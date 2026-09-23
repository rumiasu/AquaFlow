package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StationEnterpriseConfig;
import org.apache.ibatis.annotations.*;

/**
 * 站级企业身份提示阈值（v51）。
 *
 * <p>⚠️ {@link #getByStationId} 返回 {@code null} 表示「该站还没配过」= 用平台默认；
 * 返回一个两项都为 NULL 的对象表示「站长明确表示本站不提示」。
 * <b>这两种情况必须靠 null 判断区分</b>，不要用字段是否为空去猜。</p>
 */
@Mapper
public interface StationEnterpriseConfigMapper {

    @Select("select * from station_enterprise_config where station_id = #{stationId}")
    StationEnterpriseConfig getByStationId(@Param("stationId") Long stationId);

    /**
     * 保存站级阈值（有则更新、无则插入）。
     *
     * <p>用 {@code on duplicate key update} 而不是"先查再决定 insert/update"：后者在并发两个保存请求下
     * 会双双查不到、双双 insert，撞主键报错或留下两行。本仓对 upsert 有明文约定（AGENTS §8.2）。</p>
     *
     * <p>两项都传 NULL 是**合法且有语义**的（= 本站不提示），故这里刻意<b>不</b>做
     * {@code IFNULL(..., 0)} 兜底 —— 那会把"关掉"变成"按 0 桶提示"（每单都弹）。
     * 本表两列本身可空，与 station_delivery_config 那些 NOT NULL DEFAULT 0 的列不同。</p>
     *
     * @return 受影响行数（1=新增 / 2=更新 / 0=值完全相同未变）
     */
    @Insert("insert into station_enterprise_config(station_id, barrel_threshold, water_amount_threshold, "
            + "operator_id, create_time, update_time) "
            + "values(#{stationId}, #{barrelThreshold}, #{waterAmountThreshold}, #{operatorId}, NOW(), NOW()) "
            + "on duplicate key update barrel_threshold=values(barrel_threshold), "
            + "water_amount_threshold=values(water_amount_threshold), operator_id=values(operator_id), "
            + "update_time=NOW()")
    int upsert(StationEnterpriseConfig config);
}
