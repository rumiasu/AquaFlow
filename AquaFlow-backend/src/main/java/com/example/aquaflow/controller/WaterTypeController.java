package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.WaterType;
import com.example.aquaflow.service.WaterTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/water-types")
@Slf4j
public class WaterTypeController {

    @Autowired
    private WaterTypeService waterTypeService;

    @PostMapping
    public Result save(@RequestBody WaterType waterType) {
        waterTypeService.save(waterType);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody WaterType waterType) {
        waterType.setId(id);
        waterTypeService.update(waterType);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        waterTypeService.delete(id);
        return Result.success();
    }

    @GetMapping
    public Result<List<WaterType>> list(@RequestParam(required = false) String keyword) {
        if (keyword != null && !keyword.isEmpty()) {
            return Result.success(waterTypeService.listByKeyword(keyword));
        }
        return Result.success(waterTypeService.list());
    }

    @GetMapping("/{id}")
    public Result<WaterType> getById(@PathVariable Integer id) {
        return Result.success(waterTypeService.getById(id));
    }

    /**
     * 获取用户已购买过的水类型（用于首页展示已有的桶类型）
     * GET /api/water-types/my?customerId=xxx
     */
    @GetMapping("/my")
    public Result<List<Map<String, Object>>> listMyTypes(@RequestParam Integer customerId) {
        return Result.success(waterTypeService.listMyTypes(customerId));
    }
}
