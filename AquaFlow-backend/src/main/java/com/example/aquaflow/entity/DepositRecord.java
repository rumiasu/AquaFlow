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

    /** 类型: 1 新增押金 2 退押金 3 丢桶赔偿 4 其他调整 */
    private Integer type;

    /** 金额 */
    private BigDecimal amount;

    /** 关联订单ID */
    private Long relatedOrderId;

    /** 备注 */
    private String note;

    /** 操作员ID */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createTime;
}
