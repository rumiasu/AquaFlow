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

    /**
     * 站长在客户画像里一次性写全货到付款的三项配置（v48）。
     *
     * <p>{@code singleLimit = null} 表示**不限**（"特殊允许的客户可以大额"就是这么表达的）——
     * 注意 SQL 里必须写成 {@code #{singleLimit}}，**不能**加"非空才更新"的判断：那样"把上限改回不限"
     * 就变成了改不动（前端看起来像保存成功）。</p>
     */
    @Update("update customer_station_config set offline_payment_enabled = #{enabled}, "
            + "offline_payment_single_limit = #{singleLimit}, "
            + "offline_payment_allow_first_order = #{allowFirstOrder}, update_time = NOW() "
            + "where customer_id = #{customerId} and station_id = #{stationId}")
    int updateOfflinePaymentConfig(@Param("customerId") Long customerId, @Param("stationId") Long stationId,
                                   @Param("enabled") Integer enabled,
                                   @Param("singleLimit") java.math.BigDecimal singleLimit,
                                   @Param("allowFirstOrder") Integer allowFirstOrder);

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