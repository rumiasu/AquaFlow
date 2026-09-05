package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerBarrelInTransit;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CustomerBarrelInTransitMapper {

    @Insert("insert into customer_barrel_in_transit(customer_id, station_id, product_id, qty, related_order_id, status, created_at, updated_at) " +
            "values(#{customerId}, #{stationId}, #{productId}, #{qty}, #{relatedOrderId}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CustomerBarrelInTransit inTransit);

    @Select("select * from customer_barrel_in_transit where id = #{id}")
    CustomerBarrelInTransit getById(@Param("id") Long id);

    @Select("select * from customer_barrel_in_transit where customer_id = #{customerId} and station_id = #{stationId} order by created_at desc")
    List<CustomerBarrelInTransit> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select * from customer_barrel_in_transit where related_order_id = #{orderId}")
    List<CustomerBarrelInTransit> listByOrderId(@Param("orderId") Long orderId);

    @Select("select * from customer_barrel_in_transit where related_order_id = #{orderId} and status = 'PENDING'")
    List<CustomerBarrelInTransit> listPendingByOrderId(@Param("orderId") Long orderId);

    @Update("update customer_barrel_in_transit set status = #{status}, updated_at = NOW() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("update customer_barrel_in_transit set status = #{status}, updated_at = NOW() where related_order_id = #{orderId}")
    void updateStatusByOrderId(@Param("orderId") Long orderId, @Param("status") String status);

    @Delete("delete from customer_barrel_in_transit where related_order_id = #{orderId}")
    void deleteByOrderId(@Param("orderId") Long orderId);

    @Update("<script>" +
            "update customer_barrel_in_transit set related_order_id = #{orderId}, updated_at = NOW() " +
            "where id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " and status = 'PENDING' and related_order_id IS NULL" +
            "</script>")
    void linkPendingToOrder(@Param("orderId") Long orderId,
                            @Param("ids") List<Long> ids);
}