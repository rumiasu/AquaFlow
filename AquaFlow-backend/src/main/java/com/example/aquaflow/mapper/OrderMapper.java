package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Orders;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    @Insert("insert into orders(customer_id, address_id, water_type_id, quantity, source, special_note, receiver_name, receiver_phone, address_snapshot, payment_method, status, create_time, update_time) " +
            "values(#{customerId}, #{addressId}, #{waterTypeId}, #{quantity}, #{source}, #{specialNote}, #{receiverName}, #{receiverPhone}, #{addressSnapshot}, #{paymentMethod}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void save(Orders orders);


    List<Orders> list(@Param("customerId") Integer customerId,
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

}
