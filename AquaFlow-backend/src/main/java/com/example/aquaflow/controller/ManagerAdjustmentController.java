package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.AdjustType;
import com.example.aquaflow.dto.AdjustmentCreateDTO;
import com.example.aquaflow.dto.AdjustmentPreviewDTO;
import com.example.aquaflow.entity.StationAdjustment;
import com.example.aquaflow.service.StationAdjustmentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站长资产调整单 —— <b>历史 / 存量资产进入系统的唯一正式通道</b>。
 *
 * <p><b>⚠️ 做存量迁移时，这两条错路别走</b>：① 让站长"补假订单"来凑资产 —— 污染营收口径、
 * 配送记录与对账，那些单从未真实发生；② 直接改余额列 —— 绕过账本、审计与对账。
 * 本功能<b>不产生订单</b>，只按类型走各自账本的唯一写入口
 * （桶→{@code BarrelLedgerService}、押金→{@code DepositRecordService}、
 * 水票→{@code TicketAccountService}），并强制记录办理人、原因、凭证与调整前后快照，
 * 最终仍被对账 E5 纳入守恒校验。</p>
 *
 * <p>它是<b>例外通道而非常规运营功能</b>：每张单必须有 reason；
 * <b>金额方向由 {@link AdjustType} 决定，调用方一律传正数</b>，不要用正负号表达方向。</p>
 *
 * <p>权限：类级 {@code @RequireRole("STATION_MANAGER")}；站别由服务层用
 * {@code AuthContext.requireStationId()} 校验，不信任请求体里的站别字段，{@code DELIVERY} 一律拒绝。</p>
 *
 * <p>业务背景、用途三分（历史迁移 / 人工补录 / 代客订正）与完整设计见
 * {@code docs/design/10-站长资产调整单.md}。</p>
 */
@RestController
@RequestMapping("/api/manager/adjustments")
@RequireRole("STATION_MANAGER")
public class ManagerAdjustmentController {

    @Autowired
    private StationAdjustmentService adjustmentService;

    /** 列表（本站；可按客户过滤） */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(required = false) Long customerId,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> data = adjustmentService.list(customerId, page, size);
        @SuppressWarnings("unchecked")
        List<StationAdjustment> rows = (List<StationAdjustment>) data.get("list");
        data.put("list", rows.stream().map(ManagerAdjustmentController::decorate).toList());
        return Result.success(data);
    }

    /** 详情 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.success(decorate(adjustmentService.getById(id)));
    }

    /** 只读试算：返回调整前后的权益/欠桶/占用/押金/水票与预计金额，不落库 */
    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody @Valid AdjustmentPreviewDTO dto) {
        return Result.success(adjustmentService.preview(dto.getCustomerId(), dto.getAdjustType(),
                dto.getProductId(), dto.getQty(), dto.getAmount(), dto.getUnitPrice()));
    }

    /** 创建（PENDING）。clientToken 幂等：重复提交返回原单。 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody @Valid AdjustmentCreateDTO dto) {
        StationAdjustment a = adjustmentService.create(dto.getCustomerId(), dto.getAdjustType(),
                dto.getProductId(), dto.getQty(), dto.getAmount(), dto.getUnitPrice(),
                dto.getReason(), dto.getEvidence(), dto.getClientToken());
        return Result.success(decorate(a));
    }

    /** 执行（CAS PENDING→EFFECTIVE；重复执行被拒绝） */
    @PostMapping("/{id}/execute")
    public Result<Void> execute(@PathVariable Long id) {
        adjustmentService.execute(id);
        return Result.success();
    }

    /** 撤销：生成反向单并执行，原单置 REVERSED */
    @PostMapping("/{id}/reverse")
    public Result<Map<String, Object>> reverse(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        Object token = body == null ? null : body.get("clientToken");
        if (token == null || String.valueOf(token).trim().isEmpty()) {
            return Result.error("clientToken 不能为空（幂等键）");
        }
        StationAdjustment rev = adjustmentService.reverse(id,
                reason == null ? null : String.valueOf(reason), String.valueOf(token));
        return Result.success(decorate(rev));
    }

    /**
     * 补齐展示文案：前端只渲染后端下发的文案，禁止自行维护 adjustType → 文案映射表
     * （历史上两端各写一套映射导致新客下单 100% 失败）。
     */
    private static Map<String, Object> decorate(StationAdjustment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("adjustNo", a.getAdjustNo());
        m.put("customerId", a.getCustomerId());
        m.put("productId", a.getProductId());
        m.put("adjustType", a.getAdjustType());
        m.put("adjustTypeText", AdjustType.textOf(a.getAdjustType()));
        m.put("qty", a.getQty());
        m.put("amount", a.getAmount());
        m.put("unitPrice", a.getUnitPrice());
        m.put("priceSource", a.getPriceSource());
        m.put("isMigrated", a.getIsMigrated());
        m.put("reason", a.getReason());
        m.put("evidence", a.getEvidence());
        m.put("beforeSnapshot", a.getBeforeSnapshot());
        m.put("afterSnapshot", a.getAfterSnapshot());
        m.put("status", a.getStatus());
        m.put("statusText", statusTextOf(a.getStatus()));
        m.put("operatorId", a.getOperatorId());
        m.put("executorId", a.getExecutorId());
        m.put("reverses", a.getReverses());
        m.put("reversedBy", a.getReversedBy());
        m.put("executeTime", a.getExecuteTime());
        m.put("createTime", a.getCreateTime());
        return m;
    }

    private static String statusTextOf(String status) {
        if (status == null) return "未知";
        switch (status) {
            case "PENDING":   return "待执行";
            case "EFFECTIVE": return "已生效";
            case "REVERSED":  return "已撤销";
            case "REJECTED":  return "已驳回";
            default:          return status;
        }
    }
}
