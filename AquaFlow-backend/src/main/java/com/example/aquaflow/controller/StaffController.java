package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.service.StaffService;
import com.example.aquaflow.vo.StaffProfileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/staff")
@RequireRole("STATION_MANAGER")
public class StaffController {

    @Autowired
    private StaffService staffService;

    @GetMapping
    public Result<List<Staff>> listAll(@RequestParam(required = false) Long stationId) {
        if (stationId != null) {
            return Result.success(staffService.listByStationId(stationId));
        }
        return Result.success(staffService.listAll());
    }

    @GetMapping("/{id}")
    public Result<Staff> getById(@PathVariable Long id) {
        Staff staff = staffService.getById(id);
        if (staff == null) {
            return Result.error("员工不存在");
        }
        return Result.success(staff);
    }

    @PostMapping
    public Result save(@RequestBody Staff staff) {
        staffService.save(staff);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody Staff staff) {
        staff.setId(id);
        staffService.update(staff);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        staffService.delete(id);
        return Result.success();
    }

    /**
     * 员工画像（站长视角）：聚合配送业绩与服务质量。
     * GET /api/staff/{id}/profile
     */
    @GetMapping("/{id}/profile")
    public Result<StaffProfileVO> getProfile(@PathVariable Long id) {
        Long stationId = null;
        try {
            stationId = com.example.aquaflow.util.AuthContext.getStationId();
        } catch (Exception ignored) {
            // 拿不到水站不影响画像，仅水站名留空
        }
        StaffProfileVO vo = staffService.getStaffProfile(id, stationId);
        if (vo == null) {
            return Result.error("员工不存在");
        }
        return Result.success(vo);
    }
}
