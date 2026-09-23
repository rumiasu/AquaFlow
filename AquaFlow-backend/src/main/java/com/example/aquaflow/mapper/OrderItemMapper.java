package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.OrderItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderItemMapper {

    @Insert("insert into order_item(order_id, product_id, product_name_snapshot, brand_snapshot, spec_snapshot, price, quantity, deposit, subtotal, create_time, deducted_qty) " +
            "values(#{orderId}, #{productId}, #{productNameSnapshot}, #{brandSnapshot}, #{specSnapshot}, #{price}, #{quantity}, #{deposit}, #{subtotal}, #{createTime}, #{deductedQty})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OrderItem orderItem);

    @Select("select * from order_item where order_id = #{orderId}")
    List<OrderItem> listByOrderId(@Param("orderId") Long orderId);

    @Delete("delete from order_item where order_id = #{orderId}")
    void deleteByOrderId(@Param("orderId") Long orderId);
}
