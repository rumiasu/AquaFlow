package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.mapper.AlertLogMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站长端：本站运营告警（只读）。
 *
 * <p><b>归属与可见性</b>：{@code @RequireRole("STATION_MANAGER")}，水站取自
 * {@link AuthContext#requireStationId()}（不信任请求参数）。只返回
 * {@code alert_type='OPERATION'} 的记录 —— <b>系统故障告警是发给系统管理员的
 * （见 {@code constant/AlertType.java}），绝不能从这里漏出去</b>：
 * 它带平台级细节（对账不平的表与金额），站长既无权知情也修不了。</p>
 *
 * <p>系统告警没有 HTTP 入口，运维直接查表：</p>
 * <pre>select * from alert_log where alert_type='SYSTEM' order by id desc limit 50;</pre>
 */
@RestController
@RequestMapping("/api/manager/alerts")
@RequireRole("STATION_MANAGER")
public class ManagerAlertController {

    @Autowired
    private AlertLogMapper alertLogMapper;

    /** 本站运营告警（最近 N 条，默认 50、上限 200） */
    @GetMapping
    public Result<?> list(@RequestParam(required = false) Integer limit) {
        Long stationId = AuthContext.requireStationId();
        int n = (limit == null || limit <= 0) ? 50 : Math.min(limit, 200);
        return Result.success(alertLogMapper.listStationAlerts(stationId, n));
    }
}
