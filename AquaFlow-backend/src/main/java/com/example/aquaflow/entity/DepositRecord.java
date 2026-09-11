package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 押金流水实体类，对应数据库 deposit_record 表。
 * <p>记录客户押金的新增、退还、赔偿等变动。</p>
 */
@Data
public class DepositRecord {

    /** 记录ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 涉及商品ID：桶权益按 (customer, station, product) 隔离，流水必须带商品维度 */
    private Long productId;

    /** 类型: 1 新增押金 2 退押金 3 丢桶赔偿 4 其他调整 */
    private Integer type;

    /** 金额 */
    private BigDecimal amount;

    /** 本次桶权益的买入单价快照（退款只认批次单价，此字段供对账/审计） */
    private BigDecimal unitPrice;

    /** 本次涉及桶数 */
    private Integer quantity;

    /** 关联订单ID */
    private Long relatedOrderId;

    /** 备注 */
    private String note;

    /** 操作员ID */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createTime;
}
