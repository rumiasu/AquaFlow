package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.StaffUpdateDTO;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.service.StaffService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.StaffProfileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 员工管理（仅站长）。
 * <p>
 * 权限说明：类上的 {@code @RequireRole("STATION_MANAGER")} 只校验了"你是不是站长"（垂直权限），
 * 校验不了"这条数据是不是你水站的"（水平权限）。此前所有方法都缺后者，
 * 导致改一个 id 就能改/删其他水站的员工。现在所有方法统一走 {@link #requireOwn(Staff)}。
 * </p>
 */
@RestController
@RequestMapping("/api/staff")
@RequireRole("STATION_MANAGER")
public class StaffController {

    @Autowired
    private StaffService staffService;

    @Autowired
    private StaffMapper staffMapper;

    /** 校验员工属于当前登录站长所在水站；通过返回 null，否则返回错误 Result */
    private Result<Void> requireOwn(Staff staff) {
        if (staff == null) {
            return Result.error("员工不存在");
        }
        Long myStationId = AuthContext.requireStationId();
        if (staff.getStationId() == null || !myStationId.equals(staff.getStationId())) {
            return Result.error("无权操作他站员工");
        }
        return null;
    }

    /**
     * 员工列表：只返回本站员工，忽略并拒绝任何他站 stationId 入参。
     * 此前实现是「传了 stationId 就按传入值查，没传就查全表」——改个 URL 参数即可列出全平台员工。
     */
    @GetMapping
    public Result<List<Staff>> listAll(@RequestParam(required = false) Long stationId) {
        Long myStationId = AuthContext.requireStationId();
        if (stationId != null && !stationId.equals(myStationId)) {
            return Result.error("无权查看他站员工");
        }
        return Result.success(staffService.listByStationId(myStationId));
    }

    @GetMapping("/{id}")
    public Result<Staff> getById(@PathVariable Long id) {
        Staff staff = staffService.getById(id);
        Result<Void> check = requireOwn(staff);
        if (check != null) return Result.error(check.getMessage());
        return Result.success(staff);
    }

    /**
     * 新建员工：强制归属当前水站，且不允许通过此接口创建站长（防止自行提权）。
     */
    @PostMapping
    public Result save(@RequestBody Staff staff) {
        Long myStationId = AuthContext.requireStationId();
        if ("STATION_MANAGER".equals(staff.getRole())) {
            return Result.error("不可通过此接口创建站长账号");
        }
        staff.setStationId(myStationId);
        staffService.save(staff);
        return Result.success();
    }

    /**
     * 更新员工：只接受白名单字段（姓名/手机号/在职状态），role 与 stationId 一律不采信。
     */
    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody StaffUpdateDTO dto) {
        Staff exist = staffService.getById(id);
        Result<Void> check = requireOwn(exist);
        if (check != null) return Result.error(check.getMessage());

        String name = dto.getName() != null ? dto.getName() : exist.getName();
        String phone = dto.getPhone() != null ? dto.getPhone() : exist.getPhone();
        Integer status = dto.getStatus() != null ? dto.getStatus() : exist.getStatus();
        staffMapper.updateBaseInfo(id, name, phone, status);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        Staff staff = staffService.getById(id);
        Result<Void> check = requireOwn(staff);
        if (check != null) return Result.error(check.getMessage());
        staffService.delete(id);
        return Result.success();
    }

    /**
     * 员工画像（站长视角）：聚合配送业绩与服务质量。
     * GET /api/staff/{id}/profile
     */
    @GetMapping("/{id}/profile")
    public Result<StaffProfileVO> getProfile(@PathVariable Long id) {
        Staff staff = staffService.getById(id);
        Result<Void> check = requireOwn(staff);
        if (check != null) return Result.error(check.getMessage());

        StaffProfileVO vo = staffService.getStaffProfile(id, AuthContext.requireStationId());
        if (vo == null) {
            return Result.error("员工不存在");
        }
        return Result.success(vo);
    }
}
