package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
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

    /** 创建支付记录 */
    @PostMapping
    public Result<PaymentRecord> create(@RequestBody Map<String, Object> body) {
        Long orderId = Long.valueOf(body.get("orderId").toString());
        Long customerId = Long.valueOf(body.get("customerId").toString());
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        BigDecimal waterAmount = body.get("waterAmount") != null ? new BigDecimal(body.get("waterAmount").toString()) : BigDecimal.ZERO;
        BigDecimal barrelDeposit = body.get("barrelDeposit") != null ? new BigDecimal(body.get("barrelDeposit").toString()) : BigDecimal.ZERO;
        Integer excessBarrels = body.get("excessBarrels") != null ? Integer.valueOf(body.get("excessBarrels").toString()) : 0;
        Integer paymentMethod = Integer.valueOf(body.get("paymentMethod").toString());
        Long ticketWaterTypeId = body.get("ticketWaterTypeId") != null ? Long.valueOf(body.get("ticketWaterTypeId").toString()) : null;
        Integer ticketQty = body.get("ticketQty") != null ? Integer.valueOf(body.get("ticketQty").toString()) : null;
        String note = (String) body.get("note");
        return Result.success(paymentService.createPayment(orderId, customerId, amount, waterAmount, barrelDeposit, excessBarrels, paymentMethod, ticketWaterTypeId, ticketQty, note));
    }

    /** 确认支付 */
    @PutMapping("/{id}/confirm")
    public Result confirm(@PathVariable Long id) {
        paymentService.confirmPayment(id);
        return Result.success();
    }

    /** 货到付款确认 */
    @PostMapping("/cash-confirm")
    public Result confirmCash(@RequestBody Map<String, Object> body) {
        Long orderId = Long.valueOf(body.get("orderId").toString());
        Long customerId = Long.valueOf(body.get("customerId").toString());
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        paymentService.confirmCashPayment(orderId, customerId, amount);
        return Result.success();
    }

    /** 查询订单支付记录 */
    @GetMapping("/by-order")
    public Result<List<PaymentRecord>> listByOrderId(@RequestParam Long orderId) {
        return Result.success(paymentService.listByOrderId(orderId));
    }

    /** 查询客户支付记录（客户自己） */
    @GetMapping("/by-customer")
    public Result<List<PaymentRecord>> listByCustomerId() {
        Integer customerId = AuthContext.requireCustomerId();
        return Result.success(paymentService.listByCustomerId(Long.valueOf(customerId)));
    }

    /** 查询指定客户支付记录（员工管理端） */
    @GetMapping("/customer/{customerId}")
    public Result<List<PaymentRecord>> listByCustomerIdForStaff(@PathVariable Integer customerId) {
        AuthContext.requireStaffRole();
        return Result.success(paymentService.listByCustomerId(Long.valueOf(customerId)));
    }

    /** 查询所有支付记录（管理端） */
    @GetMapping("/all")
    public Result<List<PaymentRecord>> listAll(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(paymentService.listAll(limit));
    }

    /** 退款 */
    @PutMapping("/{id}/refund")
    public Result refund(@PathVariable Long id, @RequestBody Map<String, String> body) {
        paymentService.refundPayment(id, body.get("note"));
        return Result.success();
    }

    /** 获取站点支付配置 */
    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig(@RequestParam(defaultValue = "0") Long stationId) {
        return Result.success(paymentService.getStationConfig(stationId));
    }

    /** 更新站点支付配置 */
    @PutMapping("/config")
    public Result updateConfig(@RequestBody Map<String, Object> body) {
        Long stationId = body.get("stationId") != null ? Long.valueOf(body.get("stationId").toString()) : null;
        paymentService.updateStationConfig(stationId, body);
        return Result.success();
    }
}
