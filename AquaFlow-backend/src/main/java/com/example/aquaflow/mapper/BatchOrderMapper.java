package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Orders;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BatchOrderMapper {
    @Insert("insert into batch_order(batch_id, order_id) values(#{batchId}, #{orderId})")
    void insert(@Param("batchId") Integer batchId, @Param("orderId") Integer orderId);

    @Select("select order_id from batch_order where batch_id = #{batchId}")
    List<Integer> getOrderIdsByBatchId(Integer batchId);

    @Delete("delete from batch_order where batch_id = #{batchId}")
    void deleteByBatchId(Integer batchId);

    @Select("select batch_id from batch_order where order_id = #{orderId}")
    Integer getBatchIdByOrderId(Integer orderId);

    @Delete("delete from batch_order where order_id = #{orderId}")
    void deleteByOrderId(Integer orderId);

    @Select("select o.* from orders o " +
            "inner join batch_order bo on o.id = bo.order_id " +
            "where bo.batch_id = #{batchId}")
    List<Orders> getOrdersByBatchId(Integer batchId);
}
