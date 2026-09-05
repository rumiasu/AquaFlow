package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.StationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    /** 校验支付单对应订单属于本站履约，否则返回错误 */
    private Result<Void> requirePaymentOrderStation(Long paymentId) {
        PaymentRecord p = paymentRecordMapper.getById(paymentId);
        if (p == null || p.getOrderId() == null) return Result.error("支付记录不存在");
        return requireOrderStation(p.getOrderId());
    }

    /** 服务端支付试算（下单前展示，金额以服务端为准） */
    @PostMapping("/quote")
    public Result<Map<String, Object>> quote(@RequestBody Map<String, Object> body) {
        Long customerId = AuthContext.requireCustomerId();
        Long stationId = body.get("stationId") != null ? Long.valueOf(body.get("stationId").toString()) : null;
        Integer paymentMethod = body.get("paymentMethod") != null ? Integer.valueOf(body.get("paymentMethod").toString()) : null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        return Result.success(paymentService.quote(customerId, stationId, paymentMethod, items));
    }

    /** 创建支付记录（金额/新增桶数一律以服务端重算为准） */
    @PostMapping
    public Result<PaymentRecord> create(@RequestBody Map<String, Object> body) {
        Long orderId = Long.valueOf(body.get("orderId").toString());

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

        BigDecimal amount = body.get("amount") != null ? new BigDecimal(body.get("amount").toString()) : BigDecimal.ZERO;
        BigDecimal waterAmount = body.get("waterAmount") != null ? new BigDecimal(body.get("waterAmount").toString()) : BigDecimal.ZERO;
        BigDecimal barrelDeposit = body.get("barrelDeposit") != null ? new BigDecimal(body.get("barrelDeposit").toString()) : BigDecimal.ZERO;
        Integer excessBarrels = body.get("excessBarrels") != null ? Integer.valueOf(body.get("excessBarrels").toString()) : 0;
        Integer paymentMethod = Integer.valueOf(body.get("paymentMethod").toString());
        Long ticketProductId = body.get("ticketProductId") != null ? Long.valueOf(body.get("ticketProductId").toString()) : null;
        Integer ticketQty = body.get("ticketQty") != null ? Integer.valueOf(body.get("ticketQty").toString()) : null;
        String note = (String) body.get("note");
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
        return Result.success(paymentService.listByCustomerId(customerId));
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
    public Result refund(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Result<Void> check = requirePaymentOrderStation(id);
        if (check != null) return check;
        paymentService.refundPayment(id, body.get("note"));
        return Result.success();
    }

    /** 获取站点支付配置 */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig(@RequestParam(required = false) Long stationId) {
        stationId = AuthContext.requireStationId();
        return Result.success(paymentService.getStationConfig(stationId));
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
