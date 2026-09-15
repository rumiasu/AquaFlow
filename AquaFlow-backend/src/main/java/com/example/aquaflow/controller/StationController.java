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

/**
 * 水站接口 —— <b>同一个前缀下混装了"顾客可用"与"员工专属"两类端点，务必逐方法看注解</b>。
 *
 * <p><b>顾客可用</b>（无注解）：{@code /public} 营业中水站列表、{@code /search} 公开搜索、
 * {@code /{id}/public-phone} 取水站电话。
 * <b>员工专属</b>：{@code /mine}、{@code /mine/list} 以及全部写接口。</p>
 *
 * <p><b>⚠️ 顾客端最容易踩的坑就是 {@code GET /mine}</b> —— 它的名字像"我的水站"，
 * 实际是<b>员工</b>所属水站（{@code @RequireRole({"STATION_MANAGER","DELIVERY"})}），顾客调用恒 403。
 * 顾客要"我的服务水站"请用 {@code GET /api/orders/my-station}；小程序侧统一走
 * {@code miniapp-user/utils/station.js#resolveStationId()}。
 * 该误用已真实发生三次，详见 {@code getMyStation()} 方法上的记录。</p>
 *
 * <p>另注：本类用 {@code @RequestMapping({"/api/stations", "/api/station"})} 双前缀（兼容历史单数写法），
 * 用文本工具扫端点时别只匹配单值形式，否则整个类的路径都会漏掉。</p>
 */
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
     * 公开：获取站点联系电话（顾客端资产说明弹窗展示用，无需登录）
     * 仅返回 id/name/phone，避免泄露站长等内部字段
     */
    @GetMapping("/{id}/public-phone")
    public Result<Map<String, Object>> getPublicPhone(@PathVariable Long id) {
        Station s = stationMapper.getById(id);
        if (s == null) {
            return Result.error("水站不存在");
        }
        Map<String, Object> info = new HashMap<>(3);
        info.put("id", s.getId());
        info.put("name", s.getName());
        info.put("phone", s.getPhone());
        return Result.success(info);
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
     * 当前登录<b>员工</b>所属水站（站长 / 配送员）。
     *
     * <p><b>⚠️ 顾客端禁止调用本接口。</b>它带
     * {@code @RequireRole({"STATION_MANAGER","DELIVERY"})}，顾客 token 请求必然 403；
     * 而小程序侧这类失败常被静默吞掉，表现成"功能莫名失效"而不是报错，极难排查。
     * 顾客端历史上已因此误用三次：模板页保存（stationId 恒 null → 模板存不进）、
     * 下单页"再来一单"（跨站校验沦为死分支）、以及更早的取水站电话。</p>
     *
     * <p>顾客要取「我的服务水站」，请用 {@code GET /api/orders/my-station}
     * （顾客可用；优先上次下单的水站，无订单时回退客户与站点的绑定配置）；
     * 小程序侧统一走 {@code miniapp-user/utils/station.js} 的 {@code resolveStationId()}。</p>
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

}
