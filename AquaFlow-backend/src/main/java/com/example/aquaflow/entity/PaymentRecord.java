package com.example.aquaflow.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaymentRecord {
    private Long id;
    private Long orderId;
    private Long customerId;
    private BigDecimal amount;
    /** 水费金额 */
    private BigDecimal waterAmount;
    /** 桶押金金额 */
    private BigDecimal barrelDeposit;
    /** 超出桶数 */
    private Integer excessBarrels;
    /** 支付方式: 1=微信 2=现金 3=水票 4=挂账 */
    private Integer paymentMethod;
    /** 水票支付时关联的水类型ID */
    private Long ticketWaterTypeId;
    /** 水票支付张数 */
    private Integer ticketQty;
    /** 状态: 1=待支付 2=已支付 3=已退款 4=已取消 */
    private Integer status;
    private String note;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
