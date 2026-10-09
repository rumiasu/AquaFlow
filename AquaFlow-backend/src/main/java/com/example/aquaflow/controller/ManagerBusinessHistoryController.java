package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.ConfirmedRefusalService;
import com.example.aquaflow.service.BarrelService;
import com.example.aquaflow.util.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 历史增长不能移除办理责任；专属只读入口，不接收客户端站别或扩大写权限。 */
@RestController
@RequireRole("STATION_MANAGER")
@RequiredArgsConstructor
public class ManagerBusinessHistoryController {
    private final BarrelService barrels;
    private final ConfirmedRefusalService refusals;

    @GetMapping("/api/manager/business-waiting/returns")
    public Result<Map<String,Object>> returns(@RequestParam(defaultValue="ACTIVE") String scope,
            @RequestParam(required=false) Long beforeId) {
        return Result.success(barrels.listReturnApplications(AuthContext.requireStationId(),scope,beforeId));
    }

    @GetMapping("/api/manager/refusal-cases/page")
    public Result<Map<String,Object>> refusals(@RequestParam(defaultValue="ACTIVE") String scope,
            @RequestParam(required=false) Long beforeId,@RequestParam(required=false) Long orderId) {
        return Result.success(refusals.page(AuthContext.requireStationId(),scope,beforeId,orderId));
    }
}
