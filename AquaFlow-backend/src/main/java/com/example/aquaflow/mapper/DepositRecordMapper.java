package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.DepositRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DepositRecordMapper {

    @Insert("insert into deposit_record(customer_id, station_id, type, amount, related_order_id, note, operator_id, create_time) " +
            "values(#{customerId}, #{stationId}, #{type}, #{amount}, #{relatedOrderId}, #{note}, #{operatorId}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(DepositRecord depositRecord);

    @Select("select * from deposit_record where id = #{id}")
    DepositRecord getById(@Param("id") Long id);

    @Select("select * from deposit_record where customer_id = #{customerId} and station_id = #{stationId} order by create_time desc")
    List<DepositRecord> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}
