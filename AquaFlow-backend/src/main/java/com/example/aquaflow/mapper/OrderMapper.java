package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Orders;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    @Insert("insert into orders(customer_id, station_id, address_id, water_type_id, quantity, source, special_note, receiver_name, receiver_phone, address_snapshot, payment_method, status, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{addressId}, #{waterTypeId}, #{quantity}, #{source}, #{specialNote}, #{receiverName}, #{receiverPhone}, #{addressSnapshot}, #{paymentMethod}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void save(Orders orders);


    List<Orders> list(@Param("stationId") Integer stationId,
                      @Param("customerId") Integer customerId,
                      @Param("status") Integer status,
                      @Param("tag") String tag,
                      @Param("createTimeStart") String createTimeStart,
                      @Param("createTimeEnd") String createTimeEnd);

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail, a.tag as addressTag, a.name as addressName, a.phone as addressPhone, " +
            "w.name as waterTypeName, w.spec as waterTypeSpec, w.price as waterTypePrice " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "left join water_type w on o.water_type_id = w.id " +
            "where o.id = #{id}")
    Orders getById(Integer id);

    @Update("update orders set status = #{status}, update_time = now() where id = #{id}")
    void updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    /** 更新订单付款状态 */
    @Update("update orders set payment_status = #{paymentStatus}, update_time = now() where id = #{id}")
    void updatePaymentStatus(@Param("id") Integer id, @Param("paymentStatus") Integer paymentStatus);

    @Select("select count(*) from orders")
    int countAll();

    @Select("select source, count(*) as count from orders group by source")
    List<Map<String, Object>> countBySource();

    @Select("select status, count(*) as count from orders group by status")
    List<Map<String, Object>> countByStatus();

    @Select("select date(create_time) as date, count(*) as count from orders where create_time >= date_sub(curdate(), interval 7 day) group by date(create_time) order by date")
    List<Map<String, Object>> trendLast7Days();

    @Select("select w.name as waterTypeName, sum(o.quantity) as totalQuantity from orders o left join water_type w on o.water_type_id = w.id group by o.water_type_id order by totalQuantity desc")
    List<Map<String, Object>> salesByWaterType();

    @Select("select c.name as customerName, count(*) as orderCount, sum(o.quantity) as totalQuantity from orders o left join customer c on o.customer_id = c.id group by o.customer_id order by totalQuantity desc limit 10")
    List<Map<String, Object>> topCustomers();

    @Select("select count(*) from orders where date(create_time) = curdate()")
    int countToday();

    @Select("select count(*) from orders where date(create_time) = curdate() and status = 3")
    int countTodayFinished();

    @Select("select ifnull(sum(o.quantity), 0) from orders o where date(o.create_time) = curdate() and o.status = 3")
    int todayFinishedQuantity();

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail, a.tag as addressTag, a.lat as addressLat, a.lng as addressLng, " +
            "w.name as waterTypeName, w.spec as waterTypeSpec " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "left join water_type w on o.water_type_id = w.id " +
            "where o.status = 1 order by o.create_time asc limit 1")
    Orders getNextOrder();

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail, a.tag as addressTag, a.lat as addressLat, a.lng as addressLng, " +
            "w.name as waterTypeName, w.spec as waterTypeSpec " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "left join water_type w on o.water_type_id = w.id " +
            "where date(o.create_time) = curdate() order by o.create_time asc")
    List<Orders> getTodayOrders();

    @Select("select o.*, c.name as customerName, c.phone as customerPhone, " +
            "a.detail as addressDetail, a.tag as addressTag, a.lat as addressLat, a.lng as addressLng, " +
            "w.name as waterTypeName, w.spec as waterTypeSpec " +
            "from orders o " +
            "left join customer c on o.customer_id = c.id " +
            "left join address a on o.address_id = a.id " +
            "left join water_type w on o.water_type_id = w.id " +
            "where o.status = 2 order by o.create_time asc")
    List<Orders> getDeliveringOrders();

    // V2: 欠桶统计
    @Select("select ifnull(sum(delivery_bucket_qty - return_bucket_qty), 0) from orders where status = 3")
    int sumBucketsOwed();

    // V2: 企业待结算订单数
    @Select("select count(*) from orders o left join customer c on o.customer_id = c.id where c.customer_type = 2 and o.settlement_status = 1 and o.status = 3")
    int countEnterprisePending();

    // V2: 按付款状态统计
    @Select("select count(*) from orders where payment_status = #{status}")
    int countByPaymentStatus(@Param("status") Integer status);

    // ========== 水厂运营平台统计查询 ==========

    @Select("select station_id as stationId, count(*) as count from orders where date(create_time) = curdate() group by station_id")
    List<Map<String, Object>> countTodayByStation();

    @Select("select station_id as stationId, sum(quantity) as totalQuantity from orders where date(create_time) = curdate() group by station_id")
    List<Map<String, Object>> sumTodayQtyByStation();

    @Select("select station_id as stationId, count(*) as count from orders where station_id is not null group by station_id")
    List<Map<String, Object>> countTotalByStation();

    @Select("select date(create_time) as date, station_id as stationId, count(*) as count from orders where station_id is not null and create_time >= date_sub(curdate(), interval 7 day) group by date(create_time), station_id order by date")
    List<Map<String, Object>> trendLast7DaysByStation();

    @Select("select station_id as stationId, sum(quantity) as totalQuantity from orders where station_id is not null group by station_id order by totalQuantity desc")
    List<Map<String, Object>> salesRankingByStation();

    @Select("select station_id as stationId, count(distinct customer_id) as customerCount from orders where station_id is not null group by station_id")
    List<Map<String, Object>> customerCountByStation();

    @Select("select station_id as stationId, date(create_time) as date, count(*) as count from orders where station_id = #{stationId} and create_time >= date_sub(curdate(), interval 7 day) group by date(create_time), station_id order by date")
    List<Map<String, Object>> dailyTrendByStation(@Param("stationId") Integer stationId);

    @Select("select station_id as stationId, date(create_time) as date, count(*) as count from orders where station_id = #{stationId} and create_time >= date_sub(curdate(), interval 365 day) group by date(create_time), station_id order by date")
    List<Map<String, Object>> yearlyTrendByStation(@Param("stationId") Integer stationId);

    @Select("select count(*) from orders where station_id = #{stationId} and date(create_time) = curdate()")
    int countTodayByStationId(@Param("stationId") Integer stationId);

    @Select("select ifnull(sum(quantity), 0) from orders where station_id = #{stationId} and date(create_time) = curdate()")
    int sumTodayQtyByStationId(@Param("stationId") Integer stationId);

    @Select("select count(*) from orders where station_id = #{stationId}")
    int countTotalByStationId(@Param("stationId") Integer stationId);

    @Select("select count(distinct customer_id) from orders where station_id = #{stationId}")
    int customerCountByStationId(@Param("stationId") Integer stationId);

    @Select("select count(distinct customer_id) from orders where station_id = #{stationId} and create_time >= date_sub(curdate(), interval 30 day) and customer_id not in (select distinct customer_id from orders where station_id = #{stationId} and create_time >= date_sub(curdate(), interval 30 day) and create_time < date_sub(curdate(), interval 15 day)) and customer_id in (select distinct customer_id from orders where station_id = #{stationId} and create_time >= date_sub(curdate(), interval 15 day))")
    int countNewCustomersByStationId(@Param("stationId") Integer stationId);

    @Select("select count(distinct customer_id) from orders where station_id = #{stationId} and create_time >= date_sub(curdate(), interval 30 day)")
    int activeCustomersLast30Days(@Param("stationId") Integer stationId);

    @Select("select count(*) from orders where station_id = #{stationId} and create_time >= date_sub(curdate(), interval #{days} day) and create_time < date_sub(curdate(), interval #{prevDays} day)")
    int countInPeriod(@Param("stationId") Integer stationId, @Param("days") Integer days, @Param("prevDays") Integer prevDays);


    /** 按关键词搜索订单（收货人姓名/电话模糊匹配） */
    @Select("select o.*, c.name as customerName, c.phone as customerPhone " +
            "from orders o left join customer c on o.customer_id = c.id " +
            "where o.receiver_name like concat('%', #{keyword}, '%') " +
            "or o.receiver_phone like concat('%', #{keyword}, '%') " +
            "order by o.create_time desc limit 50")
    List<Orders> searchByKeyword(@Param("keyword") String keyword);

    /**
     * 查询指定水站的待配送订单（status=1 待配送）
     */
    @Select("select * from orders where station_id = #{stationId} and status in (1, 4)")
    List<Orders> listPendingByStationId(@Param("stationId") Integer stationId);

}
