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

    @Select("select * from order_item where id = #{id}")
    OrderItem getById(@Param("id") Long id);

    /**
     * 回写"下单实际占用的库存量"（{@code deducted_qty}）。
     *
     * <p>口径（2026-09-25 库存预留模型起）：它是**当前那份活跃预留凭据预留了多少**的镜像 ——
     * 下单预留时写入、换站重建凭据时改写、取消释放后不再回补库存（实物本来就没动）。
     * 旧口径是"下单已扣减量、取消按它 increaseStock"，<b>已废</b>；见
     * {@code docs/design/28-库存预留与履约凭据.md}。</p>
     */
    @Update("update order_item set deducted_qty = #{deductedQty} where id = #{id}")
    void updateDeductedQty(@Param("id") Long id, @Param("deductedQty") Integer deductedQty);

    @Delete("delete from order_item where order_id = #{orderId}")
    void deleteByOrderId(@Param("orderId") Long orderId);
}
