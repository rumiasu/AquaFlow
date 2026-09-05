package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录实体类，对应数据库 payment_record 表。
 * <p>记录订单的支付信息。</p>
 */
@Data
public class PaymentRecord {

    /** 记录ID，主键自增 */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 支付金额 */
    private BigDecimal amount;

    /** 水费金额 */
    private BigDecimal waterAmount;

    /** 桶押金金额 */
    private BigDecimal barrelDeposit;

    /** 超出桶数 */
    private Integer excessBarrels;

    /** 支付方式: 1 微信支付 2 水票 3 线下支付 */
    private Integer paymentMethod;

    /** 状态: 1 待支付 2 已支付 3 已退款 4 已取消 */
    private Integer status;

    /** 交易流水号 */
    private String transactionNo;

    /** 操作员ID(配送员确认线下支付) */
    private Long operatorId;

    /** 备注 */
    private String note;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
