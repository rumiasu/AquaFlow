package com.example.aquaflow.mapper;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.Orders;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    @Options(useGeneratedKeys = true, keyProperty = "id")
    void save(Orders orders);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, a.name as addressName, a.phone as addressPhone, " +
            "a.lat as addressLat, a.lng as addressLng " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.id = #{id}")
    Orders getById(@Param("id") Long id);

    /**
     * [AQ-015 紧急止血] DB 侧原子追加备注。
     * 转单/分配等流程此前都是「读旧快照 → 内存拼字符串 → orderMapper.update 整列覆盖」，
     * 并发下后写者会丢掉前写者的备注（lost update）。改为在数据库做 concat，由 DB 行锁保证串行。
     */
    @Update("update orders set special_note = concat(coalesce(special_note, ''), case when coalesce(special_note,'')='' then '' else ' ' end, #{part}), update_time = NOW() where id = #{id}")
    int appendSpecialNote(@Param("id") Long id, @Param("part") String part);

    /** 订单状态 CAS 更新：仅当当前状态等于 expectedStatus 时才更新，返回受影响行数(0=状态已变，拒绝) */
    @Update("update orders set status = #{newStatus}, update_time = NOW() where id = #{id} and status = #{expectedStatus}")
    int updateStatusIf(@Param("id") Long id, @Param("expectedStatus") Integer expectedStatus, @Param("newStatus") Integer newStatus);

    /** 支付状态 CAS 更新：仅当当前支付状态等于 expectedStatus 时才更新 */
    @Update("update orders set payment_status = #{newPaymentStatus}, update_time = NOW() where id = #{id} and payment_status = #{expectedStatus}")
    int updatePaymentStatusIf(@Param("id") Long id, @Param("expectedStatus") Integer expectedStatus, @Param("newPaymentStatus") Integer newPaymentStatus);

    /**
     * 收到钱：把订单标记为已付款(2)，只允许从「钱还没到手」的两个状态迁入（0 未支付 / 1 待收款）。
     *
     * <p>[2026-09-16] 为什么不用 {@code updatePaymentStatusIf(id, UNPAID, PAID)}：现金单下单即
     * <b>待收款(1)</b>（库列默认值，也是 {@code PaymentStatus.textOf(1)} 与
     * {@code DashboardMapper} 的待收款金额口径），发起收款/送达都**不再**把它改成 0。
     * 若收款时仍按 {@code expected=0} 做 CAS，`affected` 恒为 0 → 钱收了、订单却永远停在待收款，
     * 待收款合计也永远清不掉。收起钱的语义就是"从 0 或 1 都能前进到 2"。</p>
     *
     * <p>已退款(3) / 已取消(4) 一律不碰（那是终态，收钱不能把它们复活）。</p>
     */
    @Update("update orders set payment_status = 2, update_time = NOW() where id = #{id} and payment_status in (0, 1)")
    int markPaidIfCollectable(@Param("id") Long id);

    /**
     * 应收核销：把订单从「未结算」推到「已结算」（2026-09-17，应收账款）。
     *
     * <p><b>CAS 的 expected 里带 {@code payment_status = 2} 不是冗余条件</b> —— 它把不变量
     * 「<b>核销 ⟹ 已收款</b>」钉在 SQL 层：没收到钱就核销，B2B 账面上会出现"账销了、钱没到"。
     * 调用方必须先经 {@link #markPaidIfCollectable} 再调本方法，且两次都要检查受影响行数
     * （拿不到行数就不能返回成功，AGENTS §8.20）。</p>
     *
     * <p>只允许 1 → 2 单向前进（AGENTS §8.18：状态不许倒滚）；已是 2 时返回 0 —— 幂等重放
     * 不得报错，但也不得重复计数。</p>
     *
     * @return 1 = 本次核销成功；0 = 未收款 / 已是已结算 / 订单不存在
     */
    @Update("update orders set settlement_status = 2, update_time = NOW() "
            + "where id = #{id} and settlement_status = 1 and payment_status = 2")
    int settleIfCollected(@Param("id") Long id);

    @Update("update orders set delivery_staff_id = #{staffId}, status = #{status}, update_time = NOW() where id = #{id}")
    void updateDeliveryStaff(@Param("id") Long id, @Param("staffId") Long staffId, @Param("status") Integer status);

    /**
     * 显式清空配送员。
     * 注意：update(Orders) 是选择性更新（XML 里 <if test="deliveryStaffId != null">），
     * 所以 setDeliveryStaffId(null) 不会写库，清空必须走这个方法。
     */
    @Update("update orders set delivery_staff_id = null, update_time = NOW() where id = #{id}")
    int clearDeliveryStaff(@Param("id") Long id);

    /**
     * 显式清空履约站与配送员（放入抢单池 / 站长拒单外派场景）。
     * 同样不能用 orderMapper.update(Orders)（选择性更新会跳过 null）。
     */
    @Update("update orders set delivery_station_id = null, delivery_staff_id = null, update_time = NOW() where id = #{id}")
    int clearDispatchStation(@Param("id") Long id);

    /** 原子接单：仅当status=PENDING时才更新，返回受影响行数(0=失败) */
    @Update("update orders set delivery_staff_id = #{staffId}, status = #{status}, update_time = NOW() where id = #{id} and status = 1")
    int updateStatusIfPENDING(@Param("id") Long id, @Param("status") Integer status, @Param("staffId") Long staffId);

    /**
     * [AQ-020] 认领（check-then-act → CAS）：仅当订单当前无配送员（或已归自己）时才认领，返回受影响行数。
     * 0 = 已被其他配送员抢先认领。
     */
    @Update("update orders set delivery_staff_id = #{staffId}, update_time = NOW() " +
            "where id = #{id} and (delivery_staff_id is null or delivery_staff_id = #{staffId})")
    int claimIfUnassigned(@Param("id") Long id, @Param("staffId") Long staffId);

    /**
     * [AQ-020] 抢单池抢单（check-then-act → CAS）：仅当订单仍在池中（delivery_station_id 为空）
     * 且状态为期望值时更新，返回受影响行数。0 = 已被其他水站抢走或状态已变。
     */
    @Update("update orders set delivery_station_id = #{stationId}, delivery_staff_id = #{staffId}, " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and delivery_station_id is null and status = #{expectedStatus}")
    int claimPoolIfFree(@Param("id") Long id, @Param("stationId") Long stationId, @Param("staffId") Long staffId,
                        @Param("newStatus") Integer newStatus, @Param("expectedStatus") Integer expectedStatus);

    /**
     * [AQ-020] 外派：仅当状态为期望值时改写履约站并清空配送员，返回受影响行数。
     * 0 = 状态已变（被并发操作），拒绝。
     */
    @Update("update orders set delivery_station_id = #{targetStationId}, delivery_staff_id = null, update_time = NOW() " +
            "where id = #{id} and status = #{expectedStatus}")
    int dispatchIfStatus(@Param("id") Long id, @Param("targetStationId") Long targetStationId,
                         @Param("expectedStatus") Integer expectedStatus);

    /**
     * [Phase C] 指派/转让配送员（CAS）：仅当当前状态 = expectedStatus 时写入，返回受影响行数。
     * <p>替代旧的「读 Orders → setDeliveryStaffId → orderMapper.update(order)」整行选择性写。
     * 后者是 read-modify-write：两个并发的「分配/转让」会互相覆盖，且会连带把内存里的旧快照
     * 写回其它列。此处只改一列，并由 status 做乐观锁。</p>
     */
    @Update("update orders set delivery_staff_id = #{staffId}, update_time = NOW() " +
            "where id = #{id} and status = #{expectedStatus}")
    int setDeliveryStaffIf(@Param("id") Long id, @Param("staffId") Long staffId,
                           @Param("expectedStatus") Integer expectedStatus);

    /** [Phase C] 指定水站外派（CAS）：履约站=目标站、清空配送员、状态=新状态，仅当当前状态 = expectedStatus。 */
    @Update("update orders set delivery_station_id = #{targetStationId}, delivery_staff_id = null, " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and status = #{expectedStatus}")
    int outsourceToStationIf(@Param("id") Long id, @Param("targetStationId") Long targetStationId,
                             @Param("newStatus") Integer newStatus, @Param("expectedStatus") Integer expectedStatus);

    /** [Phase C] 放入抢单池（CAS）：清空履约站与配送员、状态=新状态，仅当当前状态 = expectedStatus。 */
    @Update("update orders set delivery_station_id = null, delivery_staff_id = null, " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and status = #{expectedStatus}")
    int outsourceToPoolIf(@Param("id") Long id, @Param("newStatus") Integer newStatus,
                          @Param("expectedStatus") Integer expectedStatus);

    /** [Phase C] 取消外派、召回本站（CAS）：履约站=本站、清空配送员、状态=新状态，仅当当前状态 = expectedStatus。 */
    @Update("update orders set delivery_station_id = #{stationId}, delivery_staff_id = null, " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and status = #{expectedStatus}")
    int recallToStationIf(@Param("id") Long id, @Param("stationId") Long stationId,
                          @Param("newStatus") Integer newStatus, @Param("expectedStatus") Integer expectedStatus);

    /**
     * [Phase C] 指定退回-同意（CAS 守卫在备注标记上）：仅当订单仍带「[指定退回待确认]」标记时才生效，
     * 原子地把标记替换为「[指定退回-同意]」、履约站改回原归属站、清空配送员、状态=新状态。
     * <p>并发下两个站长同时点「同意」只有一个能改到（affected=1），另一个为 0。</p>
     */
    @Update("update orders set special_note = concat(replace(replace(coalesce(special_note, ''), '[指定退回待确认]', ''), '[外派]', ''), ' [指定退回-同意]'), " +
            "delivery_station_id = #{stationId}, delivery_staff_id = null, status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and special_note like '%[指定退回待确认]%'")
    int directedReturnApproveIf(@Param("id") Long id, @Param("stationId") Long stationId,
                                @Param("newStatus") Integer newStatus);

    /** [Phase C] 指定退回-拒绝（CAS 守卫在备注标记上）：标记替换为「[指定退回-拒绝]」、状态回到配送中。 */
    @Update("update orders set special_note = concat(replace(coalesce(special_note, ''), '[指定退回待确认]', ''), ' [指定退回-拒绝]'), " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and special_note like '%[指定退回待确认]%'")
    int directedReturnRejectIf(@Param("id") Long id, @Param("newStatus") Integer newStatus);

    /**
     * [Phase C] 配送完成时回写「回桶核对结果」这类纯数据字段（不含状态/支付状态，二者另行 CAS）。
     * <p>note / exceptionId 为 null 时保留库中旧值（与原 update(Orders) 的选择性更新语义一致）。</p>
     */
    @Update("update orders set return_bucket_qty = #{returnBucketQty}, barrel_discrepancy = #{barrelDiscrepancy}, " +
            "barrel_discrepancy_note = coalesce(#{barrelDiscrepancyNote}, barrel_discrepancy_note), " +
            "barrel_exception_id = coalesce(#{barrelExceptionId}, barrel_exception_id), update_time = NOW() " +
            "where id = #{id}")
    int updateDeliveryOutcome(@Param("id") Long id,
                              @Param("returnBucketQty") Integer returnBucketQty,
                              @Param("barrelDiscrepancy") Integer barrelDiscrepancy,
                              @Param("barrelDiscrepancyNote") String barrelDiscrepancyNote,
                              @Param("barrelExceptionId") Long barrelExceptionId);

    /**
     * [2026-09-13] 回写「桶异常」标记（专用列更新）。
     *
     * <p>替代 {@code OrderBarrelExceptionServiceImpl} 里两处 {@code orderMapper.update(order)}：
     * 那两处把<b>整行内存快照</b>写回，包含 `status` / `payment_status` / 金额列，
     * 于是「配送员点完成」与「站长取消退款」并发时，会把已提交的 `status=5` 覆盖回 2，
     * 随后配送完成链路的 CAS（expected=DELIVERYING）反而成功，把<b>已退款订单改成已完成</b>。</p>
     *
     * <p>同时把 `exception_count` 改成 DB 侧自增，消除「读值+1 再写回」的丢失更新。</p>
     *
     * @param returnBucketQty 为 null 时保留旧值（仅 recordReturn 场景需要写）
     */
    @Update("update orders set exception_flag = 1, " +
            "exception_category = #{exceptionCategory}, " +
            "exception_count = exception_count + 1, " +
            "barrel_exception_id = #{barrelExceptionId}, " +
            "return_bucket_qty = coalesce(#{returnBucketQty}, return_bucket_qty), " +
            "barrel_discrepancy = coalesce(#{barrelDiscrepancy}, barrel_discrepancy), " +
            "update_time = NOW() " +
            "where id = #{id}")
    int markBarrelException(@Param("id") Long id,
                            @Param("exceptionCategory") String exceptionCategory,
                            @Param("barrelExceptionId") Long barrelExceptionId,
                            @Param("returnBucketQty") Integer returnBucketQty,
                            @Param("barrelDiscrepancy") Integer barrelDiscrepancy);

    /**
     * 整行选择性更新。
     * <p><b>[Phase C] 禁止 Controller 调用</b>：只能在 Service 内部用于「非状态、非支付状态」的业务字段回写。
     * 状态请用 {@link #updateStatusIf}，支付状态请用 {@link #updatePaymentStatusIf}，
     * 配送员请用 {@link #setDeliveryStaffIf}。</p>
     */
    void update(Orders orders);

    List<Orders> list(@Param("stationId") Long stationId,
                      @Param("customerId") Long customerId,
                      @Param("status") Integer status,
                      @Param("createTimeStart") String createTimeStart,
                      @Param("createTimeEnd") String createTimeEnd,
                      @Param("limit") Integer limit,
                      @Param("offset") Integer offset);

    // [清理 2026-09-12] 删除三个全平台口径的死统计方法：countAll / countToday / countByStatus。
    // 它们不带 station_id 过滤，一旦被某个新页面顺手调用就是全平台数据泄露；
    // 而它们当前零调用（站内皮只有 countByStationId / countByStationIdAndStatus），属永久废案。

    @Select("select count(*) from orders where station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);

    @Select("select count(*) from orders where station_id = #{stationId} and status = #{status}")
    int countByStationIdAndStatus(@Param("stationId") Long stationId, @Param("status") Integer status);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.status = 1 " +
            "and o.delivery_staff_id IS NULL " +
            "order by o.create_time asc")
    List<Orders> listPendingByStationId(@Param("stationId") Long stationId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, a.name as addressName, a.phone as addressPhone " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} and o.status = #{status}")
    List<Orders> listByDeliveryStaffId(@Param("staffId") Long staffId, @Param("status") Integer status);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} and o.status = #{status} " +
            "order by o.create_time desc")
    List<Orders> listHistoryByDeliveryStaffId(@Param("staffId") Long staffId, @Param("status") Integer status);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.status = #{status} " +
            "order by o.create_time asc")
    List<Orders> listByStationIdAndStatus(@Param("stationId") Long stationId, @Param("status") Integer status);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} and o.status = #{status} and date(o.update_time) = #{date}")
    List<Orders> listByDeliveryStaffIdAndDate(@Param("staffId") Long staffId, @Param("status") Integer status, @Param("date") java.time.LocalDate date);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} and o.status = " + OrderStatus.DELIVERING + " " +
            "order by o.update_time desc")
    List<Orders> listBarrelRecords(@Param("staffId") Long staffId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, " +
            "(select t.kind from order_transfer t where t.order_id=o.id and t.status='PENDING' order by t.id desc limit 1) as transferPendingKind " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            // [AQ-015] 转单状态改由 order_transfer 结构化判定（原 special_note LIKE 已退役）
            "and exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.kind='STAFF') " +
            "order by o.update_time desc")
    List<Orders> listTransferredOrders(@Param("stationId") Long stationId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, " +
            "(select t.kind from order_transfer t where t.order_id=o.id and t.status='PENDING' order by t.id desc limit 1) as transferPendingKind " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            // [AQ-015] 退回站长（活跃）改由 order_transfer 判定
            "and exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.sub_kind='RETURN_STATION') " +
            "and o.status = 1 " +
            "order by o.update_time desc")
    List<Orders> listStationReturnOrders(@Param("stationId") Long stationId);

    /**
     * 站长「审批」页 · <b>客户发起</b>的待决策申请（目前仅取消申请）。
     *
     * <p>[2026-09-14 新增] 订单一旦被接单（配送中/已送达），客户不能再自助取消，
     * 只能提交取消申请等待站长决策；此查询即该队列。与「站内」队列（复用
     * {@link #listTransferredOrders}，kind='STAFF'）并列，构成站长端审批页的两个页签。</p>
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, " +
            "(select t.kind from order_transfer t where t.order_id=o.id and t.status='PENDING' order by t.id desc limit 1) as transferPendingKind " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' " +
            "and t.kind='CUSTOMER' and t.sub_kind='CANCEL_REQUEST') " +
            "order by o.update_time desc")
    List<Orders> listPendingCustomerCancelRequests(@Param("stationId") Long stationId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.status = " + OrderStatus.CANCELLED + " " +
            "order by o.update_time desc")
    List<Orders> listStationExceptionOrders(@Param("stationId") Long stationId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, " +
            "(select t.kind from order_transfer t where t.order_id=o.id and t.status='PENDING' order by t.id desc limit 1) as transferPendingKind " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} " +
            // [AQ-015] 转让给我（待确认）改由 order_transfer 判定
            "and exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.sub_kind='TRANSFER') " +
            "order by o.update_time desc")
    List<Orders> listIncomingTransfers(@Param("staffId") Long staffId);

    // [清理 2026-09-12] 删除 searchByKeyword(@Param("keyword"))：全仓零调用，且它是本文件里唯一
    // 一条**没有站过滤**的关键字搜索 —— SQL 里却引用了 #{stationId}（方法签名并没有这个参数）。
    // 一旦被调用，轻则报参数缺失，重则被"顺手补上参数"后变成跨站订单+客户手机号泄露。
    // 站内搜索请一律用下面的 searchByKeywordAndStation。

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and (o.receiver_name like concat('%', #{keyword}, '%') " +
            "or o.receiver_phone like concat('%', #{keyword}, '%') " +
            "or c.name like concat('%', #{keyword}, '%') " +
            "or c.phone like concat('%', #{keyword}, '%')) " +
            "order by o.create_time desc limit 50")
    List<Orders> searchByKeywordAndStation(@Param("stationId") Long stationId, @Param("keyword") String keyword);

    // [清理 2026-09-12] 删除 updateClaimStation：全仓零调用（抢单/外派已统一走 claimPoolIfFree /
    // dispatchIfStatus / outsourceTo*If 等带 expected-state 的 CAS 方法），属永久废案。

    @Select("select count(*) from orders where station_id = #{stationId} and date(create_time) = curdate()")
    int countTodayByStationId(@Param("stationId") Long stationId);

    @Select("select * from orders where idempotency_key = #{key} limit 1")
    Orders findByIdempotencyKey(@Param("key") String idempotencyKey);

    @Select("select status, count(*) as cnt from orders where station_id = #{stationId} group by status")
    List<java.util.Map<String, Object>> countByStatusByStationId(@Param("stationId") Long stationId);

    @Select("select date(create_time) as dt, count(*) as cnt from orders " +
            "where station_id = #{stationId} " +
            "and create_time >= date_sub(curdate(), interval 6 day) " +
            "group by date(create_time) order by dt")
    List<java.util.Map<String, Object>> trendLast7DaysByStationId(@Param("stationId") Long stationId);

    // ==================== 抢单池 & 外派追踪 ====================

    /**
     * 站长待分配列表：本站 status=1 且未分配配送员的订单。
     * 另含「转单中」订单（order_transfer 有 DIRECTED 待确认，归属本站、等待站长同意/拒绝），
     * 这类单可能仍挂着原配送员，前端按状态渲染成「同意/拒绝」而非「分配/外派」。
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, " +
            "(select t.kind from order_transfer t where t.order_id=o.id and t.status='PENDING' order by t.id desc limit 1) as transferPendingKind " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where ( " +
            "  (o.station_id = #{stationId} AND o.delivery_station_id IS NULL) " +
            "  OR o.delivery_station_id = #{stationId} " +
            // [AQ-015] 指定退回待确认改由 order_transfer 判定
            "  OR (o.station_id = #{stationId} AND exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.kind='DIRECTED')) " +
            ") " +
            "and o.status = 1 " +
            "and (o.delivery_staff_id IS NULL OR exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.kind='DIRECTED')) " +
            "and (o.special_note IS NULL OR INSTR(o.special_note, '外派') = 0 OR o.delivery_station_id = #{stationId} " +
            "     OR exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.kind='DIRECTED')) " +
            "order by o.create_time asc")
    List<Orders> listStationPendingUnassigned(@Param("stationId") Long stationId);

    /**
     * 配送员待接单列表：分配给我但还未接单的订单
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, " +
            "(select t.kind from order_transfer t where t.order_id=o.id and t.status='PENDING' order by t.id desc limit 1) as transferPendingKind " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} " +
            "and o.status = 1 " +
            // [AQ-015] 排除已发起指定退回（转单中）的单，改由 order_transfer 判定
            "and not exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.kind='DIRECTED') " +
            "order by o.create_time asc")
    List<Orders> listAssignedToStaff(@Param("staffId") Long staffId);

    /**
     * 抢单池列表：delivery_station_id IS NULL 的待外派订单
     * 排除自己水站的订单（不能抢自己的单）
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_station_id IS NULL " +
            "and o.status = 1 " +
            "and o.station_id != #{stationId} " +
            "order by o.create_time asc")
    List<Orders> listPoolOrders(@Param("stationId") Long stationId);

    /**
     * 外派追踪列表：本站外派出去的订单
     * 特殊标记包含 [外派] 且 delivery_station_id IS NULL（在池中）
     * 或已被其他站抢单（delivery_station_id != null 且 != station_id）
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.special_note like '%[外派]%' " +
            "and o.special_note NOT LIKE '%[指定退回待确认]%' " +
            "order by o.update_time desc")
    List<Orders> listDispatchedOrders(@Param("stationId") Long stationId);

    /**
     * 原归属站视角：被指定水站退回、等待站长同意的订单
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, " +
            "(select t.kind from order_transfer t where t.order_id=o.id and t.status='PENDING' order by t.id desc limit 1) as transferPendingKind " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            // [AQ-015] 待原站确认的指定退回改由 order_transfer 判定
            "and exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.kind='DIRECTED') " +
            "and o.status = 1 " +
            "order by o.update_time desc")
    List<Orders> listDirectedReturns(@Param("stationId") Long stationId);

    /**
     * 目标水站视角：本水站被指定为履约站、但归属站为其他水站的订单（他站外派给我）
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, " +
            "(select t.kind from order_transfer t where t.order_id=o.id and t.status='PENDING' order by t.id desc limit 1) as transferPendingKind " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_station_id = #{stationId} " +
            "and o.station_id != #{stationId} " +
            "and o.status in (1, 2, 3) " +
            // [AQ-015] 排除已发起「指定退回待确认」的单，改由 order_transfer 判定
            "and not exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' and t.kind='DIRECTED') " +
            "order by o.update_time desc")
    List<Orders> listDirectedIncoming(@Param("stationId") Long stationId);

    /**
     * 本水站「待收款」订单数（站长看板统计）。
     * <p>口径：履约站为本水站（外派单由履约站收款）、订单未闭环、支付态非 已付/已退款、且非水票支付。</p>
     */
    @Select("select count(*) from orders o " +
            "where coalesce(o.delivery_station_id, o.station_id) = #{stationId} " +
            "and o.status in (1, 2, 3) " +
            "and o.payment_method = 2 " +
            "and o.payment_status != 2")
    int countUncollected(@Param("stationId") Long stationId);

    /**
     * 获取客户最近一笔订单的水站信息（用于重新登录后自动选站）
     */
    java.util.Map<String, Object> getLatestStationByCustomerId(@Param("customerId") Long customerId);

}