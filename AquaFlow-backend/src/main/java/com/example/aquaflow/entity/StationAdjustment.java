package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 站长资产调整单（单据头）。
 *
 * <p>一次人工补录 / 订正 = 一张单据。单据头承载「谁、何时、为什么、改了前后什么值」，
 * 具体流水落在 {@code barrel_record} / {@code deposit_record} / {@code ticket_record}
 * （三者的 {@code adjustment_id} 回指本表）。设计依据：docs/design/10-站长资产调整单.md。</p>
 */
@Data
public class StationAdjustment {

    private Long id;

    /** 单据号 ADJyyyymmdd-000001 */
    private String adjustNo;

    /** 发起站 = 资产所属站；跨站一律拒绝 */
    private Long stationId;

    private Long customerId;

    /** 桶类 / 水票类调整必填 */
    private Long productId;

    /** 见 {@link com.example.aquaflow.constant.AdjustType} */
    private String adjustType;

    /** 桶数 / 水票数（绝对值，方向由 adjustType 决定） */
    private Integer qty;

    /** 押金金额（正数，方向由 adjustType 决定） */
    private BigDecimal amount;

    /** 补录单价快照（桶权益用）：决定客户将来退桶能退多少钱 */
    private BigDecimal unitPrice;

    /** 1订单实付 2当时商品押金 3当前商品押金(推断) */
    private Integer priceSource;

    /** 1=历史迁移（单价为推断，退款需二次确认） */
    private Integer isMigrated;

    /** 调整原因（必填） */
    private String reason;

    /** 证据图 objectName，逗号分隔 */
    private String evidence;

    private String beforeSnapshot;

    private String afterSnapshot;

    /** PENDING / EFFECTIVE / REVERSED / REJECTED */
    private String status;

    /** 客户端幂等键（唯一） */
    private String clientToken;

    /** 发起人（站长） */
    private Long operatorId;

    private Long executorId;

    /** 本单是反冲哪张单 */
    private Long reverses;

    /** 本单被哪张单反冲 */
    private Long reversedBy;

    private LocalDateTime executeTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
