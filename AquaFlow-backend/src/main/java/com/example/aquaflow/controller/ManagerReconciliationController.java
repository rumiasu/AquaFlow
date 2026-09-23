package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.ReconciliationService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对账（日结）站长端接口。
 *
 * <p>此前对账结果只写日志：不平也没人看得到，站长更无法判断「这次不平是不是本站刚做的补录造成的」。
 * 本接口让站长按自己的水站做即时校验。</p>
 *
 * <p><b>范围限制（重要）</b>：本接口只返回<b>登录站长所属水站</b>的校验结果，
 * 不含其它水站与全平台的任何数据（含 sample_ids）。全平台结果由 03:00 的定时任务落
 * {@code reconciliation_result} 表，仅作运维/事后追查，<b>不经本接口暴露</b>。</p>
 *
 * <p>权限：类级 {@code @RequireRole("STATION_MANAGER")}；站别取自 {@code AuthContext}，不信任请求参数。</p>
 */
@RestController
@RequestMapping("/api/manager/reconciliation")
@RequireRole("STATION_MANAGER")
public class ManagerReconciliationController {

    @Autowired
    private ReconciliationService reconciliationService;

    /**
     * 本站对账结果（即时计算，只读）。
     *
     * <p>[2026-09-13] 原有的 {@code POST /run} 已删除：它的语义是「触发对账并落表」，
     * 但那会写入全平台结果（站长可读即跨租户泄露）。改为站点范围后两者行为完全一致，
     * 且前端无任何调用方，留着只是语义含糊的死接口。需要刷新运维记录时由 03:00 定时任务承担。</p>
     */
    @GetMapping
    public Result<Map<String, Object>> check() {
        return Result.success(reconciliationService.stationCheck(AuthContext.requireStationId()));
    }
}
