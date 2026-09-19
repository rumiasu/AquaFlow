package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水票流水实体类，对应数据库 ticket_record 表。
 * <p>记录客户水票的增减明细。</p>
 */
@Data
public class TicketRecord {

    /** 记录ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 商品ID(桶装水) */
    private Long productId;

    /**
     * 本次变动实际落在**哪个水票账户**（v54，统一水票）。
     *
     * <p>为什么不复用 {@link #productId}：消费流水必须记<b>订单行上的商品</b>，
     * 因为幂等键 {@code uk_ticket_consume(order_id, product_id, source)} 与
     * {@code countConsumeByOrderAndProduct} 都按它判重 —— 用统一票抵扣时若写 0，
     * 同一张单的第二条流水会撞唯一键被静默当成"已扣过"，<b>等于少扣票</b>。
     * 所以「订单行商品」与「扣哪个账户」必须分成两列。</p>
     *
     * <p>取值：{@code null} = 与 {@link #productId} 同账户（v54 之前的存量流水）；
     * {@code 0} = 站级通用票（统一水票）；其余 = 该商品的定制票。</p>
     *
     * <p>退款回补<b>必须</b>读它（{@code TicketAccountServiceImpl.refundTicket}）：
     * 退款那一刻余额早已变化，重新判定会算错账户。</p>
     */
    private Long accountProductId;

    /** 所属水站ID */
    private Long stationId;

    /** 增加数量 */
    private Integer increaseQty;

    /** 减少数量 */
    private Integer decreaseQty;

    /** 关联订单ID */
    private Long orderId;

    /** 来源说明 */
    private String source;

    /** 水票来源: 1 线上 2 线下 */
    private Integer ticketSource;

    /**
     * 本次变动的单价（v36）。购买 = 实付均价；消耗 = 所消耗批次的**加权均价**；
     * 退款回补 = 还原批次所用的单价。
     *
     * <p>为什么要落在流水上：档位意味着票价分段，光有"买了 100 张、收了 800 元"没法自证均价，
     * 更没法在订单取消时按**当时**的价把票还原回去。</p>
     */
    private java.math.BigDecimal unitPrice;

    /**
     * 关联的水票批次（v36）。仅当本次变动<b>只涉及一个批次</b>时有值；
     * 跨批次消耗时为 {@code null}，此时看 {@link #unitPrice} 的加权均价。
     */
    private Long ticketLotId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /**
     * 站长资产调整单 ID（station_adjustment.id），NULL=非调整产生。
     * <p>注意 uk_ticket_consume(order_id, product_id, source) 对调整记录<b>零保护</b>：
     * 调整场景 order_id 为 NULL，而 MySQL 唯一键中 NULL 互不冲突。
     * 调整的幂等由 uk_ticket_adjustment(adjustment_id, product_id, source) 承担。</p>
     */
    private Long adjustmentId;
}
