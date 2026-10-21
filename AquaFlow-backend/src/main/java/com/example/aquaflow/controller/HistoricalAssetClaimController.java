package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.HistoricalAssetClaimDTO;
import com.example.aquaflow.entity.HistoricalAssetClaim;
import com.example.aquaflow.service.HistoricalAssetClaimService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api")
public class HistoricalAssetClaimController {
    private final HistoricalAssetClaimService service;
    public HistoricalAssetClaimController(HistoricalAssetClaimService service) { this.service=service; }
    @PostMapping("/asset-claims")
    public Result<HistoricalAssetClaim> submit(@RequestBody @Valid HistoricalAssetClaimDTO request) {
        return Result.success(service.submit(request));
    }
    @GetMapping("/asset-claims/{id}")
    public Result<HistoricalAssetClaim> detail(@PathVariable Long id) { return Result.success(service.mine(id)); }
    @GetMapping("/asset-claims")
    public Result<List<HistoricalAssetClaim>> mine(@RequestParam Long stationId) { return Result.success(service.listMine(stationId)); }
    @RequireRole("STATION_MANAGER")
    @GetMapping("/manager/asset-claims")
    public Result<List<HistoricalAssetClaim>> pending() { return Result.success(service.pending()); }
    @RequireRole("STATION_MANAGER")
    @GetMapping("/manager/asset-claims/{id}")
    public Result<HistoricalAssetClaim> managerDetail(@PathVariable Long id) { return Result.success(service.managerDetail(id)); }
    @RequireRole("STATION_MANAGER")
    @PostMapping("/manager/asset-claims/{id}/review")
    public Result<HistoricalAssetClaim> review(@PathVariable Long id,@RequestBody @Valid HistoricalAssetClaimDTO.Review request) {
        return Result.success(service.review(id,request));
    }
}
