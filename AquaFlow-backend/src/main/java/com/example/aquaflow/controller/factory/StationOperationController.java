package com.example.aquaflow.controller.factory;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.factory.StationOperationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/factory-ops")
public class StationOperationController {

    @Autowired
    private StationOperationService operationService;

    @RequireRole("FACTORY_ADMIN")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success(operationService.overview());
    }

    @RequireRole("FACTORY_ADMIN")
    @GetMapping("/stations/ranking")
    public Result<List<Map<String, Object>>> stationRanking(
            @RequestParam(defaultValue = "quantity") String sortBy) {
        return Result.success(operationService.stationRanking(sortBy));
    }

    @RequireRole("FACTORY_ADMIN")
    @GetMapping("/stations/trend")
    public Result<List<Map<String, Object>>> stationTrend() {
        return Result.success(operationService.stationTrend());
    }

    @RequireRole("FACTORY_ADMIN")
    @GetMapping("/inventory-overview")
    public Result<List<Map<String, Object>>> inventoryOverview() {
        return Result.success(operationService.inventoryOverview());
    }

    @RequireRole("FACTORY_ADMIN")
    @GetMapping("/stations/{stationId}/detail")
    public Result<Map<String, Object>> stationDetail(@PathVariable Integer stationId) {
        return Result.success(operationService.stationDetail(stationId));
    }
}
