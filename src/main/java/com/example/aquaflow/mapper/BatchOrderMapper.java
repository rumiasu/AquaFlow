package com.example.aquaflow.mapper;

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
}
