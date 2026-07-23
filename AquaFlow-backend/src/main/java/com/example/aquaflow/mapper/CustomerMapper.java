package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Customer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface CustomerMapper {
    List<Customer> list();

    @Select("select * from customer where station_id = #{stationId}")
    List<Customer> listByStationId(@Param("stationId") Integer stationId);

    @Insert("insert into customer(name, phone, note, create_time, update_time) "+
            "value (#{name},#{phone},#{note},#{createTime},#{updateTime})")
    void insert(Customer customer);

    @Select("select * from customer where id=#{id}")
    Customer getById(Integer id);

    void update(Customer customer);

    @Select("select count(*) from customer")
    int countAll();

    @Select("select * from customer where name like concat('%', #{keyword}, '%') or phone like concat('%', #{keyword}, '%')")
    List<Customer> search(@Param("keyword") String keyword);

    @Select("select * from customer where openid = #{openid}")
    Customer findByOpenid(@Param("openid") String openid);

    @Select("select * from customer where name = #{username} and role = 1 and station_id is not null limit 1")
    Customer findManagerByUsername(@Param("username") String username);

    @Insert("insert into customer(name, phone, openid, customer_type, station_id, role, create_time, update_time) " +
            "values(#{name}, #{phone}, #{openid}, #{customerType}, #{stationId}, #{role}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertWithOpenid(Customer customer);

    @Update("update customer set openid = #{openid}, update_time = now() where id = #{id}")
    void updateOpenid(@Param("id") Integer id, @Param("openid") String openid);

    /**
     * 从订单表自动刷新客户统计字段：
     * first_order_time, total_orders, total_consumption, last_delivery_time, avg_cycle_days
     */
    @Update("UPDATE customer c SET " +
            "first_order_time = (SELECT MIN(o.create_time) FROM orders o WHERE o.customer_id = c.id AND o.status = 3), " +
            "total_orders = (SELECT COUNT(*) FROM orders o WHERE o.customer_id = c.id AND o.status = 3), " +
            "total_consumption = (SELECT IFNULL(SUM(p.amount), 0) FROM payment_record p INNER JOIN orders o ON p.order_id = o.id WHERE o.customer_id = c.id AND o.status = 3 AND p.status = 2), " +
            "last_delivery_time = (SELECT MAX(o.update_time) FROM orders o WHERE o.customer_id = c.id AND o.status = 3), " +
            "avg_cycle_days = (SELECT CASE WHEN COUNT(*) > 1 THEN ROUND(DATEDIFF(MAX(o.create_time), MIN(o.create_time)) / (COUNT(*) - 1)) ELSE NULL END FROM orders o WHERE o.customer_id = c.id AND o.status = 3), " +
            "update_time = NOW() " +
            "WHERE c.id = #{customerId}")
    void refreshStats(@Param("customerId") Integer customerId);

    // ========== 水厂运营平台统计查询 ==========

    @Select("select count(*) from customer where station_id is not null group by station_id")
    List<Map<String, Object>> countByStation();

    @Select("select count(*) from customer where station_id = #{stationId}")
    int countByStationId(@Param("stationId") Integer stationId);

    @Select("select count(*) from customer where station_id = #{stationId} and last_delivery_time >= date_sub(now(), interval 30 day)")
    int activeCountLast30Days(@Param("stationId") Integer stationId);

    @Select("select count(*) from customer where station_id = #{stationId} and (last_delivery_time is null or last_delivery_time < date_sub(now(), interval 60 day))")
    int churnedCount(@Param("stationId") Integer stationId);

    @Select("select station_id as stationId, count(*) as totalCount from customer where station_id is not null group by station_id")
    List<Map<String, Object>> totalCountByStation();

    @Select("select station_id as stationId, count(*) as activeCount from customer where station_id is not null and last_delivery_time >= date_sub(now(), interval 30 day) group by station_id")
    List<Map<String, Object>> activeCountByStation();

    /**
     * 解绑指定水站的所有客户（设置 station_id = NULL）
     */
    @Update("update customer set station_id = null, update_time = now() where station_id = #{stationId}")
    int unbindByStationId(@Param("stationId") Integer stationId);

}
