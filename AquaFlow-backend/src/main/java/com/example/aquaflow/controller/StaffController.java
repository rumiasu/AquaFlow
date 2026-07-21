package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.service.StaffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/staff")
@Slf4j
public class StaffController {

    @Autowired
    private StaffService staffService;

    @GetMapping
    public Result<List<Staff>> listAll(@RequestParam(required = false) Integer stationId) {
        if (stationId != null) {
            return Result.success(staffService.listByStationId(stationId));
        }
        return Result.success(staffService.listAll());
    }

    @GetMapping("/{id}")
    public Result<Staff> getById(@PathVariable Integer id) {
        return Result.success(staffService.getById(id));
    }

    @PostMapping
    public Result save(@RequestBody Staff staff) {
        staffService.save(staff);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody Staff staff) {
        staff.setId(id);
        staffService.update(staff);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        staffService.delete(id);
        return Result.success();
    }
}
