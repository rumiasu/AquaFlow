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

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail, a.name as addressName, a.phone as addressPhone, " +
            "a.lat as addressLat, a.lng as addressLng " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.id = #{id}")
    Orders getById(@Param("id") Long id);

    @Update("update orders set status = #{status}, update_time = NOW() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    @Update("update orders set delivery_staff_id = #{staffId}, status = #{status}, update_time = NOW() where id = #{id}")
    void updateDeliveryStaff(@Param("id") Long id, @Param("staffId") Long staffId, @Param("status") Integer status);

    @Update("update orders set payment_status = #{paymentStatus}, update_time = NOW() where id = #{id}")
    void updatePaymentStatus(@Param("id") Long id, @Param("paymentStatus") Integer paymentStatus);

    /** 原子接单：仅当status=PENDING时才更新，返回受影响行数(0=失败) */
    @Update("update orders set delivery_staff_id = #{staffId}, status = #{status}, update_time = NOW() where id = #{id} and status = 1")
    int updateStatusIfPENDING(@Param("id") Long id, @Param("status") Integer status, @Param("staffId") Long staffId);

    void update(Orders orders);

    List<Orders> list(@Param("stationId") Long stationId,
                      @Param("customerId") Long customerId,
                      @Param("status") Integer status,
                      @Param("createTimeStart") String createTimeStart,
                      @Param("createTimeEnd") String createTimeEnd);

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

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.status = 1 " +
            "and o.delivery_staff_id IS NULL " +
            "order by o.create_time asc")
    List<Orders> listPendingByStationId(@Param("stationId") Long stationId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail, a.name as addressName, a.phone as addressPhone " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} and o.status = #{status}")
    List<Orders> listByDeliveryStaffId(@Param("staffId") Long staffId, @Param("status") Integer status);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} and o.status = #{status} " +
            "order by o.create_time desc")
    List<Orders> listHistoryByDeliveryStaffId(@Param("staffId") Long staffId, @Param("status") Integer status);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.status = #{status} " +
            "order by o.create_time asc")
    List<Orders> listByStationIdAndStatus(@Param("stationId") Long stationId, @Param("status") Integer status);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} and o.status = #{status} and date(o.update_time) = #{date}")
    List<Orders> listByDeliveryStaffIdAndDate(@Param("staffId") Long staffId, @Param("status") Integer status, @Param("date") java.time.LocalDate date);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} and o.status = " + OrderStatus.DELIVERING + " " +
            "order by o.update_time desc")
    List<Orders> listBarrelRecords(@Param("staffId") Long staffId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and (o.special_note like '%[转让]%' or o.special_note like '%[退回站长]%' or o.special_note like '%[重分配]%') " +
            "order by o.update_time desc")
    List<Orders> listTransferredOrders(@Param("stationId") Long stationId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.special_note like '%[退回站长]%' " +
            "and o.status = 1 " +
            "order by o.update_time desc")
    List<Orders> listStationReturnOrders(@Param("stationId") Long stationId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.status = " + OrderStatus.CANCELLED + " " +
            "order by o.update_time desc")
    List<Orders> listStationExceptionOrders(@Param("stationId") Long stationId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} " +
            "and o.special_note like '%[转让]%' " +
            "order by o.update_time desc")
    List<Orders> listIncomingTransfers(@Param("staffId") Long staffId);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
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

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
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
     * 站长待分配列表：本站 status=1 且未分配配送员的订单
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.status = 1 " +
            "and o.delivery_staff_id IS NULL " +
            "order by o.create_time asc")
    List<Orders> listStationPendingUnassigned(@Param("stationId") Long stationId);

    /**
     * 配送员待接单列表：分配给我但还未接单的订单
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.delivery_staff_id = #{staffId} " +
            "and o.status = 1 " +
            "order by o.create_time asc")
    List<Orders> listAssignedToStaff(@Param("staffId") Long staffId);

    /**
     * 抢单池列表：delivery_station_id IS NULL 的待外派订单
     * 排除自己水站的订单（不能抢自己的单）
     */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
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
    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "where o.station_id = #{stationId} " +
            "and o.special_note like '%[外派]%' " +
            "order by o.update_time desc")
    List<Orders> listDispatchedOrders(@Param("stationId") Long stationId);

    /**
     * 获取客户最近一笔订单的水站信息（用于重新登录后自动选站）
     */
    java.util.Map<String, Object> getLatestStationByCustomerId(@Param("customerId") Long customerId);

}