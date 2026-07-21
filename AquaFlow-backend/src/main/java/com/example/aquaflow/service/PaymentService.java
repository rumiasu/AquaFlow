package com.example.aquaflow.service;

import com.example.aquaflow.entity.PaymentRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface PaymentService {

    /** 创建支付记录 */
    PaymentRecord createPayment(Long orderId, Long customerId, BigDecimal amount, BigDecimal waterAmount,
                                BigDecimal barrelDeposit, Integer excessBarrels, Integer paymentMethod,
                                Long ticketWaterTypeId, Integer ticketQty, String note);

    /** 确认支付（微信回调/手动确认） */
    void confirmPayment(Long paymentId);

    /** 货到付款确认（配送员确认收到现金） */
    void confirmCashPayment(Long orderId, Long customerId, BigDecimal amount);

    /** 水票支付（锁定水票） */
    void lockTicketPayment(Long orderId, Long customerId, Long waterTypeId, int qty);

    /** 配送完成后扣减水票 */
    void deductTickets(Long orderId);

    /** 退款 */
    void refundPayment(Long paymentId, String note);

    /** 查询订单支付记录 */
    List<PaymentRecord> listByOrderId(Long orderId);

    /** 查询客户支付记录 */
    List<PaymentRecord> listByCustomerId(Long customerId);

    /** 查询所有支付记录（管理端） */
    List<PaymentRecord> listAll(int limit);

    /** 获取站点支付配置 */
    Map<String, Object> getStationConfig(Long stationId);

    /** 更新站点支付配置 */
    void updateStationConfig(Long stationId, Map<String, Object> config);
}
