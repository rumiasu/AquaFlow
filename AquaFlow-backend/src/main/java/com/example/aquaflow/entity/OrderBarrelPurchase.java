package com.example.aquaflow.entity;

import lombok.Data;
import java.math.BigDecimal;

/** 随水单补购的逐商品原款快照；付款前不代表有效权益。 */
@Data
public class OrderBarrelPurchase {
    private Long id;
    private Long orderId;
    private Long customerId;
    private Long stationId;
    private Long productId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private Long paymentId;
    private Long lotId;
    private Integer refundedQty;
    private BigDecimal refundedAmount;
    private String status;
}
