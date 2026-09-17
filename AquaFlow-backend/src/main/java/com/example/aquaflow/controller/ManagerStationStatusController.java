package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.annotation.RequireStation;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.StationOperatingStatus;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 水站<b>营业状态</b>（软状态）+ 站长留言（<b>站长专属</b>）。
 *
 * <p>产品口径（2026-09-17）：「正常运营、休息…」，<b>不阻断下单</b> ——
 * 顾客照常下单，只是会在商城/下单页看到横幅、下单响应里带 {@code warnings} 提示。
 * 需要"真的不接单"时用的是另一套东西：{@code station.status = 2 停业}（硬开关，下单直接被拒）。</p>
 *
 * <p>顾客侧读同一份数据走 <b>公开</b>端点 {@code GET /api/stations/&#123;id&#125;/status}；
 * 站点列表 {@code /api/stations/public} 也已带上这三个字段（实体自动带出）。</p>
 *
 * <p>水站一律由后端按登录态判定（{@link AuthContext#requireStationId()}），不信任请求参数；
 * 更新走 {@code updateOperatingStatus} 并校验受影响行数（整行覆盖的坑见 StationMapper 注释）。</p>
 */
@RestController
@RequestMapping("/api/manager/station-status")
@RequireRole("STATION_MANAGER")
@RequireStation
public class ManagerStationStatusController {

    /** 留言长度上限（与 station.status_note varchar(100) 对齐） */
    private static final int NOTE_MAX = 100;

    @Autowired
    private StationMapper stationMapper;

    /** 读本站营业状态（站长端设置页用）。 */
    @GetMapping
    public Result<Map<String, Object>> get() {
        Station station = stationMapper.getById(AuthContext.requireStationId());
        if (station == null) {
            return Result.error("水站不存在");
        }
        return Result.success(toView(station));
    }

    /**
     * 设置营业状态与留言。
     *
     * @param body {@code {"operatingStatus":2,"note":"今天休息，明早正常送"}}
     */
    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        Integer status = parseStatus(body.get("operatingStatus"));
        if (!StationOperatingStatus.isValid(status)) {
            return Result.error("营业状态取值非法（1 正常运营 / 2 休息中 / 3 配送延迟 / 4 暂停配送可预约）");
        }
        String note = body.get("note") == null ? null : String.valueOf(body.get("note")).trim();
        if (note != null && note.length() > NOTE_MAX) {
            return Result.error("留言不能超过 " + NOTE_MAX + " 字");
        }
        if (note != null && note.isEmpty()) {
            note = null;
        }
        Long stationId = AuthContext.requireStationId();
        int affected = stationMapper.updateOperatingStatus(stationId, status, note);
        if (affected == 0) {
            return Result.error("保存失败：水站不存在");
        }
        return Result.success(toView(stationMapper.getById(stationId)));
    }

    private static Integer parseStatus(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> toView(Station station) {
        Map<String, Object> data = new HashMap<>();
        data.put("stationId", station.getId());
        data.put("stationName", station.getName());
        data.put("operatingStatus", station.getOperatingStatus() == null
                ? StationOperatingStatus.NORMAL : station.getOperatingStatus());
        data.put("statusText", station.getOperatingStatusText());
        data.put("note", station.getStatusNote());
        data.put("statusUpdateTime", station.getStatusUpdateTime());
        // 给前端判断"要不要展示横幅"：正常运营时 customerHint 为 null
        data.put("customerHint", StationOperatingStatus.customerHint(
                station.getOperatingStatus(), station.getStatusNote()));
        return data;
    }
}
