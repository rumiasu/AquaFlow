package com.example.aquaflow.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 独立押金购买凭据；实物交付不改变已确认的支付和买入单价。 */
@Data
public class BarrelRightPurchase {
    public String getStatusText() { return "PAID".equals(status)?"已确认押金":"CANCELLED".equals(status)?"已撤回，未收款":"等待水站收款确认"; }
    private Long id;
    private Long customerId;
    private Long stationId;
    private Long productId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private Long paymentId;
    private Long lotId;
    private String idempotencyKey;
    private String status;
    private LocalDateTime createTime;
}
