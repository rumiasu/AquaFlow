package com.example.aquaflow.service;

import com.example.aquaflow.entity.TicketAccount;

import java.util.List;

/**
 * 水票账户服务（余额查询、购买、加票 / 扣票）。
 *
 * <p><b>⚠️ 水票是唯一「下单即视同已付」的支付方式</b>，它<b>绕过</b> {@code confirmPayment}，
 * 因此<b>押金入账必须在本服务这条路径上自行补齐</b>：漏掉就会出现"客户用水票付了押金、
 * 押金账户却是 0、退桶时退不出钱"（详见 AGENTS.md §8 第 4 条）。</p>
 *
 * <p>水票余额的真相源是 {@code ticket_account.remain_quantity}（唯一键 {@code uk_customer_product_station}）。</p>
 */
public interface TicketAccountService {

    List<TicketAccount> listByCustomerAndStation(Long customerId, Long stationId);

    void addTicket(Long customerId, Long productId, Integer qty, Long stationId);

    void consumeTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId);

    /** 退款归还水票：回补客户水票账户余额并记一条"退款"流水（AQ-008） */
    void refundTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId);

    /** 客户线上购买水票：入账水票 + 生成支付记录（无订单） */
    com.example.aquaflow.entity.PaymentRecord purchaseTicket(Long customerId, Long productId, Integer qty, Integer paymentMethod, Long stationId);

    /**
     * 站长资产调整单专用：按 delta 调整水票余额（正=补录，负=扣减），并写带 adjustmentId 的流水。
     *
     * <p>与 {@link #addTicket} / {@link #consumeTicket} 的区别：</p>
     * <ul>
     *   <li>不依赖订单：{@code consumeTicket} 要求 orderId，调整场景没有订单；</li>
     *   <li>幂等：靠 {@code uk_ticket_adjustment(adjustment_id, product_id, source)} 兜底，
     *       重复执行同一张调整单会命中唯一键而失败（由调用方在同一事务内回滚），
     *       不像 {@code addTicket} 那样完全没有幂等键；</li>
     *   <li>扣减不足时显式失败，不静默跳过。</li>
     * </ul>
     *
     * @param delta 正数=补录，负数=扣减（0 或 null 视为非法）
     */
    void adjustTicket(Long customerId, Long productId, Integer delta, Long stationId, Long adjustmentId);
}
