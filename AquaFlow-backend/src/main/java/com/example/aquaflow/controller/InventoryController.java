package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<Inventory>> list(@RequestParam(required = false) Long stationId){
        stationId = AuthContext.requireStationId();
        return Result.success(inventoryService.list(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/inbound")
    public Result inbound(@RequestParam Long stationId, @RequestBody InventoryInboundDTO inventoryInboundDTO) {
        if (!AuthContext.requireStationId().equals(stationId)) {
            return Result.error("只能向本站入库");
        }
        inventoryService.inbound(stationId, inventoryInboundDTO.getItems());
        return Result.success();
    }
}
