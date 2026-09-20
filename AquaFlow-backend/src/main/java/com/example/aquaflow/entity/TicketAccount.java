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
 * <p><b>[2026-09-20] 账户恒为"某一款商品"</b>：水票不再有"站级通用账户"这种形态 ——
 * 站级的「统一折扣」（{@code station_ticket_discount}）只是**买票时的定价规则**，
 * 按折扣买的票仍然进这一款水自己的账户（产品拍板：「只能抵那款水」）。
 * v54 曾把 {@code productId = 0} 当作"站级通用票"，该形态已由 v58/v59 收口。</p>
 */
@Data
public class TicketAccount {

    /** 账户ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 商品ID(桶装水) */
    private Long productId;

    /** 所属水站ID */
    private Long stationId;

    /** 剩余水票数量（派生：{@code Σ ticket_lot.remain_qty}，真相源在批次表） */
    private Integer remainQuantity;

    /**
     * 剩余水票的**金额价值**（派生列，v36）：{@code Σ lot.remain_qty × lot.unit_price}。
     *
     * <p>它是 {@code ticket_account} 的一个真实列，对账 E8 的金额等式按它校验
     * —— 不是仅供某个形态使用的临时字段。</p>
     */
    private java.math.BigDecimal rightAmount;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
