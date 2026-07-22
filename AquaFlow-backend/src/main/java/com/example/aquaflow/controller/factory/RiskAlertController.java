package com.example.aquaflow.controller.factory;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.RiskAlert;
import com.example.aquaflow.service.factory.RiskAlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/factory-ops/alerts")
public class RiskAlertController {

    @Autowired
    private RiskAlertService alertService;

    @GetMapping
    public Result<List<RiskAlert>> list(
            @RequestParam(required = false) Integer status) {
        return Result.success(alertService.list(status));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(alertService.stats());
    }

    @GetMapping("/recent")
    public Result<List<RiskAlert>> recentAlerts() {
        return Result.success(alertService.recentAlerts());
    }

    @PutMapping("/{id}/read")
    public Result markRead(@PathVariable Integer id) {
        alertService.markRead(id);
        return Result.success();
    }

    @PutMapping("/{id}/handle")
    public Result handle(@PathVariable Integer id, @RequestBody Map<String, String> body) {
        alertService.handle(id, body.get("handleNote"));
        return Result.success();
    }

    @PostMapping("/check")
    public Result check() {
        alertService.check();
        return Result.success();
    }
}
