package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    @GetMapping
    public Result<List<Inventory>> list(@RequestParam(required = false) Integer stationId){
        return Result.success(inventoryService.list(stationId));
    }

    @PostMapping("/inbound")
    public Result inbound(@RequestParam Integer stationId, @RequestBody InventoryInboundDTO inventoryInboundDTO) {
        inventoryService.inbound(stationId, inventoryInboundDTO.getItems());
        return Result.success();
    }


}
