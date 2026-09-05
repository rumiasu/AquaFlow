package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerBarrelAsset;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CustomerBarrelAssetMapper {

    @Insert("insert into customer_barrel_asset(customer_id, product_id, station_id, quantity, update_time) " +
            "values(#{customerId}, #{productId}, #{stationId}, #{quantity}, #{updateTime})")
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
}
