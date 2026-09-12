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

    /**
     * [DEF-4] 确保 over 行存在并对其加<b>排他行锁</b>，用于把同一
     * (customer, station, product) 的并发桶账写入串行化。
     *
     * <p><b>为什么必须是 upsert 而不是 SELECT ... FOR UPDATE：</b>
     * {@code SELECT ... FOR UPDATE} 在行不存在时不产生任何锁（无间隙锁兜底），
     * 于是「首次还桶」这种行尚未建立的场景仍会两端并发通过校验。
     * 这里用 {@code INSERT ... ON DUPLICATE KEY UPDATE}：行不存在则插入 0 并持有排他锁，
     * 行存在则同样持排他锁，两种情况都能把并发事务挡在门外。</p>
     *
     * <p>调用方必须处于事务中（{@code @Transactional}），锁才会持续到事务提交。</p>
     */
    @Insert("insert into customer_barrel_over(customer_id, station_id, product_id, over_qty, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{productId}, 0, NOW(), NOW()) " +
            "on duplicate key update over_qty = over_qty")
    int lockOrCreate(@Param("customerId") Long customerId,
                     @Param("stationId") Long stationId,
                     @Param("productId") Long productId);
}
