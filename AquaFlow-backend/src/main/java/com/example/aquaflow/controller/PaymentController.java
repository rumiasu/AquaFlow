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
        return Result.success(paymentService.quote(customerId, dto.getStationId(), dto.getPaymentMethod(), itemMaps));
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

    /** 退款 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/refund")
    public Result refund(@PathVariable Long id, @RequestBody @Valid PaymentRefundDTO dto) {
        Result<Void> check = requirePaymentOrderStation(id);
        if (check != null) return check;
        paymentService.refundPayment(id, dto.getNote());
        return Result.success();
    }

    /**
     * 获取本站支付配置（站长端）。
     * <p>原先声明了 {@code stationId} 入参却紧接着用登录态覆盖，调用方传什么都不生效，
     * 顾客调用还会得到"当前账号未绑定水站"这种误导性报错。这里直接去掉该无效入参，
     * 客户端的支付方式与可用性改由 /api/payments/quote 的 methods 字段下发。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig() {
        return Result.success(paymentService.getStationConfig(AuthContext.requireStationId()));
    }

    /** 更新站点支付配置 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/config")
    public Result updateConfig(@RequestBody Map<String, Object> body) {
        body.put("stationId", AuthContext.requireStationId());
        Long stationId = body.get("stationId") != null ? Long.valueOf(body.get("stationId").toString()) : null;
        paymentService.updateStationConfig(stationId, body);
        return Result.success();
    }
}
