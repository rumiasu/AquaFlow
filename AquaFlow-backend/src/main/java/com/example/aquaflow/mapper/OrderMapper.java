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

    @Update("update orders set status = #{status}, update_time = NOW() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

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

    @Update("update orders set payment_status = #{paymentStatus}, update_time = NOW() where id = #{id}")
    void updatePaymentStatus(@Param("id") Long id, @Param("paymentStatus") Integer paymentStatus);

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

    void update(Orders orders);

    List<Orders> list(@Param("stationId") Long stationId,
                      @Param("customerId") Long customerId,
                      @Param("status") Integer status,
                      @Param("createTimeStart") String createTimeStart,
                      @Param("createTimeEnd") String createTimeEnd,
                      @Param("limit") Integer limit,
                      @Param("offset") Integer offset);

    @Select("select count(*) from orders")
    int countAll();

    @Select("select count(*) from orders where date(create_time) = curdate()")
    int countToday();

    @Select("select count(*) from orders where status = #{status}")
    int countByStatus(@Param("status") Integer status);

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
    List<Orders> searchByKeyword(@Param("keyword") String keyword);

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

    @Update("update orders set station_id=#{stationId}, delivery_station_id=#{deliveryStationId}, " +
            "status=#{status}, special_note=#{specialNote}, update_time=NOW() where id=#{id}")
    void updateClaimStation(@Param("id") Long id, @Param("stationId") Long stationId,
                            @Param("deliveryStationId") Long deliveryStationId,
                            @Param("status") Integer status,
                            @Param("specialNote") String specialNote);

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