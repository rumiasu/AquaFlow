package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.DepositRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DepositRecordMapper {

    @Insert("insert into deposit_record(customer_id, station_id, type, amount, related_order_id, note, operator_id, create_time, product_id, unit_price, quantity, adjustment_id) " +
            "values(#{customerId}, #{stationId}, #{type}, #{amount}, #{relatedOrderId}, #{note}, #{operatorId}, #{createTime}, #{productId}, #{unitPrice}, #{quantity}, #{adjustmentId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(DepositRecord depositRecord);

    @Select("select * from deposit_record where id = #{id}")
    DepositRecord getById(@Param("id") Long id);

    @Select("select * from deposit_record where customer_id = #{customerId} and station_id = #{stationId} order by create_time desc")
    List<DepositRecord> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * [AQ-009] 统计某订单某类型的押金流水条数，用于"支付成功入账"的幂等判断。
     */
    @Select("select count(*) from deposit_record where related_order_id = #{orderId} and type = #{type}")
    int countByOrderAndType(@Param("orderId") Long orderId, @Param("type") Integer type);
}
