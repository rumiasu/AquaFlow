package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StationDeliveryConfig;
import org.apache.ibatis.annotations.*;

/**
 * 站级配送计费配置（v35）。
 *
 * <p>⚠️ {@link #getByStationId} 返回 {@code null} 表示"该站还没配过"，
 * 调用方必须用 {@link StationDeliveryConfig#defaults(Long)} 兜底而不是直接 NPE ——
 * 本表是后加的，存量水站全部没有配置行。</p>
 */
@Mapper
public interface StationDeliveryConfigMapper {

    @Select("select * from station_delivery_config where station_id = #{stationId}")
    StationDeliveryConfig getByStationId(@Param("stationId") Long stationId);

    /**
     * 保存站级配置（有则更新、无则插入）。
     *
     * <p>用 {@code on duplicate key update} 而不是"先查再决定 insert/update"：
     * 后者在并发两个保存请求下会双双查不到、双双 insert，撞主键报错或留下两行。
     * 本仓对 upsert 有明文约定（AGENTS §8.2：`SELECT FOR UPDATE` 对不存在的行不加锁）。</p>
     *
     * <p>⚠️ 四个金额列用 {@code IFNULL(..., 0.00)} 兜住，<b>不要改回裸 {@code #{minOrderFee}}</b>：
     * 它们是 {@code NOT NULL DEFAULT 0.00}，而 SQL 里的 {@code DEFAULT} <b>只在「该列没出现在
     * INSERT 里」时才生效</b> —— 显式列出却传 NULL 会直接报「Column 'min_order_fee' cannot be null」。
     * 站长只填"半径"、不填费用时就会踩到。同一个坑在 {@code payment_record}（v34）与
     * {@code orders}（{@code OrderMapper.xml}，v35）上各踩过一次 —— 这是第三次，别再改回去。</p>
     *
     * @return 受影响行数（1=新增，2=更新，0=值完全相同未变）
     */
    @Insert("insert into station_delivery_config(station_id, min_order_buckets, min_order_amount, min_order_mode, min_order_fee, "
            + "delivery_radius_m, over_radius_mode, remote_fee, base_delivery_fee, free_delivery_buckets, free_delivery_amount, "
            + "floor_free_level, floor_fee_per_level, floor_fee_mode, create_time, update_time) "
            + "values(#{stationId}, #{minOrderBuckets}, #{minOrderAmount}, #{minOrderMode}, IFNULL(#{minOrderFee}, 0.00), "
            + "#{deliveryRadiusM}, #{overRadiusMode}, IFNULL(#{remoteFee}, 0.00), IFNULL(#{baseDeliveryFee}, 0.00), #{freeDeliveryBuckets}, #{freeDeliveryAmount}, "
            + "#{floorFreeLevel}, IFNULL(#{floorFeePerLevel}, 0.00), #{floorFeeMode}, NOW(), NOW()) "
            + "on duplicate key update min_order_buckets=values(min_order_buckets), min_order_amount=values(min_order_amount), "
            + "min_order_mode=values(min_order_mode), min_order_fee=values(min_order_fee), "
            + "delivery_radius_m=values(delivery_radius_m), over_radius_mode=values(over_radius_mode), remote_fee=values(remote_fee), "
            + "base_delivery_fee=values(base_delivery_fee), free_delivery_buckets=values(free_delivery_buckets), "
            + "free_delivery_amount=values(free_delivery_amount), floor_free_level=values(floor_free_level), "
            + "floor_fee_per_level=values(floor_fee_per_level), floor_fee_mode=values(floor_fee_mode), update_time=NOW()")
    int upsert(StationDeliveryConfig config);
}
