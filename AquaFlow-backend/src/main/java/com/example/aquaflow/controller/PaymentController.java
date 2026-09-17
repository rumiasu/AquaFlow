package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.dto.PaymentCreateDTO;
import com.example.aquaflow.dto.PaymentQuoteDTO;
import com.example.aquaflow.dto.PaymentQuoteItemDTO;
import com.example.aquaflow.dto.PaymentRefundDTO;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.StationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

/**
 * 支付接口 —— <b>顾客端与站长端混装在同一个 {@code /api/payments} 前缀下</b>，靠逐个方法的注解区分。
 *
 * <p><b>顾客侧</b>（无注解 + {@code requireCustomerId()}）：{@code /quote} 试算、
 * {@code POST /api/payments} 发起支付、{@code /by-customer} 查自己的流水。
 * <b>站长侧</b>（{@code STATION_MANAGER}）：确认收款、现金确认、退款、配置、各类列表。</p>
 *
 * <p>资金口径：支付状态的唯一真值是 {@code orders.payment_status}；
 * 退款的<b>唯一入口</b>是 {@code PaymentService.refundOrder}（内含"已完成/已取消不得再取消"的
 * 状态门槛，以及退水票→退流水→退押金→清配送中桶→回补库存的完整编排）。
 * <b>不要在本类另写一套退款逻辑</b> —— 历史上抄漏步骤导致过"订单已取消但钱票没退"。</p>
 */
@RestController
@RequestMapping("/api/payments")
@Slf4j
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private CustomerMapper customerMapper;

    /** 校验订单属于本站履约，否则返回错误 */
    private Result<Void> requireOrderStation(Long orderId) {
        if (orderId == null) return Result.error("缺少订单ID");
        Orders o = orderMapper.getById(orderId);
        if (o == null) return Result.error("订单不存在");
        if (!AuthContext.requireStationId().equals(StationUtil.deliveryStation(o))) {
            return Result.error("无权操作他站订单");
        }
        return null;
    }

    /**
     * 校验支付单归属本站，否则返回错误。
     * <p>站内购买（如线上买水票）产生的支付记录没有关联订单（order_id 为空）。
     * 旧实现对此直接返回「支付记录不存在」，导致水票购买后任何人都无法确认入账 ——
     * 客户付了钱、票永远不到账，且没有任何补救入口。这里改为按水站归属校验。</p>
     */
    private Result<Void> requirePaymentOrderStation(Long paymentId) {
        PaymentRecord p = paymentRecordMapper.getById(paymentId);
        if (p == null) return Result.error("支付记录不存在");
        if (p.getOrderId() == null) {
            Long myStationId = AuthContext.getStationId();
            if (myStationId == null || p.getStationId() == null || !myStationId.equals(p.getStationId())) {
                return Result.error("无权操作他站支付记录");
            }
            return null;
        }
        return requireOrderStation(p.getOrderId());
    }

    /** 服务端支付试算（下单前展示，金额以服务端为准） */
    @PostMapping("/quote")
    public Result<Map<String, Object>> quote(@RequestBody @Valid PaymentQuoteDTO dto) {
        Long customerId = AuthContext.requireCustomerId();
        List<Map<String, Object>> itemMaps = dto.getItems().stream().map(i -> {
            Map<String, Object> m = new HashMap<>();
            m.put("productId", i.getProductId());
            m.put("quantity", i.getQuantity());
            return m;
        }).collect(Collectors.toList());
        return Result.success(paymentService.quote(customerId, dto.getStationId(), dto.getPaymentMethod(), itemMaps,
                dto.getAddressId()));
    }

    /** 创建支付记录（金额/新增桶数一律以服务端重算为准） */
    @PostMapping
    public Result<PaymentRecord> create(@RequestBody @Valid PaymentCreateDTO dto) {
        Long orderId = dto.getOrderId();

        // 客户调用时：customerId 强制取自登录态，且订单必须属于本人，防止越权修改他人余额/水票
        Long customerId;
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            return Result.error("订单不存在");
        }
        if ("customer".equals(AuthContext.getUserType())) {
            customerId = AuthContext.requireCustomerId();
            if (!customerId.equals(order.getCustomerId())) {
                return Result.error("无权操作他人订单");
            }
        } else {
            // 员工：仅站长可代录支付，且订单必须属于本人水站
            String role = AuthContext.getRole();
            if (!"STATION_MANAGER".equals(role) && !"manager".equals(role)) {
                return Result.error("权限不足");
            }
            if (!AuthContext.requireStationId().equals(StationUtil.deliveryStation(order))) {
                return Result.error("无权操作他站订单");
            }
            customerId = order.getCustomerId();
        }

        BigDecimal amount = dto.getAmount() != null ? dto.getAmount() : BigDecimal.ZERO;
        BigDecimal waterAmount = dto.getWaterAmount() != null ? dto.getWaterAmount() : BigDecimal.ZERO;
        BigDecimal barrelDeposit = dto.getBarrelDeposit() != null ? dto.getBarrelDeposit() : BigDecimal.ZERO;
        Integer excessBarrels = dto.getExcessBarrels() != null ? dto.getExcessBarrels() : 0;
        Integer paymentMethod = dto.getPaymentMethod();
        Long ticketProductId = dto.getTicketProductId();
        Integer ticketQty = dto.getTicketQty();
        String note = dto.getNote();
        return Result.success(paymentService.createPayment(orderId, customerId, amount, waterAmount, barrelDeposit, excessBarrels, paymentMethod, ticketProductId, ticketQty, note));
    }

    /** 确认支付 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/confirm")
    public Result confirm(@PathVariable Long id) {
        Result<Void> check = requirePaymentOrderStation(id);
        if (check != null) return check;
        paymentService.confirmPayment(id);
        return Result.success();
    }

    /** 现金收款确认 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/cash-confirm")
    public Result confirmCashById(@PathVariable Long id) {
        Result<Void> check = requirePaymentOrderStation(id);
        if (check != null) return check;
        paymentService.confirmPayment(id);
        return Result.success();
    }

    /** 查询订单支付记录 */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/by-order")
    public Result<List<PaymentRecord>> listByOrderId(@RequestParam Long orderId) {
        Result<Void> check = requireOrderStation(orderId);
        if (check != null) return Result.error(check.getMessage());
        return Result.success(paymentService.listByOrderId(orderId));
    }

    /** 查询客户支付记录（客户自己） */
    @GetMapping("/by-customer")
    public Result<List<PaymentRecord>> listByCustomerId() {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(paymentService.listByCustomerId(customerId));
    }

    /** 查询指定客户支付记录（员工管理端） */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customer/{customerId}")
    public Result<List<PaymentRecord>> listByCustomerIdForStaff(@PathVariable Long customerId, @RequestParam Long stationId) {
        // [AQ-023] 强制使用登录站长所属水站，忽略客户端传入的 stationId，杜绝跨站查询他站客户支付流水
        Long myStationId = AuthContext.requireStationId();
        return Result.success(paymentRecordMapper.listByCustomerAndStation(customerId, myStationId));
    }

    /** 查询所有支付记录（管理端，支持过滤） */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<PaymentRecord>> listAll(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer method,
            @RequestParam(defaultValue = "200") int limit) {
        return Result.success(paymentRecordMapper.listByStation(AuthContext.requireStationId(), status, method, limit));
    }

    /** 查询所有支付记录（管理端，备用） */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/all")
    public Result<List<PaymentRecord>> listAllBackup(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(paymentRecordMapper.listAllByStation(AuthContext.requireStationId(), limit));
    }

    /**
     * 待确认收款列表（站长端）。
     *
     * <p>同时覆盖两类此前<b>完全没有界面入口</b>的待确认收款：</p>
     * <ol>
     *   <li>订单现金/微信下单后生成的待收款流水；</li>
     *   <li>「线上买水票」产生的无订单 PENDING 流水 —— 微信支付渠道未接入，
     *       钱只能靠站长核对到账后手工确认，没有入口时顾客付了钱、水票永远不入账。</li>
     * </ol>
     * <p>确认动作复用 {@code PUT /api/payments/{id}/confirm}，其站别校验已支持无订单支付。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/pending")
    public Result<List<Map<String, Object>>> listPending(@RequestParam(defaultValue = "200") int limit) {
        int n = limit <= 0 ? 200 : Math.min(limit, 500);
        List<Map<String, Object>> rows = paymentRecordMapper.listPendingByStation(AuthContext.requireStationId(), n);
        // 本端点返回的是原始列 Map（非实体），派生 getter 不参与序列化，故在此显式补两条文案。
        // 文案真相源仍是常量类（PayMethod / PaymentStatus），**不在 SQL 里复制映射**，
        // 前端也不得自建映射表 —— 历史上两端各写一套导致展示与实际状态不符。
        for (Map<String, Object> r : rows) {
            r.put("methodText", com.example.aquaflow.constant.PayMethod.textOf(asInt(r.get("paymentMethod"))));
            r.put("statusText", com.example.aquaflow.constant.PaymentStatus.textOf(asInt(r.get("status"))));
        }
        return Result.success(rows);
    }

    /** Map 结果里的数值列可能来自不同数值类型，统一取 Integer */
    private static Integer asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    /** 退款 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/refund")
    public Result refund(@PathVariable Long id, @RequestBody @Valid PaymentRefundDTO dto) {
        Result<Void> check = requirePaymentOrderStation(id);
        if (check != null) return check;
        paymentService.refundPayment(id, dto.getNote());
        return Result.success();
    }

}
