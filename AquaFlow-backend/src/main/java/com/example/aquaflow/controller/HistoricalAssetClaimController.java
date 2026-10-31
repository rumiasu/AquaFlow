package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.HistoricalAssetClaimDTO;
import com.example.aquaflow.entity.HistoricalAssetClaim;
import com.example.aquaflow.service.HistoricalAssetClaimService;
import com.example.aquaflow.service.HistoricalClaimPhotoService;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api")
public class HistoricalAssetClaimController {
    private final HistoricalAssetClaimService service;
    private final HistoricalClaimPhotoService photos;
    public HistoricalAssetClaimController(HistoricalAssetClaimService service, HistoricalClaimPhotoService photos) { this.service=service; this.photos=photos; }
    @PostMapping("/asset-claims/photo")
    public Result<HistoricalClaimPhotoService.Photo> uploadPhoto(@RequestParam Long stationId, @RequestParam String clientToken, @RequestParam("file") MultipartFile file) {
        return Result.success(photos.upload(stationId,clientToken,file));
    }
    @GetMapping("/asset-claims/photo")
    public Result<HistoricalClaimPhotoService.Photo> myPhoto(@RequestParam Long stationId, @RequestParam String clientToken) {
        return Result.success(photos.mine(stationId,clientToken));
    }
    @RequireRole("STATION_MANAGER")
    @GetMapping("/manager/asset-claims/{id}/photo")
    public Result<HistoricalClaimPhotoService.Photo> claimPhoto(@PathVariable Long id) { return Result.success(photos.manager(id)); }
    @PostMapping("/asset-claims")
    public Result<HistoricalAssetClaim> submit(@RequestBody @Valid HistoricalAssetClaimDTO request) {
        return Result.success(service.submit(request));
    }
    @GetMapping("/asset-claims/{id}")
    public Result<HistoricalAssetClaim> detail(@PathVariable Long id) { return Result.success(service.mine(id)); }
    @GetMapping("/asset-claims/products")
    public Result<List<HistoricalAssetClaimDTO.ProductOption>> products(@RequestParam Long stationId) {
        return Result.success(service.catalog(stationId));
    }
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
