package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.service.StationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stations")
@Slf4j
public class StationController {

    @Autowired
    private StationService stationService;

    @RequireRole("FACTORY_ADMIN")
    @GetMapping
    public Result<List<Station>> listAll() {
        return Result.success(stationService.listAll());
    }

    @RequireRole("FACTORY_ADMIN")
    @GetMapping("/{id}")
    public Result<Station> getById(@PathVariable Integer id) {
        return Result.success(stationService.getById(id));
    }

    @RequireRole("FACTORY_ADMIN")
    @PostMapping
    public Result save(@RequestBody Station station) {
        stationService.save(station);
        return Result.success();
    }

    @RequireRole("FACTORY_ADMIN")
    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody Station station) {
        station.setId(id);
        stationService.update(station);
        return Result.success();
    }

    @RequireRole("FACTORY_ADMIN")
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        stationService.delete(id);
        return Result.success();
    }

    /**
     * 关闭水站：取消待配送订单，解绑客户，设置状态为停用
     */
    @RequireRole("FACTORY_ADMIN")
    @PutMapping("/{id}/close")
    public Result closeStation(@PathVariable Integer id) {
        stationService.closeStation(id);
        return Result.success();
    }
}
