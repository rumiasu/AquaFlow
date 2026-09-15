package com.example.aquaflow.service;

import com.example.aquaflow.entity.PaymentRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 支付服务：下单试算、发起支付、收款确认、退款编排。
 *
 * <p><b>三条唯一性约定（改动前必读）：</b></p>
 * <ul>
 *   <li>支付状态的唯一真值是 {@code orders.payment_status}，本服务是它唯一的写方。</li>
 *   <li>{@code refundOrder} 是<b>全系统「取消订单 / 退款」的唯一编排入口</b>：
 *       退水票 → 退支付流水 → 退押金 → 清配送中桶 → 回补库存 → 置订单已取消，
 *       并带「已完成(4) / 已取消(5) 不得再取消」的状态门槛。
 *       客户取消、配送员拒单、站长解决/拒单、取消申请审批<b>全都汇到它</b>，不要另写一套
 *       —— 历史上各 Controller 各拼半套回滚，漏一步就留下"订单已取消但钱票没退"。</li>
 *   <li>金额与桶数一律由 {@code quote} 在<b>服务端</b>推导，不信任前端传入的任何数字。</li>
 * </ul>
 *
 * <p>渠道现状：微信支付未接入（{@code availableMethods()} 里该选项恒 disabled），
 * 当前可用的是现金（货到付款）与水票。</p>
 */
public interface PaymentService {

    /** 创建支付记录 */
    PaymentRecord createPayment(Long orderId, Long customerId, BigDecimal amount, BigDecimal waterAmount,
                                BigDecimal barrelDeposit, Integer excessBarrels, Integer paymentMethod,
                                Long ticketWaterTypeId, Integer ticketQty, String note);

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

    /**
     * 订单是否已有「已支付(PAID)」的支付流水。
     * [AQ-002][AQ-007] 完成配送时判定能否置「已付款」的唯一凭据 —— 杜绝配送员点一下"完成"就把
     * 未付款的微信单 / 未扣票的水票单变成已付款。
     */
    boolean hasPaidRecord(Long orderId);

    /**
     * 记录现金现场收款（货到付款），保证「订单已付款」必有对应支付流水。
     * 幂等：若该订单已存在 PAID 流水则直接返回，不重复记账。
     */
    void recordCashCollection(Long orderId);

    /**
     * [AQ-009] 订单支付成功时入账预收桶押金（幂等）。
     * <p>押金不再在下单时入账（那时顾客一分未付、且金额取自客户端可被放大），
     * 而是统一改到订单支付成功（payment_status -> PAID）时，按订单 deposit_amount 入账并写流水。
     * 幂等：同一订单仅入账一次（按 related_order_id + PREPAID 去重），
     * 可被多处"置已付款"入口（线上确认 / 现金收款 / 水票扣减）安全重复调用。</p>
     */
    void applyDepositOnPaid(Long orderId);

    /** 查询订单支付记录 */
    List<PaymentRecord> listByOrderId(Long orderId);

    /** 查询客户支付记录 */
    List<PaymentRecord> listByCustomerId(Long customerId);

    /** 查询所有支付记录（管理端）—— [AQ-052] 必须带 stationId，禁止全平台无过滤查询 */
    List<PaymentRecord> listAll(Long stationId, int limit);

    /** 查询支付记录（带过滤）—— [AQ-052] 必须带 stationId */
    List<PaymentRecord> listWithFilter(Long stationId, Integer status, Integer paymentMethod, int limit);

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
