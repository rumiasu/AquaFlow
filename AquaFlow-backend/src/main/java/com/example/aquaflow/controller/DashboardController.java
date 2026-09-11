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

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/order-status")
    public Result<?> orderStatus() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(orderMapper.countByStatusByStationId(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/order-trend")
    public Result<?> orderTrend() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(orderMapper.trendLast7DaysByStationId(stationId));
    }

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
