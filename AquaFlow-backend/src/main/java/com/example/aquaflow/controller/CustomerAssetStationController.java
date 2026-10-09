package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.CustomerAssetStationService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.CustomerAssetStationVO;
import com.example.aquaflow.vo.CustomerAssetStationsVO;
import org.springframework.web.bind.annotation.*;

/** 顾客只读本人关联资产站；不接受 customerId，也不产生绑定或改资产归属。 */
@RestController
@RequestMapping("/api/customer-assets/stations")
public class CustomerAssetStationController {
    private final CustomerAssetStationService service;

    public CustomerAssetStationController(CustomerAssetStationService service) { this.service = service; }

    @GetMapping
    public Result<CustomerAssetStationsVO> list(
            @RequestParam(defaultValue = "0") Long afterStationId,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(service.list(AuthContext.requireCustomerId(), afterStationId, limit));
    }

    @GetMapping("/{stationId}")
    public Result<CustomerAssetStationVO> get(@PathVariable Long stationId) {
        return Result.success(service.get(AuthContext.requireCustomerId(), stationId));
    }
}

