package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerBarrelOwed;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CustomerBarrelOwedMapper {

    @Select("select * from customer_owed_barrel where customer_id = #{customerId} and station_id = #{stationId}")
    CustomerBarrelOwed get(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Insert("insert into customer_owed_barrel(customer_id, station_id, owed_qty, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{owedQty}, #{createTime}, #{updateTime}) " +
            "on duplicate key update owed_qty = owed_qty + #{owedQty}, update_time = #{updateTime}")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void upsert(CustomerBarrelOwed record);

    // AQ-017: 改为 upsert —— 该行不存在时自动插入（首欠桶不再静默丢失），存在时累加；返回受影响行数
    @Insert("insert into customer_owed_barrel(customer_id, station_id, owed_qty, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{delta}, NOW(), NOW()) " +
            "on duplicate key update owed_qty = owed_qty + #{delta}, update_time = NOW()")
    int adjustOwed(@Param("customerId") Long customerId, @Param("stationId") Long stationId, @Param("delta") int delta);
}
