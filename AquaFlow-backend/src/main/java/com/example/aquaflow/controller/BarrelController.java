package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.BarrelService;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.dto.BarrelRecordStatusDTO;
import com.example.aquaflow.dto.BarrelReturnEmptyDTO;
import com.example.aquaflow.dto.BarrelReturnRequestDTO;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
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
    private ProductMapper productMapper;

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
     * 获取当前登录客户的桶资产站级汇总（持有/欠桶/配送中/押金余额等）
     */
    @GetMapping("/summary")
    public Result<Map<String, Object>> getBarrelSummary(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.success(java.util.Collections.emptyMap());
        }
        return Result.success(barrelService.getBarrelSummary(customerId, effectiveStationId));
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

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/all-records")
    public Result<List<BarrelRecord>> getAllRecords(@RequestParam(required = false) Integer limit) {
        Long stationId = AuthContext.requireStationId();
        // [AQ-046] 分页兜底：默认最近 500 条、上限 2000，避免记录量增大后一次全量返回
        int n = (limit == null || limit <= 0) ? 500 : Math.min(limit, 2000);
        List<BarrelRecord> records = barrelRecordMapper.listByStationId(stationId, n);
        // 为每条退桶申请(type=2)补充客户当前欠桶数，用于站长审批提醒
        for (BarrelRecord r : records) {
            // 欠桶改为按商品统计：退的是哪种桶，就看那种桶欠不欠（A 水多还不能抵 B 水欠桶）
            if (r.getType() != null && r.getType() == 2 && r.getCustomerId() != null && r.getProductId() != null) {
                r.setOwedBuckets(barrelLedgerService.overQty(r.getCustomerId(), stationId, r.getProductId()));
            }
        }
        return Result.success(records);
    }

    /**
     * 退桶试算（只读）：退 N 个桶能拿回多少钱，以及依据（哪几张押金条、各自买入单价）。
     *
     * <p>顾客申请前先看到金额，柜台不用吵架。
     * {@code hasMigratedPrice=true} 表示这批单价是<b>历史迁移时推断的</b>、不是真实成交价，前端应提示站长复核。</p>
     */
    @GetMapping("/return/preview")
    public Result<Map<String, Object>> previewReturn(@RequestParam Long productId,
                                                     @RequestParam Integer quantity,
                                                     @RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.error("请先选择服务水站");
        }
        if (productId == null || quantity == null || quantity <= 0) {
            return Result.error("商品和数量不能为空");
        }
        try {
            return Result.success(barrelService.previewReturn(customerId, effectiveStationId, productId, quantity));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 客户申请退桶（type=2，创建待审批记录，不立即扣减桶资产）
     *
     * <p>申请单上的 {@code depositRefund} 是<b>按押金条批次试算出来的估值</b>，不是当前商品押金价。
     * 真正退款时（站长退押金）会重新核销一次；若期间批次有变动，以实际核销金额为准。</p>
     */
    @PostMapping("/return")
    public Result<Map<String, Object>> requestReturn(@RequestBody @Valid BarrelReturnRequestDTO dto) {
        Long customerId = AuthContext.requireCustomerId();
        Long stationId = dto.getStationId() != null ? dto.getStationId() : AuthContext.getStationId();
        if (stationId == null) {
            return Result.error("请先选择服务水站");
        }
        Long productId = dto.getProductId();
        Integer quantity = dto.getQuantity();
        String note = dto.getNote() != null ? dto.getNote() : "";

        if (productId == null || quantity == null || quantity <= 0) {
            return Result.error("商品和数量不能为空");
        }
        if (productMapper.getById(productId) == null) {
            return Result.error("商品不存在");
        }

        // 试算（只读）：既拿到预估退款额，也顺便把「权益不足 / 有欠桶」在申请阶段就拦掉，
        // 而不是等站长审批时才失败——那样顾客会以为申请成功了，白等一场。
        Map<String, Object> preview;
        try {
            preview = barrelService.previewReturn(customerId, stationId, productId, quantity);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
        if (Boolean.TRUE.equals(preview.get("blocked"))) {
            return Result.error(String.valueOf(preview.get("blockedReason")));
        }
        int effectiveQty = preview.get("effectiveQty") == null
                ? quantity : ((Number) preview.get("effectiveQty")).intValue();
        if (effectiveQty <= 0) {
            return Result.error("可退权益不足");
        }
        BigDecimal depositRefund = preview.get("refundAmount") instanceof BigDecimal
                ? (BigDecimal) preview.get("refundAmount")
                : new BigDecimal(String.valueOf(preview.get("refundAmount")));

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

        // 回传试算结果，前端可即时展示「预计退回 ¥X」与提示，不用再自己按押金单价乘一遍
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("recordId", record.getId());
        data.put("quantity", quantity);
        data.put("refundAmount", depositRefund);
        data.put("hasMigratedPrice", preview.get("hasMigratedPrice"));
        return Result.success(data);
    }

    /**
     * 站长审批退桶申请 —— 状态机 1 → 2 → 3，<b>不允许跳步</b>（DEF-7）。
     * status: 2=确认收到空桶 3=已退押金（按押金条批次核销） 4=驳回
     */
    @RequireRole("STATION_MANAGER")
    @PutMapping("/records/{id}/status")
    public Result<Void> handleReturn(@PathVariable Long id, @RequestBody @Valid BarrelRecordStatusDTO dto) {
        Long stationId = AuthContext.requireStationId();
        Integer status = dto.getStatus();
        String handleNote = dto.getHandleNote() != null ? dto.getHandleNote() : "";
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

    /**
     * 纯还桶：顾客交回空桶但【不】带走满桶 —— 只冲减欠桶(over)，<b>不扣权益、不退款</b>。
     *
     * <p>为什么需要这个动作：旧模型里消掉欠桶的唯一途径是"下次配送时多还"，
     * 而欠桶 ≥ 阈值又会被拒单，于是顾客一旦欠桶就永久锁死、押金退不出来。
     * 有了这个入口，随时可以主动把桶还回来，over 会被冲减，可以为负（多还桶/水站暂存）。</p>
     *
     * <p>业务边界：over &lt; 0 是合法状态（不是脏数据、不是负债、不是负权益），
     * 所以这里<b>不做任何"结果必须非负"的校验</b>；唯一校验是物理上限「交回数 ≤ 持有数」。
     * 真正想拿回钱要走退桶（/return），那才会按押金条批次核销退款。</p>
     *
     * <p><b>⚠️ 必须加事务</b>：改 over 和写流水必须是原子的。
     * {@code BarrelLedgerService.returnEmpty} 自带 {@code @Transactional}，但若此处没有外层事务，
     * 它一返回就提交；之后 insert 流水失败（例如 clientToken 撞唯一键）时 over 已经落库、回滚不了——
     * 结果是「桶账少了一个桶，却没有任何流水」，对账 E5 立刻就不平。</p>
     */
    @RequireRole({"STATION_MANAGER", "DELIVERY"})
    @PostMapping("/return-empty")
    @org.springframework.transaction.annotation.Transactional
    public Result<Map<String, Object>> returnEmpty(@RequestBody @Valid BarrelReturnEmptyDTO dto) {
        Long stationId = AuthContext.requireStationId();
        Long customerId = dto.getCustomerId();
        String clientToken = dto.getClientToken();
        String note = dto.getNote() != null ? dto.getNote() : "";
        if (customerId == null) return Result.error("客户不能为空");
        if (clientToken == null || clientToken.isEmpty()) return Result.error("缺少幂等 token");

        List<BarrelLedgerService.ItemQty> items = new java.util.ArrayList<>();
        for (BarrelReturnEmptyDTO.BarrelReturnEmptyItemDTO it : dto.getItems()) {
            items.add(new BarrelLedgerService.ItemQty(it.getProductId(), it.getQty()));
        }

        // 幂等：同一 token 已处理过就直接返回成功，绝不再动一次账。
        // 为什么必须先查而不是靠唯一键兜底：唯一键冲突是在【账已经改完、准备写流水时】才炸，
        // 那时 over 已经减了；事务回滚虽能救回来，但前端拿到的是一句"请勿重复提交"的错误，
        // 对超时重试的场景毫无帮助。先查则能给出明确的成功响应。
        if (!items.isEmpty()) {
            BarrelRecord existed = barrelRecordMapper.getByClientToken(
                    clientToken + "#" + items.get(0).getProductId());
            if (existed != null) {
                Map<String, Object> dup = new java.util.HashMap<>();
                dup.put("changes", java.util.Collections.emptyList());
                dup.put("refundAmount", BigDecimal.ZERO);
                dup.put("duplicate", true);
                return Result.success(dup);
            }
        }

        // [DEF-4] 不在此处 catch 业务异常：
        // 本方法带 @Transactional，若把异常吞掉再返回 Result.error，Spring 仍会把事务标记为
        // rollback-only，提交时抛 UnexpectedRollbackException —— 于是「交回数超过持有数」这种
        // 正常业务拒绝会被伪装成 code=500。交给 GlobalExceptionHandler 统一转 code=1 才正确。
        Long operatorId = AuthContext.getUserId();
        List<BarrelLedgerService.OverChange> changes =
                barrelLedgerService.returnEmpty(customerId, stationId, items, operatorId);

        // 留痕：纯还桶 type=7，refund_amount 恒为 0（它不产生任何退款），over 前后值可负
        for (BarrelLedgerService.OverChange c : changes) {
            BarrelRecord record = new BarrelRecord();
            record.setCustomerId(customerId);
            record.setStationId(stationId);
            record.setProductId(c.getProductId());
            record.setType(7); // 纯还桶
            record.setQuantity(c.getQty());
            record.setStatus(3); // 纯还桶即时生效，无需审批
            record.setDepositRefund(BigDecimal.ZERO);
            record.setClientToken(clientToken + "#" + c.getProductId());
            record.setOverBefore(c.getOverBefore());
            record.setOverAfter(c.getOverAfter());
            record.setNote(note);
            record.setOperatorId(operatorId);
            record.setCreateTime(java.time.LocalDateTime.now());
            barrelRecordMapper.insert(record);
        }

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("changes", changes);
        data.put("refundAmount", BigDecimal.ZERO);
        return Result.success(data);
    }

    @Autowired
    private BarrelLedgerService barrelLedgerService;
}
