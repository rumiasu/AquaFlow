package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.ExceptionCloseoutDTO;
import jakarta.validation.Valid;
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
    /** 员工端：当事站站长查看原判断的处理历史；顾客端不能调用。 */
    @GetMapping("/{orderId}/history") public Result<List<Map<String,Object>>> history(@PathVariable Long orderId) {
        return Result.success(service.history(orderId,AuthContext.requireStationId()));
    }
    /** 员工端：原债权站撤销自身误判；金额/收款状态不变。 */
    @PostMapping("/{orderId}/revoke") public Result<Map<String,Object>> revoke(@PathVariable Long orderId,@Valid @RequestBody ExceptionCloseoutDTO dto) {
        return Result.success(service.resolve(orderId,AuthContext.requireStationId(),"REVOKE",dto));
    }
    /** 员工端：资产站仅解除该案已确认的本站资产冻结。 */
    @PostMapping("/{orderId}/release-freeze") public Result<Map<String,Object>> release(@PathVariable Long orderId,@Valid @RequestBody ExceptionCloseoutDTO dto) {
        return Result.success(service.resolve(orderId,AuthContext.requireStationId(),"RELEASE",dto));
    }
}
