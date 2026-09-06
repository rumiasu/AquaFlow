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

    @Update("update customer_owed_barrel set owed_qty = owed_qty + #{delta}, update_time = NOW() " +
            "where customer_id = #{customerId} and station_id = #{stationId}")
    int adjustOwed(@Param("customerId") Long customerId, @Param("stationId") Long stationId, @Param("delta") int delta);
}
