package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.WaterType;
import com.example.aquaflow.service.WaterTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/water-types")
@Slf4j
public class WaterTypeController {

    @Autowired
    private WaterTypeService waterTypeService;

    @PostMapping
    public Result save(@RequestBody WaterType waterType) {
        waterTypeService.save(waterType);
        return Result.success(waterType.getId());
    }

    @GetMapping
    public Result<List<WaterType>> list() {
        return Result.success(waterTypeService.list());
    }

    @GetMapping("/{id}")
    public Result<WaterType> getById(@PathVariable Integer id) {
        return Result.success(waterTypeService.getById(id));
    }
}
