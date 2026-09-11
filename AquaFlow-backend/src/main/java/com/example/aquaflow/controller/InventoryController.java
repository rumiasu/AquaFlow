package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.InventoryRecord;
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

    /**
     * [AQ-029] 查询本站库存流水（进出明细），供站长核对库存变动。
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/records")
    public Result<List<InventoryRecord>> records(@RequestParam(required = false) Integer limit) {
        Long stationId = AuthContext.requireStationId();
        int n = (limit == null || limit <= 0) ? 200 : Math.min(limit, 1000);
        return Result.success(inventoryService.listRecords(stationId, n));
    }
}
