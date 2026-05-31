package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Orders;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    @Insert("insert into orders (customer_id, address_id, water_type_id, quantity, source) values " +
            "(#{customerId},#{addressId},#{waterTypeId},#{quantity},#{source})")
    void save(Orders orders);

    List<Orders> list(@Param("status") Integer status,
                      @Param("tag") String tag,
                      @Param("createTimeStart") String createTimeStart,
                      @Param("createTimeEnd") String createTimeEnd);

    @Select("select * from orders where id = #{id}")
    Orders getById(Integer id);

    void updateStatus(Integer id, Map<String, Integer> params);
}
