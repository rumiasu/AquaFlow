package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.TicketAddDTO;
import com.example.aquaflow.dto.TicketConsumeDTO;
import com.example.aquaflow.dto.TicketPurchaseDTO;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 水票账户。
 *
 * <p><b>顾客侧</b>（无注解 + {@code requireCustomerId()}）：{@code GET /api/tickets} 查自己的余额、
 * {@code /purchase} 购买。<b>站长侧</b>（{@code STATION_MANAGER}）：按客户查询、加票、扣票。</p>
 *
 * <p><b>⚠️ 水票是唯一「下单即视同已付」的支付方式</b>，它绕过 {@code confirmPayment}，
 * 所以<b>押金入账必须在本服务这条路径自行补齐</b>，否则会出现"客户用票付了押金、押金账户却是 0、
 * 退桶时退不出钱"（见 AGENTS.md §8 第 4 条）。动扣票/加票逻辑前请先回看那条。</p>
 */
@RestController
@RequestMapping("/api/tickets")
@Slf4j
public class TicketAccountController {

    @Autowired
    private TicketAccountService ticketAccountService;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    /**
     * 我的水票账户。
     * <p>stationId 可选：客户尚未选水站时返回空列表，而不是 400「缺少必填参数：stationId」
     * ——前端在未选站时正是这么调的。</p>
     */
    @GetMapping
    public Result<List<Map<String, Object>>> listByCustomerId(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        Long effectiveStationId = stationId != null ? stationId : AuthContext.getStationId();
        if (effectiveStationId == null) {
            return Result.success(java.util.Collections.emptyList());
        }
        return Result.success(ticketAccountMapper.listByCustomerAndStationWithDetail(customerId, effectiveStationId));
    }

    /**
     * 员工查询指定客户的水票（管理端）
     * GET /api/tickets/customer/{customerId}?stationId=xxx
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customer/{customerId}")
    public Result<List<Map<String, Object>>> listByCustomerIdForStaff(@PathVariable Long customerId, @RequestParam(required = false) Long stationId) {
        // stationId 以 JWT 当前站长所属水站为准，防跨站查询
        Long effectiveStationId = AuthContext.requireStationId();
        return Result.success(ticketAccountMapper.listByCustomerAndStationWithDetail(customerId, effectiveStationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/add")
    public Result add(@RequestBody TicketAddDTO dto) {
        if (dto.getCustomerId() == null) {
            return Result.error("客户ID不能为空");
        }
        // stationId 以 JWT 当前站长所属水站为准，禁止信任请求体（防跨站刷水票）
        Long stationId = AuthContext.requireStationId();
        ticketAccountService.addTicket(dto.getCustomerId(), dto.getProductId(), dto.getQuantity(), stationId);
        return Result.success();
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/consume")
    public Result consume(@RequestBody TicketConsumeDTO dto) {
        if (dto.getCustomerId() == null) {
            return Result.error("客户ID不能为空");
        }
        // stationId 以 JWT 当前站长所属水站为准，禁止信任请求体（防跨站扣水票）
        Long stationId = AuthContext.requireStationId();
        ticketAccountService.consumeTicket(dto.getCustomerId(), dto.getProductId(), dto.getQuantity(), dto.getOrderId(), stationId);
        return Result.success();
    }

    /**
     * 客户线上购买水票
     * POST /api/tickets/purchase  { productId, quantity, paymentMethod, stationId, idempotencyKey }
     *
     * <p><b>idempotencyKey 必填</b>：本端点是无订单支付（{@code order_id} 为 NULL），
     * 数据库唯一键 {@code uk_payment_active_order} 建在生成列 {@code active_order_id} 上，
     * order_id 为 NULL 时生成列也是 NULL，而 MySQL 唯一键中 NULL 互不冲突 —— 也就是这条路径
     * 没有任何数据库级兜底。缺了幂等键，连点两次「买 100 张票」就会落两条待收款流水，
     * 站长在「待确认收款」里看到两行、两条都确认即<b>入账两次</b>。见 v33 迁移头注释。</p>
     */
    @PostMapping("/purchase")
    public Result<java.util.Map<String, Object>> purchase(@RequestBody TicketPurchaseDTO dto) {
        Long customerId = AuthContext.requireCustomerId();
        // customerId 一律取自登录态，禁止信任请求体（此处连字段都不在 DTO 里）
        if (dto.getProductId() == null || dto.getQuantity() == null) {
            return Result.error("参数不完整");
        }
        if (dto.getStationId() == null) {
            return Result.error("stationId 不能为空，请先选择服务水站");
        }
        if (dto.getIdempotencyKey() == null || dto.getIdempotencyKey().trim().isEmpty()) {
            return Result.error("缺少幂等键 idempotencyKey");
        }
        Long stationId = dto.getStationId();
        com.example.aquaflow.entity.PaymentRecord pr = ticketAccountService.purchaseTicket(
                customerId, dto.getProductId(), dto.getQuantity(), dto.getPaymentMethod(), stationId,
                dto.getIdempotencyKey(), dto.getPackageId(), dto.getUnifiedQty());
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("paymentId", pr.getId());
        result.put("amount", pr.getAmount());
        result.put("status", pr.getStatus());
        return Result.success(result);
    }
}