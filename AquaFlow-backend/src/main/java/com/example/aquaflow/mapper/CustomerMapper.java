package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Customer;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CustomerMapper {

    @Insert("insert into customer(openid, name, phone, customer_type, note, first_order_time, last_delivery_time, create_time, update_time) " +
            "values(#{openid}, #{name}, #{phone}, #{customerType}, #{note}, #{firstOrderTime}, #{lastDeliveryTime}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Customer customer);

    @Select("select * from customer where id = #{id}")
    Customer getById(@Param("id") Long id);

    @Update("update customer set openid=#{openid}, name=#{name}, phone=#{phone}, customer_type=#{customerType}, " +
            "note=#{note}, first_order_time=#{firstOrderTime}, " +
            "last_delivery_time=#{lastDeliveryTime}, update_time=NOW() where id=#{id}")
    void update(Customer customer);

    @Delete("delete from customer where id = #{id}")
    void delete(@Param("id") Long id);

    @Select("select * from customer")
    List<Customer> list();

    @Select("select * from customer where openid = #{openid}")
    Customer findByOpenid(@Param("openid") String openid);

    @Select("select * from customer where phone = #{phone} limit 1")
    Customer findByPhone(@Param("phone") String phone);

    @Select("select * from customer where name like concat('%', #{keyword}, '%') or phone like concat('%', #{keyword}, '%')")
    List<Customer> search(@Param("keyword") String keyword);

    @Select("select distinct c.* from customer c " +
            "join orders o on c.id = o.customer_id " +
            "where o.station_id = #{stationId}")
    List<Customer> listByStationId(@Param("stationId") Long stationId);

    @Select("select distinct c.* from customer c " +
            "join orders o on c.id = o.customer_id " +
            "where o.station_id = #{stationId} " +
            "and (c.name like concat('%', #{keyword}, '%') or c.phone like concat('%', #{keyword}, '%'))")
    List<Customer> searchByStation(@Param("stationId") Long stationId, @Param("keyword") String keyword);

    @Select("select count(*) from customer")
    int countAll();

    @Select("select count(distinct c.id) from customer c " +
            "join orders o on c.id = o.customer_id " +
            "where o.station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);
}