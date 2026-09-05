package com.example.aquaflow.service;

import com.example.aquaflow.entity.PaymentRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface PaymentService {

    /** 创建支付记录 */
    PaymentRecord createPayment(Long orderId, Long customerId, BigDecimal amount, BigDecimal waterAmount,
                                BigDecimal barrelDeposit, Integer excessBarrels, Integer paymentMethod,
                                Long ticketProductId, Integer ticketQty, String note);

    /**
     * 服务端支付试算（quote）。
     * 按最终业务规则在服务端计算：单次上限、当前可持有桶、需新增押金桶、水费/押金/总金额。
     * 不信任前端传入的任何金额或桶数。
     */
    Map<String, Object> quote(Long customerId, Long stationId, Integer paymentMethod, List<Map<String, Object>> items);

    /** 确认支付（微信回调/手动确认） */
    void confirmPayment(Long paymentId);

    /**
     * 配送完成收款确认：线下订单标记已收款 → 订单真正完成（COMPLETED）。
     * 仅线下方式（现金/微信转账）可被确认；水票视同已付。
     */
    void confirmOrderCollection(Long orderId);

    /**
     * 收款修正（站长用）：已完成的线下订单改回「已送达待付款」（DELIVERED）。
     */
    void unconfirmOrderCollection(Long orderId);

    /** 货到付款确认（配送员确认收到现金） */
    void confirmCashPayment(Long orderId, Long customerId, BigDecimal amount);

    /** 水票支付（锁定水票，按站隔离） */
    void lockTicketPayment(Long orderId, Long customerId, Long productId, int qty, Integer orderStationId);

    /** 配送完成后扣减水票 */
    void deductTickets(Long orderId);

    /** 退款（单笔支付记录） */
    void refundPayment(Long paymentId, String note);

    /** 订单退款（取消订单触发）：对该订单所有已支付记录生成退款流水，更新订单状态 */
    void refundOrder(Long orderId, String reason);

    /** 查询订单支付记录 */
    List<PaymentRecord> listByOrderId(Long orderId);

    /** 查询客户支付记录 */
    List<PaymentRecord> listByCustomerId(Long customerId);

    /** 查询所有支付记录（管理端） */
    List<PaymentRecord> listAll(int limit);

    /** 查询支付记录（带过滤） */
    List<PaymentRecord> listWithFilter(Integer status, Integer paymentMethod, int limit);

    /** 获取站点支付配置 */
    Map<String, Object> getStationConfig(Long stationId);

    /** 更新站点支付配置 */
    void updateStationConfig(Long stationId, Map<String, Object> config);

    /**
     * 校验客户在指定水站是否有线下支付权限
     * 双开关：水站总开关 + 客户授权
     */
    boolean canUseOfflinePayment(Long customerId, Long stationId);
}
