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
     *
     * <p>[v47] 清空履约站 ⟹ 结算站回<b>归属站</b>（营收跟着"退回池 = 这单又归我"走）。
     * ⚠️ 与本类其它改履约站的语句不同，这里回的是 {@code station_id} 而<b>不是</b>某个传入值：
     * 在池中的单没有履约站，营收只能记归属站 —— 这就是 v47 取值规则里的"退回池 = 回 station_id"。</p>
     *
     * <p>当前<b>零调用</b>（放池/拒单外派已统一走带 expected-state 的 {@link #outsourceToPoolIf}）。
     * 留在这里是为了不让它成为下一个人的陷阱：它会改 delivery_station_id，就必须同步 settle。</p>
     */
    @Update("update orders set delivery_station_id = null, settle_station_id = station_id, "
            + "delivery_staff_id = null, update_time = NOW() where id = #{id}")
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
     *
     * <p>[v47] 抢到手的站既履约也结算（settle_station_id 与 delivery_station_id 同一个
     * {@code #{stationId}}）：按产品裁定「水费 + 配送费 + 楼层费归实际配送站」。两列必须在
     * <b>同一条语句</b>里写 —— 分成两次 UPDATE，中间失败就会留下"履约站是 B、营收还在 A"的脏行。</p>
     */
    @Update("update orders set delivery_station_id = #{stationId}, settle_station_id = #{stationId}, "
            + "delivery_staff_id = #{staffId}, "
            + "status = #{newStatus}, update_time = NOW() "
            + "where id = #{id} and delivery_station_id is null and status = #{expectedStatus}")
    int claimPoolIfFree(@Param("id") Long id, @Param("stationId") Long stationId, @Param("staffId") Long staffId,
                        @Param("newStatus") Integer newStatus, @Param("expectedStatus") Integer expectedStatus);

    /**
     * [AQ-020] 外派：仅当状态为期望值时改写履约站并清空配送员，返回受影响行数。
     * 0 = 状态已变（被并发操作），拒绝。
     *
     * <p>[v47] 定向外派把营收一并交给目标站（同 {@link #claimPoolIfFree}）。
     * <b>定价不变</b>：费用仍是下单时按归属站算好的快照，外派不重算（docs/design/17 §4.3）。</p>
     */
    @Update("update orders set delivery_station_id = #{targetStationId}, settle_station_id = #{targetStationId}, "
            + "delivery_staff_id = null, update_time = NOW() "
            + "where id = #{id} and status = #{expectedStatus}")
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

    /** [Phase C] 指定水站外派（CAS）：履约站=目标站、清空配送员、状态=新状态，仅当当前状态 = expectedStatus。
     *  <p>[v47] 营收随履约站走（结算站=目标站，见 {@link #claimPoolIfFree}）。</p> */
    @Update("update orders set delivery_station_id = #{targetStationId}, settle_station_id = #{targetStationId}, " +
            "delivery_staff_id = null, " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and status = #{expectedStatus}")
    int outsourceToStationIf(@Param("id") Long id, @Param("targetStationId") Long targetStationId,
                             @Param("newStatus") Integer newStatus, @Param("expectedStatus") Integer expectedStatus);

    /** [Phase C] 放入抢单池（CAS）：清空履约站与配送员、状态=新状态，仅当当前状态 = expectedStatus。
     *  <p>[v47] 结算站回<b>归属站</b>（在池中没人履约，营收只能记归属站；见 {@link #clearDispatchStation}）。</p> */
    @Update("update orders set delivery_station_id = null, settle_station_id = station_id, " +
            "delivery_staff_id = null, " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and status = #{expectedStatus}")
    int outsourceToPoolIf(@Param("id") Long id, @Param("newStatus") Integer newStatus,
                          @Param("expectedStatus") Integer expectedStatus);

    /** [Phase C] 取消外派、召回本站（CAS）：履约站=本站、清空配送员、状态=新状态，仅当当前状态 = expectedStatus。
     *  <p>[v47] 营收一并召回（结算站=本站）。</p> */
    @Update("update orders set delivery_station_id = #{stationId}, settle_station_id = #{stationId}, " +
            "delivery_staff_id = null, " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and status = #{expectedStatus}")
    int recallToStationIf(@Param("id") Long id, @Param("stationId") Long stationId,
                          @Param("newStatus") Integer newStatus, @Param("expectedStatus") Integer expectedStatus);

    /**
     * [Phase C] 指定退回-同意（CAS 守卫在备注标记上）：仅当订单仍带「[指定退回待确认]」标记时才生效，
     * 原子地把标记替换为「[指定退回-同意]」、履约站改回原归属站、清空配送员、状态=新状态。
     * <p>并发下两个站长同时点「同意」只有一个能改到（affected=1），另一个为 0。</p>
     * <p>[v47] 营收随之退回归属站（同 {@link #outsourceToPoolIf}：退回 = 这单又归原站）。</p>
     */
    @Update("update orders set special_note = concat(replace(replace(coalesce(special_note, ''), '[指定退回待确认]', ''), '[外派]', ''), ' [指定退回-同意]'), " +
            "delivery_station_id = #{stationId}, settle_station_id = #{stationId}, " +
            "delivery_staff_id = null, status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and special_note like '%[指定退回待确认]%'")
    int directedReturnApproveIf(@Param("id") Long id, @Param("stationId") Long stationId,
                                @Param("newStatus") Integer newStatus);

    /** [Phase C] 指定退回-拒绝（CAS 守卫在备注标记上）：标记替换为「[指定退回-拒绝]」、状态回到配送中。 */
    @Update("update orders set special_note = concat(replace(coalesce(special_note, ''), '[指定退回待确认]', ''), ' [指定退回-拒绝]'), " +
            "status = #{newStatus}, update_time = NOW() " +
            "where id = #{id} and special_note like '%[指定退回待确认]%'")
    int directedReturnRejectIf(@Param("id") Long id, @Param("newStatus") Integer newStatus);

    /**
     * 配送员上报楼层（选填，v43）。
     *
     * <p>⚠️ 带 {@code reported_floor is null} 条件 = **只写一次**：完工那一刻的快照，
     * 之后任何路径（包括站长看单、客户申诉）都不许悄悄改写发钱的依据。真要改，走人工调整（ADJUST）留痕。</p>
     */
    @Update("update orders set reported_floor = #{floor}, update_time = NOW() "
            + "where id = #{orderId} and reported_floor is null")
    int saveReportedFloor(@Param("orderId") Long orderId, @Param("floor") Integer floor);

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

    /**
     * 配送员待接单列表：本站 status=1、未分配配送员、且**钱已经到手**的订单。
     *
     * <p>「钱已经到手」只有两条路，别再加第三条：</p>
     * <ol>
     *   <li>{@code payment_status = 2}（已付）—— 微信/水票走的都是这条：<b>谁付款成功谁自动出现</b>。
     *       水票的扣减发生在客户端下单后那次支付请求里，扣成功即置 2，于是它和微信支付成功
     *       完全同一条线（不要为了"水票下单即算已付"在下单时抢着扣票：那会让票不够的客户
     *       <b>连单都下不出来</b>，而现有流程是"先下单、再扣票"）。</li>
     *   <li>{@code payment_method = 2}（现金 = 货到付款）—— <b>钱要当面收，不能等付了才派人</b>，
     *       所以它必须在钱没到时就进视野。它的安全性由**下单闸门**保证：客户没在本站开通
     *       货到付款时，{@code OrderServiceImpl.createOrder} 直接拒单，所以"现金单"本身
     *       就等价于"允许货到付款的客户"。</li>
     * </ol>
     *
     * <p>⚠️ 被排除的是**还没付钱的微信单**（渠道未接入，见 {@code PayMethod}）：客户下单 ≠ 收到钱。
     * TODO(微信支付接入)：回调里把 {@code payment_status} 置 2 之后，本条件会**自动**把它放出来 ——
     * 所以**不要**在这里加"渠道未接入"之类的特例，也不要在别处另写一套推送判据
     * （全仓三处：本方法、{@link #listStationPendingUnassigned}、接单/分配的业务闸门）。</p>
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.status = 1 " +
            "and o.delivery_staff_id IS NULL " +
            // 已收款，或货到付款（现金）：没收到钱的单不进站长/配送员的视野
            "and (o.payment_status = 2 or o.payment_method = 2) " +
            "order by o.create_time asc")
    List<Orders> listPendingByStationId(@Param("stationId") Long stationId);

    /**
     * 超时未支付的**微信**单（供定时任务自动取消，2026-09-18 产品裁定：只对微信单，货到付款是特殊）。
     *
     * <p>判据必须同时满足四条：① {@code payment_method = 1}（微信）—— 现金是货到付款、水票是扣票后
     * 才算已付，都不适用；② {@code payment_status in (0,1)}（还没收到钱）；③ {@code status = 1}（还在待配送，
     * 已经出车的不能自动取消）；④ 建单超过阈值分钟数。阈值是参数，不写死在这里。</p>
     *
     * <p>⚠️ 别把判据放宽成"所有未付单"：现金单未付是**正常经营状态**（钱要当面收），
     * 水票单未付是"票还没扣"（客户可能正在充值），自动取消它们等于替客户做决定。</p>
     */
    @Select("select id from orders where payment_method = 1 and payment_status in (0, 1) and status = 1 " +
            "and create_time < date_sub(now(), interval #{minutes} minute) " +
            "order by id limit #{limit}")
    List<Long> listTimedOutWechatOrders(@Param("minutes") int minutes, @Param("limit") int limit);

    /**
     * 本站作为**履约站**接下的跨站单（归属站 ≠ 本站），供站长端「订单」页归并展示（2026-09-18 产品裁定）。
     *
     * <p>产品原话：「跨站单订单可以算，只是不能看用户画像，但是可以把跨站单统一成一个，
     * 统一看接了多少跨站单。」所以这里按<b>履约站</b>取数（谁送货谁算），
     * 与「站长端订单列表按归属站取数」是两套口径 —— 那张列表只列本站自己的单。</p>
     *
     * <p>⚠️ 返回的是<b>原始实体</b>（带 {@code customerName} / {@code customerPhone}），
     * 调用方**必须**先过 {@code CustomerProfileMask.maskIfCrossStation} 再出网 ——
     * 这几行全是别站的客户，画像不能下发。状态只取 1/2/3/4（已取消的不算"接了多少单"）。</p>
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "(select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_station_id = #{stationId} and o.station_id <> #{stationId} " +
            "and o.status in (1, 2, 3, 4) " +
            "order by o.create_time desc")
    List<Orders> listCrossStationOrders(@Param("stationId") Long stationId);

    /**
     * 该客户在本站的**历史订单数**（不含已取消）—— 判"这是不是他在本站的第一单"（v48）。
     *
     * <p>口径：`orders.station_id = 本站`（归属站，不是履约站）—— 客户是在这个站下的单，
     * 货到付款是"这个站敢不敢让他赊账"，与谁去送无关。</p>
     */
    @Select("select count(*) from orders where customer_id = #{customerId} and station_id = #{stationId} and status <> 5")
    int countCustomerOrdersAtStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 该客户在本站**逾期未结的现金单**张数与金额（v48 的「欠款即停」判据）。
     *
     * <p>判据只用现有列现算（不发明新规则）：{@code payment_status = 1}（待收款，钱还没到手）
     * 且 {@code status <> 5}（未取消）且 {@code payment_method = 2}（现金）且
     * {@code due_date < curdate()}（账期已过）。{@code due_date} 是下单时按客户账期快照的
     * （{@code ReceivableService.resolveDueDate}），**只有现金单才有**，所以这里再加一个
     * {@code due_date is not null} 只是把语义写明，不改变结果集。</p>
     */
    @Select("select count(*) from orders where customer_id = #{customerId} and station_id = #{stationId} "
            + "and status <> 5 and payment_status = 1 and payment_method = 2 "
            + "and due_date is not null and due_date < curdate()")
    int countOverdueCashOrders(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 见 {@link #countOverdueCashOrders}：同一判据的金额合计（给站长在开通弹窗里看到"欠了多少"）。 */
    @Select("select coalesce(sum(total_amount), 0) from orders where customer_id = #{customerId} and station_id = #{stationId} "
            + "and status <> 5 and payment_status = 1 and payment_method = 2 "
            + "and due_date is not null and due_date < curdate()")
    java.math.BigDecimal sumOverdueCashAmount(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 本站**有赊账的客户**批量摘要（未结赊账 / 逾期笔数 / 最长逾期天数）—— 订单列表上色用。
     *
     * <p>一次查完，避免列表页逐行算风险造成 N+1。判据与 {@link #sumOutstandingCredit}、
     * {@link #countOverdueCashOrders}、{@link #maxOverdueDaysForCredit} **同源**
     * （同一组 where 条件），四处必须一起改。</p>
     */
    @Select("select customer_id as customerId, coalesce(sum(water_amount), 0) as outstandingCredit, "
            + "sum(case when due_date is not null and due_date < curdate() then 1 else 0 end) as overdueCount, "
            + "coalesce(max(case when due_date is not null and due_date < curdate() "
            + "                  then datediff(curdate(), due_date) else 0 end), 0) as maxOverdueDays "
            + "from orders where station_id = #{stationId} and status <> 5 "
            + "and payment_status = 1 and payment_method = 2 "
            + "group by customer_id")
    List<java.util.Map<String, Object>> creditSummaryByStation(@Param("stationId") Long stationId);

    /**
     * 该客户在本站赊账的**最长逾期天数**（0 = 没有逾期的）—— 风险等级升级为「冻结」的判据。     *
     * <p>判据与 {@link #countOverdueCashOrders} / {@link #sumOverdueCashAmount} **逐字同源**
     * （同一组 where 条件），三处必须一起改：分叉会出现"看板说逾期 20 天、却按 5 天判冻结"。</p>
     */
    @Select("select coalesce(max(datediff(curdate(), due_date)), 0) from orders "
            + "where customer_id = #{customerId} and station_id = #{stationId} "
            + "and status <> 5 and payment_status = 1 and payment_method = 2 "
            + "and due_date is not null and due_date < curdate()")
    int maxOverdueDaysForCredit(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 该客户在本站**未结清的赊账**（含未到期与已逾期）—— 验资与额度用。
     *
     * <p>两个口径都必须与"可赊额度"对齐，否则会自己把自己判成超额度：</p>
     * <ul>
     *   <li>只算 {@code payment_method = 2}（现金/赊账）：微信未付单与水票未付单不是赊账，
     *       它们收不到钱根本不会进配送流程；</li>
     *   <li>只算 {@code water_amount}：<b>押金不算</b> —— 押金是客户资产（可退）、由他手上的桶担保，
     *       把它算进"欠款"会凭空放大敞口。这与 {@code EnterpriseIdentityService} 的大额口径同源
     *       （产品原话：「企业的只看水，押金不算」）。</li>
     * </ul>
     * <p>⚠️ 2026-09-21 实测踩过：这里原先用 {@code total_amount}（水费 + 押金），
     * 而额度只按水费算 —— 于是"按额度上限买 20 桶水"会被判成超额度（1000 &gt; 400）。</p>
     */
    @Select("select coalesce(sum(water_amount), 0) from orders where customer_id = #{customerId} and station_id = #{stationId} "
            + "and status <> 5 and payment_status = 1 and payment_method = 2")
    java.math.BigDecimal sumOutstandingCredit(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 该客户在本站近 N 天的**水费**合计 —— 验资用（算"他平时一个月买多少水"）。
     *
     * <p>只算 {@code water_amount}：押金是客户资产不是消费、配送费与楼层费是履约成本，
     * 三者都不代表这个客户的采购规模（与 {@code EnterpriseIdentityService} 的大额口径同源：只算水）。</p>
     */
    @Select("select coalesce(sum(water_amount), 0) from orders "
            + "where customer_id = #{customerId} and station_id = #{stationId} and status <> 5 "
            + "and create_time >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)")
    java.math.BigDecimal sumWaterAmountSince(@Param("customerId") Long customerId,
                                             @Param("stationId") Long stationId,
                                             @Param("days") int days);

    /**
     * 本站**每个客户**近 N 天的水费合计（{@code customerId → water90}）—— 订单列表批量算可赊额度用。
     *
     * <p>⚠️ where 条件必须与 {@link #sumWaterAmountSince} <b>逐字同源</b>（只多"按客户分组"这一处）：
     * 少一个条件，列表算出的额度就与客户详情页 / 退押金拦截用的是两个数 ——
     * 同一个客户在列表上标黄、点进去变红（口径只有一份，见 AGENTS §6.1）。</p>
     */
    @Select("select customer_id as customerId, coalesce(sum(water_amount), 0) as water90 from orders "
            + "where station_id = #{stationId} and status <> 5 "
            + "and create_time >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) group by customer_id")
    List<java.util.Map<String, Object>> waterAmountSinceByStation(@Param("stationId") Long stationId,
                                                                 @Param("days") int days);

    /**
     * 该客户在本站**还没结清的挂账单**（有应付日期、未付、未取消）—— 供「账期重算」用。
     *
     * <p>只取挂账单（{@code due_date is not null}）：即时结清的单下单时就没有账期，
     * 重算时不该给它凭空长出一个。</p>
     */
    @Select("select * from orders where customer_id = #{customerId} and station_id = #{stationId} "
            + "and status <> 5 and payment_status = 1 and due_date is not null order by id")
    List<Orders> listUnsettledWithDueDate(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 改一张**还没结清**的挂账单的应付日期（「账期重算」专用）。
     *
     * <p>⚠️ {@code orders.due_date} 的常规约定是<b>下单时快照、之后只读</b>（与金额/地址快照同源）。
     * 本方法是那个约定的**唯一例外**，且只允许站长显式触发；CAS 里的三个条件
     * （{@code due_date is not null}、{@code payment_status = 1}、{@code status <> 5}）
     * 保证它改不到已付/已取消/即时结清的单 —— 拿不到行数就跳过，不报错。</p>
     */
    @Update("update orders set due_date = #{dueDate}, update_time = NOW() where id = #{id} "
            + "and due_date is not null and payment_status = 1 and status <> 5")
    int updateDueDateIfUnsettled(@Param("id") Long id, @Param("dueDate") java.time.LocalDate dueDate);

    // ⚠️ 比同族查询多带 a.floor / a.has_elevator：这是**配送员自己的任务列表**，
    // 他要据此知道这一单要不要上楼（P0-2 的楼层字段此前只有计价在用，见 Orders 的字段注释）。
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail, a.name as addressName, a.phone as addressPhone, " +
            "a.floor as addressFloor, a.has_elevator as addressHasElevator " +
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

    /**
     * 同上，但**按 {@code sub_kind} 过滤** —— 给「待办汇总」拆"转单请求 / 站内取消申请"用。
     *
     * <p>为什么不改 {@link #listTransferredOrders} 的契约：它另有三个调用方
     * （两个列表端点 + 站长「审批」页的 pending-approvals），改签名等于顺手扩大风险面；
     * 而这里要的只是"同一批数据切一刀"。SQL 主体与它逐字一致，**只有过滤条件多一项** ——
     * 两处若将来要改口径（比如推送闸门），必须同时改（判据：它们回答的是同一个问题）。</p>
     *
     * <p>⚠️ {@code subKinds} 为空列表时**返回空集**（MyBatis 会把空集合渲染成 {@code in ()}，
     * 是语法错误）—— 调用方不要传空。</p>
     *
     * @param subKinds 见 {@code constant/PendingItem.STAFF_TRANSFER_SUB_KINDS} /
     *                 {@code PendingItem.SUB_CANCEL_REQUEST}（两者互补，新增子类时必须归类）
     */
    @Select("<script>select o.*, c.name as customerName, c.phone as customerPhone, " +
            "(select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and exists (select 1 from order_transfer t where t.order_id=o.id and t.status='PENDING' " +
            "and t.kind='STAFF' and t.sub_kind in " +
            "<foreach collection='subKinds' item='sk' open='(' separator=',' close=')'>#{sk}</foreach>) " +
            "order by o.update_time desc</script>")
    List<Orders> listStaffRequestsBySubKinds(@Param("stationId") Long stationId,
                                            @Param("subKinds") List<String> subKinds);

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

    // [2026-09-18 删除] listStationExceptionOrders(stationId)：只服务于
    // GET /api/delivery/orders/station-exception —— 那个端点名字叫"异常"、实际过滤 status=5 返回**取消单**，
    // 与 GET /api/orders?status=5 重复，两端小程序都没调用（docs/audit/2026-09-16-死端点评估.md 判"删除"，已执行）。
    // 要看本站取消单请走订单列表接口；不要再按"异常"这个名字把本方法加回来。
    // 回归：ManagerOrderControllerRemovedIntegrationTest 断言该路径返回 404。

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

    // [2026-09-18 删除] countByStatusByStationId / trendLast7DaysByStationId：只服务于
    // GET /api/dashboard/order-status 与 /order-trend，两个端点零前端调用且与 /report 口径分叉
    // （同一指标两套算法，见 docs/audit/2026-09-16-死端点评估.md §5.2/§5.4，判"删除"，已执行）。
    // 看板一律走 DashboardService.report()；不要再把这两个"同名不同算法"的查询加回来。

    // ==================== 抢单池 & 外派追踪 ====================

    /**
     * 站长待分配列表：本站 status=1 且未分配配送员的订单。
     * 另含「转单中」订单（order_transfer 有 DIRECTED 待确认，归属本站、等待站长同意/拒绝），
     * 这类单可能仍挂着原配送员，前端按状态渲染成「同意/拒绝」而非「分配/外派」。
     *
     * <p>⚠️ 与 {@link #listPendingByStationId} 同一条推送判据：**没收到钱的单不进站长视野**
     * （现金与水票除外，理由见该方法的 javadoc）。改一处必须改另一处，否则
     * 「站长看得到、配送员看不到」两边分叉。</p>
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, c.customer_type as customerType, (select oi.product_name_snapshot from order_item oi where oi.order_id=o.id order by oi.id limit 1) as firstProductName, " +
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
            // 与 listPendingByStationId 同一道推送闸门：已收款 或 货到付款（现金）
            "and (o.payment_status = 2 or o.payment_method = 2) " +
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
     * <p>口径：<b>结算站</b>为本水站（v47：谁结算谁催收 —— 与应收台账
     * {@code ReceivableMapper.listOrders} 同一个站别口径）、订单未闭环、支付态非 已付/已退款、且非水票支付。</p>
     *
     * <p>⚠️ 本方法此前用 {@code coalesce(delivery_station_id, station_id)}，而应收台账那一套用
     * {@code station_id} → 同一句"待收款"在首页看板与应收台账里是<b>两个订单集合</b>
     * （跨站外派单只出现在其中一边）。2026-09-18 统一到 v47 结算站口径。
     * <b>两边仍未统一的是谓词</b>（本方法限现金单且 status in (1,2,3)；台账是
     * {@code payment_status=1 and status<>5}，不限支付方式）—— 那是两个不同的指标
     * （"还有几笔现金要收" vs "待收款台账"），不是站别分叉，别再顺手改成一样。</p>
     */
    @Select("select count(*) from orders o " +
            "where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} " +
            "and o.status in (1, 2, 3) " +
            "and o.payment_method = 2 " +
            "and o.payment_status != 2")
    int countUncollected(@Param("stationId") Long stationId);

    /**
     * 获取客户最近一笔订单的水站信息（用于重新登录后自动选站）
     */
    java.util.Map<String, Object> getLatestStationByCustomerId(@Param("customerId") Long customerId);

}