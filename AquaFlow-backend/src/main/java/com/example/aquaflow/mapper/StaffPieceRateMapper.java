package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StaffPieceRate;
import org.apache.ibatis.annotations.*;

/**
 * 站级配送计件单价（v37）。
 *
 * <p>⚠️ {@link #getByStationAndProduct} 返回 {@code null} 表示"该站/该商品没配过"，
 * 调用方必须用 {@link StaffPieceRate#defaults} 兜底 —— 本表是后加的，存量水站全都没有配置行。</p>
 */
@Mapper
public interface StaffPieceRateMapper {

    @Select("select * from staff_piece_rate where station_id = #{stationId} and product_id = #{productId}")
    StaffPieceRate getByStationAndProduct(@Param("stationId") Long stationId,
                                          @Param("productId") Long productId);

    @Select("select * from staff_piece_rate where station_id = #{stationId} order by product_id asc")
    java.util.List<StaffPieceRate> listByStation(@Param("stationId") Long stationId);

    /**
     * 保存计件单价（有则更新、无则插新）。
     *
     * <p>⚠️ 所有金额列用 {@code IFNULL(..., 0.00)} 兜住，<b>不要改回裸 {@code #{perBucketAmount}}</b>：
     * 它们是 {@code NOT NULL DEFAULT 0.00}，而 SQL 里的 {@code DEFAULT} <b>只在「该列没出现在
     * INSERT 里」时才生效</b> —— 显式列出却传 NULL 会直接报「Column 'per_bucket_amount' cannot be null」。
     * 这个坑在 v34/v35 上已经踩过三次（payment_record / orders / station_delivery_config）。</p>
     */
    @Insert("insert into staff_piece_rate(station_id, product_id, per_bucket_amount, "
            + "floor_bonus_per_level, floor_free_level, create_time, update_time) "
            + "values(#{stationId}, #{productId}, IFNULL(#{perBucketAmount},0.00), "
            + "IFNULL(#{floorBonusPerLevel},0.00), IFNULL(#{floorFreeLevel},1), NOW(), NOW()) "
            + "on duplicate key update per_bucket_amount=values(per_bucket_amount), "
            + "floor_bonus_per_level=values(floor_bonus_per_level), "
            + "floor_free_level=values(floor_free_level), update_time=NOW()")
    int upsert(StaffPieceRate rate);

    @Delete("delete from staff_piece_rate where station_id = #{stationId} and product_id = #{productId}")
    int delete(@Param("stationId") Long stationId, @Param("productId") Long productId);
}
