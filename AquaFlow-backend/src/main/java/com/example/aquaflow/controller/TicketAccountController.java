package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.TicketAddDTO;
import com.example.aquaflow.dto.TicketConsumeDTO;
import com.example.aquaflow.dto.TicketPurchaseDTO;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
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

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private com.example.aquaflow.service.TicketPurchaseFenceService purchaseFenceService;

    /** 顾客自助结束未登记的请求编号，客户取自 AuthContext；已有款项只查回，不改付款状态。 */
    @PostMapping("/purchase-intent/close")
    public Result<Map<String, Object>> closePurchaseIntent(@RequestBody Map<String, String> body) {
        var closed = purchaseFenceService.closeUnregistered(AuthContext.requireCustomerId(), body.get("idempotencyKey"));
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("idempotencyKey", closed.idempotencyKey());
        result.put("closed", closed.closed());
        result.put("payment", closed.payment() == null ? null : purchaseResultOf(closed.payment()));
        return Result.success(result);
    }

    /** 只读查回原购票款；客户来自会话，不依赖当前商品/档位是否仍在售。 */
    @GetMapping("/purchase-result")
    public Result<java.util.Map<String, Object>> purchaseResult(@RequestParam String idempotencyKey) {
        Long customerId = AuthContext.requireCustomerId();
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty() || idempotencyKey.trim().length() > 64) {
            return Result.error("购买编号不正确");
        }
        var pr = ticketAccountService.findPurchaseResult(customerId, idempotencyKey.trim());
        if (pr == null) return Result.success(null);
        return Result.success(purchaseResultOf(pr));
    }

    private java.util.Map<String, Object> purchaseResultOf(com.example.aquaflow.entity.PaymentRecord pr) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("paymentId", pr.getId());
        result.put("amount", pr.getAmount());
        result.put("status", pr.getStatus());
        result.put("statusText", pr.getStatusText());
        result.put("stationId", pr.getStationId());
        result.put("productId", pr.getTicketWaterTypeId());
        result.put("quantity", pr.getTicketQty());
        result.put("paymentMethod", pr.getPaymentMethod());
        result.put("packageId", pr.getTicketPackageId());
        // 统一档张数必须随原款返回，不能把同张数的散买误认成原档位。
        String unifiedNote = "线上购买水票（站级统一折扣 " + pr.getTicketQty() + " 张档）";
        result.put("unifiedQty", unifiedNote.equals(pr.getNote()) ? pr.getTicketQty() : null);
        return result;
    }

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

    /**
     * 站长手工扣票
     * POST /api/tickets/consume  { customerId, productId, quantity, orderId?, idempotencyKey }
     *
     * <p><b>idempotencyKey 必传</b>（v70，台账 F-24）：{@code orderId} 可空，站长手工扣票时它就是
     * NULL，而唯一键 {@code uk_ticket_consume(order_id, product_id, source)} 在 {@code order_id IS NULL}
     * 时<b>零保护</b>（MySQL 唯一键中 NULL 互不冲突）—— 连点两次就把客户的票扣两次。
     * 缺键由 DTO 上的 {@code @NotBlank} 在 HTTP 边界拦下（本仓 {@code GlobalExceptionHandler}
     * 会把字段级 message 原样回给调用方，见 {@code handleBeanValidation}）。
     * 幂等判据与唯一键形状照抄 v33 的在线购票（{@code purchase}），见迁移 v70 头注释。</p>
     *
     * <p><b>唯一调用方是站长端</b>（{@code @RequireRole("STATION_MANAGER")}）；顾客端不能调。
     * 截至 v70，两端小程序**都没有调用本端点**（原计划的人工扣票入口尚未接）。
     * 与本任务无关的那条无订单扣票路径是「资产调整单」（{@code adjustTicket}），不在这里。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/consume")
    public Result consume(@RequestBody @Valid TicketConsumeDTO dto) {
        if (dto.getCustomerId() == null) {
            return Result.error("客户ID不能为空");
        }
        // stationId 以 JWT 当前站长所属水站为准，禁止信任请求体（防跨站扣水票）
        Long stationId = AuthContext.requireStationId();
        ticketAccountService.consumeTicket(dto.getCustomerId(), dto.getProductId(), dto.getQuantity(),
                dto.getOrderId(), stationId, dto.getIdempotencyKey());
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
     *
     * <p><b>[2026-09-26] 购票不是审批</b>（产品口径：「水票购买不需要水站同意，直接微信收款就行，
     * 现在只是没做收款实现而已」）：客户自己下单、自己付钱，水站只负责「收到钱」这个事实。
     * 因此微信购票在<b>模拟渠道</b>下当场确认（{@code confirmMockChannelIfApplicable}），
     * 返回的 {@code status} 就是流水的真实状态（2 = 已到账）；
     * 现金购票仍是待收款(1)，等站长确认收到钱后才入账 —— 那一步是收款确认，不是同意购买。</p>
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
        if (pr == null) {
            // 理论上不会有（purchaseTicket 要么返回流水要么抛业务异常），但不留一条能 NPE 成 500 的路。
            return Result.error("购票未成功，请重试");
        }
        // 模拟渠道下当场把收款确认掉，并把**重新读出的**流水回给客户端：
        // 直接回 purchaseTicket 返回的那个对象会带着"未确认"的旧状态，客户端就只能提示"等待到账"。
        // TODO(微信支付接入)：真实渠道到位后这里不再调它 —— 改为统一下单，由**支付回调**调 confirmPayment；
        //   本行与 PaymentService.confirmMockChannelIfApplicable 一起删（别留成"永真"的开关）。
        pr = paymentService.confirmMockChannelIfApplicable(pr.getId());
        return Result.success(purchaseResultOf(pr));
    }
}
