package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Batch;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.InventoryService;
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
    private BatchMapper batchMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private InventoryService inventoryService;

    /**
     * 水站端首页 — 只看本站数据
     * stationId 由前端自动注入（request interceptor）
     */
    @GetMapping("/today")
    public Result<Map<String, Object>> today(@RequestParam(required = false) Integer stationId) {
        Map<String, Object> data = new HashMap<>();
        data.put("pendingBatches", batchMapper.countByStatus(1));
        data.put("deliveringBatches", batchMapper.countByStatus(2));
        data.put("finishedBatches", batchMapper.countByStatus(3));
        data.put("pendingOrders", orderMapper.list(stationId, null, 1, null, null, null).size());
        data.put("deliveringQty", batchMapper.sumDeliveringQty());
        java.util.List<Inventory> inventoryList = inventoryService.list(stationId);
        data.put("lowStock", inventoryList.stream().filter(i -> i.getQuantity() < 20).count());
        data.put("totalBucketsOwed", orderMapper.sumBucketsOwed());
        data.put("enterprisePendingCount", orderMapper.countEnterprisePending());
        data.put("unpaidOrders", orderMapper.countByPaymentStatus(1));
        return Result.success(data);
    }

    @GetMapping("/pending-batches")
    public Result<java.util.List<Batch>> pendingBatches() {
        return Result.success(batchMapper.list(1, null, null));
    }

    @GetMapping("/delivering-batches")
    public Result<java.util.List<Batch>> deliveringBatches() {
        return Result.success(batchMapper.list(2, null, null));
    }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(required = false) Integer stationId) {
        Map<String, Object> data = new HashMap<>();
        if (stationId != null) {
            // 水站端：只看本站
            data.put("customerCount", customerMapper.countByStationId(stationId));
            data.put("orderCount", orderMapper.countTotalByStationId(stationId));
            data.put("inventoryTotal", inventoryMapper.listByStationId(stationId)
                    .stream().mapToInt(Inventory::getQuantity).sum());
        } else {
            // 水厂端：全局统计
            data.put("customerCount", customerMapper.countAll());
            data.put("addressCount", addressMapper.countAll());
            data.put("orderCount", orderMapper.countAll());
            data.put("batchCount", batchMapper.countAll());
            data.put("inventoryTotal", inventoryMapper.sumQuantity());
        }
        data.put("deliveringBatchCount", batchMapper.countByStatus(2));
        data.put("pendingOrderCount", orderMapper.list(stationId, null, 1, null, null, null).size());
        return Result.success(data);
    }

    @GetMapping("/order-source")
    public Result<?> orderSource(@RequestParam(required = false) Integer stationId) {
        return Result.success(orderMapper.countBySource());
    }

    @GetMapping("/order-status")
    public Result<?> orderStatus(@RequestParam(required = false) Integer stationId) {
        return Result.success(orderMapper.countByStatus());
    }

    @GetMapping("/order-trend")
    public Result<?> orderTrend(@RequestParam(required = false) Integer stationId) {
        return Result.success(orderMapper.trendLast7Days());
    }

    @GetMapping("/water-type-sales")
    public Result<?> waterTypeSales(@RequestParam(required = false) Integer stationId) {
        return Result.success(orderMapper.salesByWaterType());
    }

    @GetMapping("/top-customers")
    public Result<?> topCustomers(@RequestParam(required = false) Integer stationId) {
        return Result.success(orderMapper.topCustomers());
    }
}
