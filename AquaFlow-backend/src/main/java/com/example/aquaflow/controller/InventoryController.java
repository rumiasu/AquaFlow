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
    public Result<List<Inventory>> list(){
        return Result.success(inventoryService.list());
    }

    @PostMapping("/inbound")
    public Result inbound(@RequestBody InventoryInboundDTO inventoryInboundDTO) {
        inventoryService.inbound(inventoryInboundDTO.getItems());
        return Result.success();
    }


}
