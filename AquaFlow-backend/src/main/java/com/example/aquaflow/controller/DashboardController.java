package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Batch;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
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

    // ========== 配送员核心数据 ==========

    @GetMapping("/today")
    public Result<Map<String, Object>> today() {
        Map<String, Object> data = new HashMap<>();
        data.put("pendingBatches", batchMapper.countByStatus(1));
        data.put("deliveringBatches", batchMapper.countByStatus(2));
        data.put("finishedBatches", batchMapper.countByStatus(3));
        data.put("pendingOrders", orderMapper.list(null, 1, null, null, null).size());
        data.put("deliveringQty", batchMapper.sumDeliveringQty());
        List<Inventory> inventoryList = inventoryService.list();
        data.put("lowStock", inventoryList.stream().filter(i -> i.getQuantity() < 20).count());
        // V2: 回桶统计
        data.put("totalBucketsOwed", orderMapper.sumBucketsOwed());
        // V2: 企业待结算
        data.put("enterprisePendingCount", orderMapper.countEnterprisePending());
        // V2: 待付款订单
        data.put("unpaidOrders", orderMapper.countByPaymentStatus(1));
        return Result.success(data);
    }

    @GetMapping("/pending-batches")
    public Result<List<Batch>> pendingBatches() {
        return Result.success(batchMapper.list(1, null, null));
    }

    @GetMapping("/delivering-batches")
    public Result<List<Batch>> deliveringBatches() {
        return Result.success(batchMapper.list(2, null, null));
    }

    // ========== 管理数据 ==========

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> data = new HashMap<>();
        data.put("customerCount", customerMapper.countAll());
        data.put("addressCount", addressMapper.countAll());
        data.put("orderCount", orderMapper.countAll());
        data.put("batchCount", batchMapper.countAll());
        data.put("inventoryTotal", inventoryMapper.sumQuantity());
        data.put("deliveringBatchCount", batchMapper.countByStatus(2));
        data.put("pendingOrderCount", orderMapper.list(null, 1, null, null, null).size());
        return Result.success(data);
    }

    @GetMapping("/order-source")
    public Result<?> orderSource() {
        return Result.success(orderMapper.countBySource());
    }

    @GetMapping("/order-status")
    public Result<?> orderStatus() {
        return Result.success(orderMapper.countByStatus());
    }

    @GetMapping("/order-trend")
    public Result<?> orderTrend() {
        return Result.success(orderMapper.trendLast7Days());
    }

    @GetMapping("/water-type-sales")
    public Result<?> waterTypeSales() {
        return Result.success(orderMapper.salesByWaterType());
    }

    @GetMapping("/top-customers")
    public Result<?> topCustomers() {
        return Result.success(orderMapper.topCustomers());
    }
}
