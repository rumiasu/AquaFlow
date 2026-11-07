package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.DeliveryProblemReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/delivery")
public class DeliveryProblemReadController {
    private final DeliveryProblemReadService service;
    public DeliveryProblemReadController(DeliveryProblemReadService service) { this.service = service; }
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/my-problem-receipts")
    public Result<List<DeliveryProblemReadService.Receipt>> listMine() { return Result.success(service.listMine()); }
}
