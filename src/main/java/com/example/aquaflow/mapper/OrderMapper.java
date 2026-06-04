package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Orders;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    @Insert("insert into orders(customer_id, address_id, water_type_id, quantity, source, status, create_time, update_time) " +
            "values(#{customerId}, #{addressId}, #{waterTypeId}, #{quantity}, #{source}, #{status}, #{createTime}, #{updateTime})")
    void save(Orders orders);


    List<Orders> list(@Param("status") Integer status,
                      @Param("tag") String tag,
                      @Param("createTimeStart") String createTimeStart,
                      @Param("createTimeEnd") String createTimeEnd);

    @Select("select * from orders where id = #{id}")
    Orders getById(Integer id);

    @Update("update orders set status = #{status}, update_time = now() where id = #{id}")
    void updateStatus(@Param("id") Integer id, @Param("status") Integer status);

}
