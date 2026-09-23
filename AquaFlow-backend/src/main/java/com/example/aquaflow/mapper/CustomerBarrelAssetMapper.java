package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerBarrelAsset;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CustomerBarrelAssetMapper {

    @Insert("insert into customer_barrel_asset(customer_id, product_id, station_id, quantity, right_amount, update_time) " +
            "values(#{customerId}, #{productId}, #{stationId}, #{quantity}, coalesce(#{rightAmount},0), #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CustomerBarrelAsset asset);

    @Select("select * from customer_barrel_asset where id = #{id}")
    CustomerBarrelAsset getById(@Param("id") Long id);

    @Select("select * from customer_barrel_asset where customer_id = #{customerId} and station_id = #{stationId}")
    List<CustomerBarrelAsset> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select * from customer_barrel_asset where customer_id = #{customerId} and product_id = #{productId} and station_id = #{stationId}")
    CustomerBarrelAsset getByCustomerAndProduct(@Param("customerId") Long customerId, @Param("productId") Long productId, @Param("stationId") Long stationId);

    @Update("update customer_barrel_asset set quantity = quantity + #{quantity}, update_time = now() where id = #{id}")
    void increaseQuantity(@Param("id") Long id, @Param("quantity") Integer quantity);

    @Update("update customer_barrel_asset set quantity = quantity - #{quantity}, update_time = now() where id = #{id} and quantity >= #{quantity}")
    int decreaseQuantity(@Param("id") Long id, @Param("quantity") Integer quantity);

    @Update("update customer_barrel_asset set quantity = #{quantity}, update_time = now() where id = #{id}")
    void setQuantity(@Param("id") Long id, @Param("quantity") Integer quantity);

    /** 写路径专用：行锁，防并发下单重复计算权益缺口 */
    @Select("select * from customer_barrel_asset " +
            "where customer_id = #{customerId} and product_id = #{productId} and station_id = #{stationId} for update")
    CustomerBarrelAsset getByCustomerAndProductForUpdate(@Param("customerId") Long customerId,
                                                         @Param("productId") Long productId,
                                                         @Param("stationId") Long stationId);

    @Update("update customer_barrel_asset set right_amount = coalesce(right_amount,0) + #{amt}, update_time = now() " +
            "where id = #{id}")
    void addRightAmount(@Param("id") Long id, @Param("amt") java.math.BigDecimal amt);

    /** 扣减可退金额，余额不足则 affected=0（调用方必须校验并回滚） */
    @Update("update customer_barrel_asset set right_amount = right_amount - #{amt}, update_time = now() " +
            "where id = #{id} and right_amount >= #{amt}")
    int subRightAmount(@Param("id") Long id, @Param("amt") java.math.BigDecimal amt);

    @Update("update customer_barrel_asset set right_amount = #{amt}, update_time = now() where id = #{id}")
    void setRightAmount(@Param("id") Long id, @Param("amt") java.math.BigDecimal amt);
}
