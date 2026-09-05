package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.OrderImage;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderImageMapper {

    @Insert("insert into order_image(order_id, object_name, type, create_time) values(#{orderId}, #{objectName}, #{type}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OrderImage orderImage);

    @Select("select * from order_image where order_id = #{orderId} order by create_time desc")
    List<OrderImage> listByOrderId(@Param("orderId") Long orderId);

    @Delete("delete from order_image where id = #{id}")
    void deleteById(@Param("id") Long id);

    @Select("select * from order_image where id = #{id}")
    OrderImage getById(@Param("id") Long id);
}
