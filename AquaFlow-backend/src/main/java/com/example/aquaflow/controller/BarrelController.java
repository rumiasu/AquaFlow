package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelOwed;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.CustomerBarrelOwedMapper;
import com.example.aquaflow.service.BarrelService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 桶资产管理接口
 */
@RestController
@RequestMapping("/api/barrels")
public class BarrelController {

    @Autowired
    private BarrelService barrelService;

    @Autowired
    private BarrelRecordMapper barrelRecordMapper;

    @Autowired
    private CustomerBarrelOwedMapper customerBarrelOwedMapper;

    /**
     * 获取当前登录客户的桶资产摘要（按水类型分组）
     */
    @GetMapping("/summary-by-type")
    public Result<List<Map<String, Object>>> getBarrelSummaryByType(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.success(java.util.Collections.emptyList());
        }
        return Result.success(barrelService.getBarrelSummaryByType(customerId, effectiveStationId));
    }

    /**
     * 获取当前登录客户的桶资产（可指定站点）
     */
    @GetMapping("/assets")
    public Result<List<CustomerBarrelAsset>> getAssets(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        // 优先使用传入的 stationId，其次使用客户绑定的站点
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.success(java.util.Collections.emptyList());
        }
        return Result.success(barrelService.getAssets(customerId, effectiveStationId));
    }

    /**
     * 获取当前登录客户的桶异常记录（可指定站点）
     */
    @GetMapping("/records")
    public Result<List<BarrelRecord>> listRecords(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.success(java.util.Collections.emptyList());
        }
        return Result.success(barrelService.listRecords(customerId, effectiveStationId));
    }

    /**
     * 管理端：处理桶异常（丢桶/损坏等）
     */
    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/handle-exception")
    public Result<Void> handleException(@RequestBody java.util.Map<String, Object> params) {
        Long customerId = ((Number) params.get("customerId")).longValue();
        Long productId = ((Number) params.get("productId")).longValue();
        Integer type = (Integer) params.get("type");
        Integer quantity = (Integer) params.get("quantity");
        Long relatedOrderId = params.get("relatedOrderId") != null ? ((Number) params.get("relatedOrderId")).longValue() : null;
        String note = (String) params.get("note");
        Long operatorId = AuthContext.getUserId();
        Long stationId = AuthContext.requireStationId();

        barrelService.handleBarrelException(customerId, stationId, productId, type, quantity, relatedOrderId, note, operatorId);
        return Result.success();
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/all-records")
    public Result<List<BarrelRecord>> getAllRecords() {
        Long stationId = AuthContext.requireStationId();
        List<BarrelRecord> records = barrelRecordMapper.listByStationId(stationId);
        // 为每条退桶申请(type=2)补充客户当前欠桶数，用于站长审批提醒
        for (BarrelRecord r : records) {
            if (r.getType() != null && r.getType() == 2 && r.getCustomerId() != null) {
                CustomerBarrelOwed owed = customerBarrelOwedMapper.get(r.getCustomerId(), stationId);
                r.setOwedBuckets(owed != null && owed.getOwedQty() != null ? owed.getOwedQty() : 0);
            }
        }
        return Result.success(records);
    }

    /**
     * 客户申请退桶（type=2，创建待审批记录，不立即扣减桶资产）
     */
    @PostMapping("/return")
    public Result<Void> requestReturn(@RequestBody Map<String, Object> params) {
        Long customerId = AuthContext.requireCustomerId();
        Long stationId = params.get("stationId") != null
                ? ((Number) params.get("stationId")).longValue()
                : AuthContext.getStationId();
        if (stationId == null) {
            return Result.error("请先选择服务水站");
        }
        Long productId = params.get("productId") != null ? ((Number) params.get("productId")).longValue() : null;
        Integer quantity = params.get("quantity") != null ? ((Number) params.get("quantity")).intValue() : null;
        BigDecimal depositRefund = params.get("depositRefund") != null
                ? new BigDecimal(params.get("depositRefund").toString())
                : BigDecimal.ZERO;
        String note = params.get("note") != null ? params.get("note").toString() : "";

        if (productId == null || quantity == null || quantity <= 0) {
            return Result.error("商品和数量不能为空");
        }

        BarrelRecord record = new BarrelRecord();
        record.setCustomerId(customerId);
        record.setStationId(stationId);
        record.setProductId(productId);
        record.setType(2); // 退桶
        record.setQuantity(quantity);
        record.setNote(note);
        record.setDepositRefund(depositRefund);
        record.setStatus(1); // 待审批
        record.setCreateTime(java.time.LocalDateTime.now());
        barrelRecordMapper.insert(record);

        return Result.success();
    }

    /**
     * 站长审批退桶申请
     * status: 2=确认收到空桶 3=已退押金（扣资产+退押金） 4=驳回
     */
    @RequireRole("STATION_MANAGER")
    @PutMapping("/records/{id}/status")
    public Result<Void> handleReturn(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        Long stationId = AuthContext.requireStationId();
        Integer status = params.get("status") != null ? ((Number) params.get("status")).intValue() : null;
        String handleNote = params.get("handleNote") != null ? params.get("handleNote").toString() : "";
        if (status == null) {
            return Result.error("status 不能为空");
        }

        BarrelRecord record = barrelRecordMapper.getById(id);
        if (record == null) {
            return Result.error("退桶记录不存在");
        }
        if (!stationId.equals(record.getStationId())) {
            return Result.error("无权处理他站退桶申请");
        }

        Long operatorId = AuthContext.getUserId();
        try {
            barrelService.handleBarrelReturn(id, status, handleNote, operatorId);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
        return Result.success();
    }
}
