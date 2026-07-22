package com.example.aquaflow.controller.factory;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.factory.StationProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/factory-ops/profile")
public class StationProfileController {

    @Autowired
    private StationProfileService profileService;

    @GetMapping("/{stationId}")
    public Result<Map<String, Object>> profile(@PathVariable Integer stationId) {
        return Result.success(profileService.profile(stationId));
    }

    @GetMapping("/{stationId}/sales-trend")
    public Result<List<Map<String, Object>>> salesTrend(
            @PathVariable Integer stationId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(profileService.salesTrend(stationId, period));
    }

    @GetMapping("/{stationId}/customer-stats")
    public Result<Map<String, Object>> customerStats(@PathVariable Integer stationId) {
        return Result.success(profileService.customerStats(stationId));
    }

    @GetMapping("/{stationId}/inventory-turnover")
    public Result<List<Map<String, Object>>> inventoryTurnover(@PathVariable Integer stationId) {
        return Result.success(profileService.inventoryTurnover(stationId));
    }

    @GetMapping("/{stationId}/payment-speed")
    public Result<Map<String, Object>> paymentSpeed(@PathVariable Integer stationId) {
        return Result.success(profileService.paymentSpeed(stationId));
    }
}
