package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.InventoryRecord;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 库存（<b>站长专属</b>：查询 / 入库 / 入库流水全部 {@code STATION_MANAGER}）。
 *
 * <p>库存按 {@code (station_id, product_id)} 隔离（唯一键 {@code uk_inventory_station_product}），
 * 水站由后端按登录站长判定。入库走 {@code InventoryService.inbound}，
 * 同时落 {@code inventory_record} 备查 —— 不要直接 UPDATE 数量列，那会让流水与库存对不上。</p>
 *
 * <p>另注：商品是<b>全局</b>的，"某站能不能买"完全由本表决定（含上架、水票开关与价格）。</p>
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;
    @Autowired private com.example.aquaflow.service.InventoryLossService lossService;
    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/{productId}/loss") public Result<java.util.Map<String,Object>> loss(@PathVariable Long productId,@jakarta.validation.Valid @RequestBody com.example.aquaflow.dto.InventoryLossDTO dto) {
        return Result.success(lossService.record(productId,AuthContext.requireStationId(),dto));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<Inventory>> list(@RequestParam(required = false) Long stationId){
        stationId = AuthContext.requireStationId();
        return Result.success(inventoryService.list(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/inbound")
    public Result inbound(@RequestParam Long stationId, @RequestBody InventoryInboundDTO inventoryInboundDTO) {
        if (!AuthContext.requireStationId().equals(stationId)) {
            return Result.error("只能向本站入库");
        }
        if (inventoryInboundDTO.getClientRequestId() != null) {
            return Result.success(inventoryService.inboundWithReceipt(stationId,
                    inventoryInboundDTO.getItems(), inventoryInboundDTO.getClientRequestId()));
        }
        // 兼容无编号旧客户端；其重试没有去重保证，新员工端只接受匹配编号的回执。
        inventoryService.inbound(stationId, inventoryInboundDTO.getItems());
        return Result.success();
    }

    /** 员工端原编号入库；独立路径使新前端遇旧后端时404，而非被旧逻辑忽略编号后重复累加。 */
    @RequireRole({"STATION_MANAGER"})
    @PostMapping({"/inbound-with-receipt", "/inbound-with-cost-receipt"})
    public Result inboundWithReceipt(@RequestParam Long stationId, @RequestBody InventoryInboundDTO dto) {
        if (!AuthContext.requireStationId().equals(stationId)) return Result.error("只能向本站入库");
        return Result.success(inventoryService.inboundWithReceipt(stationId, dto.getItems(), dto.getClientRequestId()));
    }

    /**
     * [AQ-029] 查询本站库存流水（进出明细），供站长核对库存变动。
     *
     * @param productId 可选：只看某一种商品的进出（站长核对单品时用）；不传 = 本站全部商品
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/records")
    public Result<List<InventoryRecord>> records(@RequestParam(required = false) Integer limit,
                                                @RequestParam(required = false) Long productId) {
        Long stationId = AuthContext.requireStationId();
        int n = (limit == null || limit <= 0) ? 200 : Math.min(limit, 1000);
        return Result.success(inventoryService.listRecords(stationId, productId, n));
    }
}
