package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 桶权益批次（押金条），对应表 customer_barrel_lot。
 *
 * <h3>为什么必须有这张表</h3>
 * 「押金」在这个业务里的真实含义是<b>购买桶权益</b>：顾客付一笔钱，获得在该水站该桶型
 * 占用 N 个桶的永久权利，以后买水不用再买桶；彻底不用时可以退桶、终止权益、拿回当初那笔钱。
 * 因此系统必须记住<b>当初买入时的单价</b>——2026 年 30 元买的，2027 年涨价到 40 元，
 * 2027 年退也只能退 30 元。旧模型用「当前 product.deposit」重算退款，一调价就多退/少退。
 *
 * <p>本表是<b>金额的唯一真相源</b>：应退桶款 = Σ remain_qty × unit_price。
 * customer_barrel_asset 只保留数量与派生金额，不再自己算钱。</p>
 *
 * <h3>与「占用」的关系</h3>
 * 权益 ≠ 手里有几个桶。桶是流动实体，顾客可能买了 3 份权益但只拿走 1 个桶
 * （over = −2，多还桶 / 水站暂存）。见 {@link CustomerBarrelOver}。
 */
@Data
public class CustomerBarrelLot {

    private Long id;

    /** 押金条凭证号，如 DP20260911-000001 */
    private String lotNo;

    private Long customerId;
    private Long stationId;
    private Long productId;

    /** 买入当时单价快照（元/桶）。退款只认这个值，不认当前商品押金价。 */
    private BigDecimal unitPrice;

    /** 本批购买权益数 */
    private Integer qty;

    /** 剩余未退权益数（退桶时 FIFO 核销） */
    private Integer remainQty;

    /** 1订单购买 2历史迁移 3人工补录 */
    private Integer sourceType;

    /** 1订单实付 2当时商品押金 3当前商品押金(兜底推断) */
    private Integer priceSource;

    private Long relatedOrderId;
    private Long depositRecordId;

    /** 1有效 2已退完 3作废 */
    private Integer status;

    /** 1=历史迁移批次（单价为推断值，退款需二次确认） */
    private Integer isMigrated;

    private Long operatorId;
    private String note;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
