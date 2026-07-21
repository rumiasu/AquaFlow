package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 押金记录实体类，对应数据库 deposit_record 表。
 * <p>记录客户押金的充值、退还等变动明细。</p>
 */
@Data
public class DepositRecord {

    /** 记录ID，主键自增 */
    private Integer id;

    /** 客户ID，关联 customer 表 */
    private Integer customerId;

    /** 押金类型：1=充值 2=退还 3=扣除 */
    private Integer type;

    /** 金额 */
    private BigDecimal amount;

    /** 备注说明 */
    private String note;

    /** 创建时间 */
    private LocalDateTime createTime;
}
