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

    /**
     * 在线购买水票：所购水票标识（对应数据库 payment_record.ticket_water_type_id 列）。
     * 用于支付确认后自动入账水票，非购票支付为 null。
     */
    private Long ticketWaterTypeId;

    /** 在线购买水票：购买数量 */
    private Integer ticketQty;

    /** 水费金额 */
    private BigDecimal waterAmount;

    /** 桶押金金额 */
    private BigDecimal barrelDeposit;

    /** 超出桶数 */
    private Integer excessBarrels;

    /**
     * 支付方式：1 微信 2 现金（货到付款） 3 水票。
     * 以 {@link com.example.aquaflow.constant.PayMethod} 为准（本注释此前误写为 "2水票 3线下"）。
     */
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
