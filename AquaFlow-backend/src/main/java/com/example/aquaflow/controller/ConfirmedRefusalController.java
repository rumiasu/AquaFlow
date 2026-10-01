package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.ConfirmedRefusalService;
import com.example.aquaflow.util.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 员工端风险台账调用；只有当前站站长可读当事站证据、确认本站资产冻结。 */
@RestController
@RequestMapping("/api/manager/refusal-cases")
@RequireRole("STATION_MANAGER")
@RequiredArgsConstructor
public class ConfirmedRefusalController {
    private final ConfirmedRefusalService service;
    @GetMapping public Result<List<Map<String,Object>>> list() { return Result.success(service.list(AuthContext.requireStationId())); }
    @PutMapping("/{orderId}/confirm-freeze") public Result<Void> confirm(@PathVariable Long orderId) {
        service.confirmFreeze(orderId,AuthContext.requireStationId()); return Result.success();
    }
}
