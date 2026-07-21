package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.BarrelReturnDTO;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.service.BarrelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 水桶管理接口（后端: BarrelController）
 * <p>提供水桶概况查询、退桶记录、退桶申请等功能。</p>
 */
@RestController
@RequestMapping("/api/barrels")
@Slf4j
public class BarrelController {

    @Autowired
    private BarrelService barrelService;

    /**
     * 获取客户水桶概况
     * GET /api/barrels/summary?customerId=xxx
     */
    @GetMapping("/summary")
    public Result<Map<String, Object>> getSummary(@RequestParam Integer customerId) {
        return Result.success(barrelService.getSummary(customerId));
    }

    /**
     * 获取客户退桶记录列表
     * GET /api/barrels/records?customerId=xxx
     */
    @GetMapping("/records")
    public Result<List<BarrelRecord>> listRecords(@RequestParam Integer customerId) {
        return Result.success(barrelService.listRecords(customerId));
    }

    /**
     * 按水类型统计持有桶数明细
     * GET /api/barrels/summary-by-type?customerId=xxx
     */
    @GetMapping("/summary-by-type")
    public Result<List<Map<String, Object>>> getSummaryByType(@RequestParam Integer customerId) {
        return Result.success(barrelService.getBarrelSummaryByType(customerId));
    }

    /**
     * 客户申请退桶
     * POST /api/barrels/return
     */
    @PostMapping("/return")
    public Result<BarrelRecord> requestReturn(@RequestBody BarrelReturnDTO dto) {
        log.info("退桶申请: customerId={}, quantity={}, depositRefund={}",
                dto.getCustomerId(), dto.getQuantity(), dto.getDepositRefund());
        BarrelRecord record = barrelService.requestReturn(
                dto.getCustomerId(),
                dto.getQuantity(),
                dto.getDepositRefund(),
                dto.getNote()
        );
        return Result.success(record);
    }

    /** 管理端：查看所有退桶记录 */
    @GetMapping("/all-records")
    public Result<List<BarrelRecord>> listAllRecords() {
        return Result.success(barrelService.listAllRecords());
    }

    /** 管理端：审批退桶记录 */
    @PutMapping("/records/{id}/status")
    public Result handleReturn(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        Integer status = (Integer) body.get("status");
        String handleNote = (String) body.get("handleNote");
        barrelService.handleReturn(id, status, handleNote);
        return Result.success();
    }
}
