package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.impl.StationSetupGuideService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站长「信息完善引导」（2026-09-19）：一个端点把"本站还差什么没填、为什么必须填、去哪填、建议值多少"
 * 一次性给出，供站长端任意页面渲染「配置完善度」卡片。
 *
 * <p>产品要求：「所有需要/建议填写的字段，都加一层引导」。规则目录的唯一实现在
 * {@link StationSetupGuideService} —— 页面只渲染后端给的中文，**前端不得自建 key→文案映射**
 * （本仓明令，见 AGENTS §6）。</p>
 *
 * <p>站点取自 {@code AuthContext}（不信任请求参数）：否则站长能看别站的完善度，
 * 而完善度里带电话/地址这类信息。</p>
 */
@RestController
@RequestMapping("/api/manager/setup-guide")
@RequireRole({"STATION_MANAGER"})
public class ManagerSetupGuideController {

    @Autowired
    private StationSetupGuideService stationSetupGuideService;

    /** 本站配置完善度清单（含汇总文案）。 */
    @GetMapping
    public Result<Map<String, Object>> guide() {
        return Result.success(stationSetupGuideService.guide(AuthContext.requireStationId()));
    }
}
