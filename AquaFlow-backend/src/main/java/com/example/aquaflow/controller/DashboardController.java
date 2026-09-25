package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.DashboardService;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 站长经营看板（<b>全部限 {@code STATION_MANAGER}</b>）。
 *
 * <p>水站维度一律由后端按登录站长判定（{@code AuthContext.requireStationId()}），前端不传 stationId。
 * 指标口径若与对账（{@code ReconciliationService}）不一致，以对账为准并在注释里写明差异原因。</p>
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private AddressMapper addressMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private DashboardService dashboardService;

    /**
     * 水站端首页 — 只看本站数据
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/today")
    public Result<Map<String, Object>> today() {
        Long stationId = AuthContext.requireStationId();
        Map<String, Object> data = new HashMap<>();
        data.put("pendingOrders", orderMapper.countByStationIdAndStatus(stationId, OrderStatus.PENDING));
        data.put("deliveringOrders", orderMapper.countByStationIdAndStatus(stationId, OrderStatus.DELIVERING));
        data.put("finishedToday", orderMapper.countTodayByStationId(stationId));
        data.put("totalOrders", orderMapper.countByStationId(stationId));
        
        java.util.List<Inventory> inventoryList = inventoryService.list(stationId);
        data.put("lowStock", inventoryList.stream().filter(i -> i.getQuantity() < 20).count());
        data.put("totalInventory", inventoryList.stream().mapToInt(Inventory::getQuantity).sum());
        
        return Result.success(data);
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Long stationId = AuthContext.requireStationId();
        Map<String, Object> data = new HashMap<>();
        data.put("customerCount", customerMapper.countByStationId(stationId));
        data.put("orderCount", orderMapper.countByStationId(stationId));
        data.put("inventoryTotal", inventoryMapper.listByStationId(stationId)
                .stream().mapToInt(Inventory::getQuantity).sum());
        data.put("pendingOrderCount", orderMapper.countByStationIdAndStatus(stationId, OrderStatus.PENDING));
        return Result.success(data);
    }

    // [2026-09-18 删除] GET /order-status 与 GET /order-trend：两个端点零前端调用，且口径与
    // GET /api/dashboard/report 分叉 —— 同一个指标两套算法，正是本仓"口径分叉"的老来源
    // （docs/audit/2026-09-16-死端点评估.md 判"删除"，已执行）。看板数据一律走下面的 /report。
    // 回归：ManagerOrderControllerRemovedIntegrationTest 断言这两条路径返回 404。

    /**
     * 综合数据报表。
     * GET /api/dashboard/report?range=today|7d|30d
     *
     * <p>返回：周期汇总 + 环比对比（自动取上一等长周期）+ 按天趋势（补零）+
     * 状态/支付方式分布 + 热销商品/客户 TOP5 + 配送员业绩 + 下单时段分布 + 欠桶提醒。
     * 水站取自登录站长，不接受前端传参。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/report")
    public Result<?> report(@RequestParam(defaultValue = "7d") String range) {
        Long stationId = AuthContext.requireStationId();
        return Result.success(dashboardService.report(stationId, range));
    }
}
