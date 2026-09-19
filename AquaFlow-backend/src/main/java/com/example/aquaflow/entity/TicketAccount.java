package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水票账户实体类，对应数据库 ticket_account 表。
 * <p>记录客户按商品维度的水票剩余数量。</p>
 *
 * <p><b>⚠️ {@code remainQuantity} 是派生汇总，不是真相源</b>：真相源是 {@code ticket_lot}
 * （{@code remain_quantity == Σ lot.remain_qty}），批次唯一写入口是 {@code TicketLotService}，
 * 对账 E8 校验这条等式。写本表前先问"批次动了吗"。</p>
 *
 * <p><b>[v54 统一水票]</b> {@code productId = 0} 表示<b>站级通用票</b>（见 {@code util/TicketScope}）——
 * 水票四张表的 {@code product_id} 都没有外键，所以这里能放一个不存在的商品 id。</p>
 */
@Data
public class TicketAccount {

    /** 账户ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 商品ID(桶装水)；{@code 0} = 站级通用票（统一水票），对照 {@code util/TicketScope} */
    private Long productId;

    /** 所属水站ID */
    private Long stationId;

    /** 剩余水票数量（派生：{@code Σ ticket_lot.remain_qty}，真相源在批次表） */
    private Integer remainQuantity;

    /**
     * 剩余水票的**金额价值**（派生列，v36）：{@code Σ lot.remain_qty × lot.unit_price}。
     *
     * <p>它同时是统一水票唯一的"面值"来源 —— 统一票没有商品、没有站级水票价，
     * {@code right_amount / remain_quantity} 就是它的加权均价。</p>
     */
    private java.math.BigDecimal rightAmount;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
