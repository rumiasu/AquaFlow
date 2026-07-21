package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Factory;
import com.example.aquaflow.service.FactoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/factories")
@Slf4j
public class FactoryController {

    @Autowired
    private FactoryService factoryService;

    @GetMapping
    public Result<List<Factory>> listAll() {
        return Result.success(factoryService.listAll());
    }

    @GetMapping("/{id}")
    public Result<Factory> getById(@PathVariable Integer id) {
        return Result.success(factoryService.getById(id));
    }

    @PostMapping
    public Result save(@RequestBody Factory factory) {
        factoryService.save(factory);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody Factory factory) {
        factory.setId(id);
        factoryService.update(factory);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        factoryService.delete(id);
        return Result.success();
    }
}
