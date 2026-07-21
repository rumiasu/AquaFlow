package com.example.aquaflow.controller;

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

    @GetMapping
    public Result<List<Station>> listAll() {
        return Result.success(stationService.listAll());
    }

    @GetMapping("/{id}")
    public Result<Station> getById(@PathVariable Integer id) {
        return Result.success(stationService.getById(id));
    }

    @PostMapping
    public Result save(@RequestBody Station station) {
        stationService.save(station);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody Station station) {
        station.setId(id);
        stationService.update(station);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        stationService.delete(id);
        return Result.success();
    }
}
