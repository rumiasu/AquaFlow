package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.BarrelRightPurchaseDTO;
import com.example.aquaflow.entity.BarrelRightPurchase;
import com.example.aquaflow.service.IndependentBarrelService;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 顾客自助资产入口；身份一律来自登录态，员工收款沿用站级支付确认权限。 */
@RestController
@RequestMapping("/api/barrel-rights")
@RequiredArgsConstructor
public class BarrelRightPurchaseController {
    private final IndependentBarrelService service;
    @PutMapping("/{id}/withdraw") public Result<Void> withdraw(@PathVariable Long id) {
        service.withdraw(id,AuthContext.requireCustomerId()); return Result.success();
    }

    /** 顾客端查报价，不创建资金流水。 */
    @GetMapping("/quote")
    public Result<Map<String,Object>> quote(@RequestParam Long stationId,@RequestParam Long productId,
                                          @RequestParam(defaultValue="1") int quantity) {
        return Result.success(service.quote(AuthContext.requireCustomerId(),stationId,productId,quantity));
    }
    /** 顾客端提交独立押金意图；线下收款只允许归属站确认。 */
    @PostMapping("/purchase")
    public Result<Map<String,Object>> purchase(@Valid @RequestBody BarrelRightPurchaseDTO dto) {
        return Result.success(service.purchase(AuthContext.requireCustomerId(),dto));
    }
    /** 顾客端查询原购买结果，支付超时可恢复。 */
    @GetMapping
    public Result<List<BarrelRightPurchase>> list(@RequestParam Long stationId) {
        return Result.success(service.list(AuthContext.requireCustomerId(),stationId));
    }
}
