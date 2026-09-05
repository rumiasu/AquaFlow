package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.StationService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/stations", "/api/station"})
public class StationController {

    @Autowired
    private StationService stationService;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<Station>> listAll() {
        return Result.success(stationService.listAll());
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}")
    public Result<Station> getById(@PathVariable Long id) {
        return Result.success(stationService.getById(id));
    }

    /**
     * 公开可选水站列表（客户选站用，无需登录）
     * 仅返回 status=1 营业中的站点
     */
    @GetMapping("/public")
    public Result<List<Station>> listPublic() {
        return Result.success(stationMapper.listPublic());
    }

    /**
     * 公开搜索水站（配送员申请绑定前用，无需登录）
     * keyword 匹配名称或地址
     */
    @GetMapping("/search")
    public Result<List<Station>> search(@RequestParam(required = false) String keyword) {
        List<Station> all = stationMapper.listAll();
        if (keyword == null || keyword.trim().isEmpty()) {
            all = all.stream().limit(50).collect(Collectors.toList());
            return Result.success(all);
        }
        String kw = keyword.trim();
        List<Station> filtered = all.stream()
                .filter(s -> {
                    if (s.getName() != null && s.getName().contains(kw)) return true;
                    if (s.getAddress() != null && s.getAddress().contains(kw)) return true;
                    if (s.getPhone() != null && s.getPhone().contains(kw)) return true;
                    return false;
                })
                .limit(50)
                .collect(Collectors.toList());
        return Result.success(filtered);
    }

    /**
     * 当前登录客户选择的服务水站
     */
    /**
     * 当前登录员工所属水站
     */
    @RequireRole({"STATION_MANAGER", "DELIVERY"})
    @GetMapping("/mine")
    public Result<Station> getMyStation() {
        Long stationId = AuthContext.getStationId();
        if (stationId == null) {
            return Result.success(null);
        }
        return Result.success(stationService.getById(stationId));
    }

    /**
     * 站长查看自己创建的水站列表
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/mine/list")
    public Result<List<Station>> listMyStations() {
        Long staffId = AuthContext.getUserId();
        return Result.success(stationMapper.listByCreator(staffId));
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping
    public Result save(@RequestBody Station station) {
        Long staffId = AuthContext.getUserId();
        station.setCreatorStaffId(staffId);
        stationService.save(station);
        return Result.success();
    }

    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody Station station) {
        Long staffId = AuthContext.getUserId();
        Station existing = stationMapper.getByIdAndCreator(id, staffId);
        if (existing == null) {
            return Result.error("水站不存在或无权操作他人创建的水站");
        }
        station.setId(id);
        station.setCreatorStaffId(staffId);
        stationService.update(station);
        return Result.success();
    }

    @RequireRole({"STATION_MANAGER"})
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        Long staffId = AuthContext.getUserId();
        Station existing = stationMapper.getByIdAndCreator(id, staffId);
        if (existing == null) {
            return Result.error("水站不存在或无权操作他人创建的水站");
        }
        stationService.delete(id);
        return Result.success();
    }

    /** 获取当前站长水站的线下支付总开关状态 */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}/offline-payment")
    public Result<Map<String, Object>> getOfflinePaymentConfig(@PathVariable Long id) {
        Long staffId = AuthContext.getUserId();
        Station existing = stationMapper.getByIdAndCreator(id, staffId);
        if (existing == null) {
            return Result.error("水站不存在或无权操作");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("stationId", existing.getId());
        result.put("offlinePaymentEnabled", existing.getOfflinePaymentEnabled() != null ? existing.getOfflinePaymentEnabled() : 0);
        return Result.success(result);
    }

    /** 设置当前站长水站的线下支付总开关 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/offline-payment")
    public Result updateOfflinePaymentConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long staffId = AuthContext.getUserId();
        Station existing = stationMapper.getByIdAndCreator(id, staffId);
        if (existing == null) {
            return Result.error("水站不存在或无权操作");
        }
        Integer enabled = body.get("offlinePaymentEnabled") != null ? Integer.valueOf(body.get("offlinePaymentEnabled").toString()) : 0;
        existing.setOfflinePaymentEnabled(enabled);
        stationService.update(existing);
        return Result.success();
    }
}
