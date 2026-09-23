package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 站长给自己的水站设置坐标（地图选点）的请求体。
 *
 * <p>对接 {@code PUT /api/stations/mine/coordinates}，2026-09-17 新增（v34）。</p>
 *
 * <p><b>没有 stationId 字段</b>：站点一律取自 {@code AuthContext}（登录态里服务端刷新的
 * stationId），禁止信任请求体 —— 否则站长可以改别人站的坐标，进而影响别人的配送范围判定。
 * 这与本仓 {@code /api/tickets/add}、{@code /api/tickets/consume} 的处理一致。</p>
 */
@Data
public class StationCoordinateDTO {

    /**
     * 纬度。允许 {@code null}（= 清除坐标）。
     *
     * <p>清除后配送范围校验会<b>跳过并放行</b>，不会拒单 —— 「没有坐标」与「超出范围」
     * 是两件事，混为一谈会让站长一清空坐标就把所有客户挡在门外。</p>
     */
    private BigDecimal lat;

    /** 经度。语义同 {@link #lat} */
    private BigDecimal lng;
}
