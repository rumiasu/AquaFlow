package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户按水站隔离的押金余额实体类，对应数据库 customer_deposit_account 表。
 * <p>记录客户在每个水站的押金余额，A站押金不能用于B站。</p>
 */
@Data
public class CustomerDepositAccount {

    /** ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 押金余额 */
    private BigDecimal balance;

    /** 最后修改时间 */
    private LocalDateTime updateTime;
}
