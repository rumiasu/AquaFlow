package com.example.aquaflow.controller.factory;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.factory.StationAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/factory-ops/analysis")
public class StationAnalysisController {

    @Autowired
    private StationAnalysisService analysisService;

    @GetMapping("/sales-decline")
    public Result<List<Map<String, Object>>> salesDecline() {
        return Result.success(analysisService.salesDecline());
    }

    @GetMapping("/customer-churn")
    public Result<List<Map<String, Object>>> customerChurn() {
        return Result.success(analysisService.customerChurn());
    }

    @GetMapping("/inventory-pressure")
    public Result<List<Map<String, Object>>> inventoryPressure() {
        return Result.success(analysisService.inventoryPressure());
    }

    @GetMapping("/suggestions")
    public Result<List<Map<String, Object>>> suggestions() {
        return Result.success(analysisService.suggestions());
    }
}
