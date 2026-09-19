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

    /**
     * 该客户在本站、该商品的**定制票**余额（没有账户时返回 0）。
     *
     * <p>统一水票的判据要用它：余额 &gt; 0 = 有定制票 → 定制优先（见 {@code util/TicketScope}）。</p>
     */
    int balanceOf(Long customerId, Long productId, Long stationId);

    /**
     * 本站是否配了**上架的统一水票档位**（{@code ticket_package.product_id = 0}）。
     *
     * <p>这就是统一水票的"站级开关" —— 产品口径是"**统一水票是可以设置项**"，
     * 不必再加一个开关列：配了档位 = 开通，全下架 = 关闭。</p>
     */
    boolean unifiedTicketConfigured(Long stationId);

    /**
     * 这一行该从哪个账户扣票（**唯一判据**，内部委托 {@code util/TicketScope}）。
     *
     * @return 账户商品 id：定制票 → 该商品 id；统一票 → 0；都不能用 → {@code null}
     */
    Long resolveDeductAccount(Long customerId, Long productId, Long stationId);

    /**
     * 站长手工加票 / 在线购票之外的人工入账。{@code productId = 0} = 给客户补**统一水票**（站级通用票）。
     *
     * <p>没有真实付款，故单价按 {@code TicketAccountServiceImpl.inferredUnitPrice} 推断
     * （统一票取本站上架档位的最低均价）并标记为推断值 —— 退票需二次确认。</p>
     */
    void addTicket(Long customerId, Long productId, Integer qty, Long stationId);

    /**
     * 从该客户在本站的水票里扣 {@code qty} 张，用于商品 {@code productId}。
     *
     * <p><b>账户由 {@link #resolveDeductAccount} 选</b>（判据在 {@code util/TicketScope}）：
     * 该商品有定制票余额 → 扣定制；没有且本站配了统一票、商品是桶装水 → 扣站级通用票。
     * 所以**两条调用路径都受这条规则约束**：订单支付（{@code PaymentServiceImpl.deductTickets}）
     * 与站长手工扣票（{@code POST /api/tickets/consume}）——
     * 后者在客户只有统一票时会扣统一票，这是刻意的（"该客户手上能抵这件商品的票"），
     * 不是漏判；界面上若要按商品展示余额，请分别展示定制与统一两个账户。</p>
     */
    void consumeTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId);

    /** 退款归还水票：回补客户水票账户余额并记一条"退款"流水（AQ-008） */
    void refundTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId);

    /**
     * 在线购票**支付确认后**入账（v36）。
     *
     * <p>与 {@link #addTicket} 的关键差别：单价取<b>实付均价</b>（实付金额 ÷ 张数），
     * 而不是站级水票价。档位套餐下这两者不同 —— 客户买 100 张按档位价付了 800 元，
     * 批次单价就该是 8.00；用站级单张价 9.00 记，退票时就会多退给客户钱。</p>
     *
     * @param paidAmount 实际收到的金额（{@code payment_record.amount}）
     */
    void creditPurchasedTickets(Long customerId, Long productId, Integer qty, Long stationId,
                                Long paymentRecordId, java.math.BigDecimal paidAmount);

    /**
     * 客户线上购买水票：生成待支付流水（无订单），支付确认后再入账水票。
     *
     * <p><b>idempotencyKey 必传</b>（2026-09-17 / v33）。这条路径是「无订单支付」，
     * {@code order_id} 为 NULL，因此：
     * <ul>
     *   <li>{@code PaymentServiceImpl.createPayment} 的存在性检查（包在
     *       {@code if (orderId != null)} 里）整段跳过；</li>
     *   <li>数据库唯一键 {@code uk_payment_active_order} 建在生成列 {@code active_order_id} 上，
     *       order_id 为 NULL 时生成列也是 NULL，<b>MySQL 唯一键中 NULL 互不冲突 → 零保护</b>。</li>
     * </ul>
     * 没有幂等键时连点两次会落两条待收款流水，站长两次确认即<b>入账两次水票</b>
     * （{@code confirmPayment} 的乐观锁只保证单条流水确认一次，管不住重复流水）。
     * 之所以设成必传而不是可选：「可选」等于默认没有保护，而漏传的代价是真金白银。</p>
     *
     * @param idempotencyKey 客户端生成的幂等键（同一笔购买意图重试时复用同一个值）
     * @param packageId      水票档位ID（v36，可空）：传了则张数与总价以服务端档位配置为准，
     *                       不传则按散买单张价计费
     */
    com.example.aquaflow.entity.PaymentRecord purchaseTicket(Long customerId, Long productId, Integer qty,
                                                            Integer paymentMethod, Long stationId,
                                                            String idempotencyKey, Long packageId);

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
