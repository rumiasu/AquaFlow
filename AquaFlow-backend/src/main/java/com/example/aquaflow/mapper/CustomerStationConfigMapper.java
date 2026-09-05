package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerStationConfig;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CustomerStationConfigMapper {

    @Insert("insert into customer_station_config(customer_id, station_id, offline_payment_enabled, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{offlinePaymentEnabled}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CustomerStationConfig config);

    @Select("select * from customer_station_config where customer_id = #{customerId} and station_id = #{stationId}")
    CustomerStationConfig getByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Update("update customer_station_config set offline_payment_enabled = #{offlinePaymentEnabled}, update_time = NOW() " +
            "where customer_id = #{customerId} and station_id = #{stationId}")
    void updateOfflinePaymentEnabled(@Param("customerId") Long customerId, @Param("stationId") Long stationId,
                                     @Param("offlinePaymentEnabled") Integer offlinePaymentEnabled);

    @Select("select * from customer_station_config where station_id = #{stationId}")
    List<CustomerStationConfig> listByStation(@Param("stationId") Long stationId);

    @Select("select * from customer_station_config where customer_id = #{customerId}")
    List<CustomerStationConfig> listByCustomer(@Param("customerId") Long customerId);

    @Delete("delete from customer_station_config where customer_id = #{customerId} and station_id = #{stationId}")
    void deleteByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 确保记录存在（不存在则插入默认关闭） */
    @Insert("insert into customer_station_config(customer_id, station_id, offline_payment_enabled, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, 0, NOW(), NOW()) " +
            "on duplicate key update update_time = NOW()")
    void ensureExists(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}