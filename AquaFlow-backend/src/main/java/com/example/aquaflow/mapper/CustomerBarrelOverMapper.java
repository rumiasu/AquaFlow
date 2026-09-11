package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerBarrelOver;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 客户过占桶（按商品，可负）数据访问。
 *
 * <p><b>注意：本表所有写入都不做「结果必须 &gt;= 0」的校验。</b>
 * over &lt; 0（顾客多还桶 / 水站暂存）是业务方确认的合法状态，
 * 加任何形式的非负校验都会把合理场景挡在门外。</p>
 */
@Mapper
public interface CustomerBarrelOverMapper {

    @Select("select * from customer_barrel_over " +
            "where customer_id = #{customerId} and station_id = #{stationId} and product_id = #{productId}")
    CustomerBarrelOver get(@Param("customerId") Long customerId,
                           @Param("stationId") Long stationId,
                           @Param("productId") Long productId);

    @Select("select * from customer_barrel_over " +
            "where customer_id = #{customerId} and station_id = #{stationId}")
    List<CustomerBarrelOver> listByCustomerAndStation(@Param("customerId") Long customerId,
                                                      @Param("stationId") Long stationId);

    @Select("select * from customer_barrel_over where station_id = #{stationId}")
    List<CustomerBarrelOver> listByStation(@Param("stationId") Long stationId);

    /**
     * 增减 over（可为负）。
     * 行不存在时按 delta 插入——注意 delta 本身可以是负数，直接插入负值即可。
     */
    @Insert("insert into customer_barrel_over(customer_id, station_id, product_id, over_qty, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{productId}, #{delta}, NOW(), NOW()) " +
            "on duplicate key update over_qty = over_qty + #{delta}, update_time = NOW()")
    int adjustOver(@Param("customerId") Long customerId,
                   @Param("stationId") Long stationId,
                   @Param("productId") Long productId,
                   @Param("delta") int delta);

    /**
     * 加锁读取（并发写入路径用）。
     * 注意：行不存在时 FOR UPDATE 不产生锁，调用方需自行处理（通常靠 lot 行锁或唯一键兜底）。
     */
    @Select("select * from customer_barrel_over " +
            "where customer_id = #{customerId} and station_id = #{stationId} and product_id = #{productId} " +
            "for update")
    CustomerBarrelOver getForUpdate(@Param("customerId") Long customerId,
                                    @Param("stationId") Long stationId,
                                    @Param("productId") Long productId);
}
