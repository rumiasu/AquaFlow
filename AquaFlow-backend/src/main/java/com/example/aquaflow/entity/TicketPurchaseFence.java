package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 永久请求编号封锁凭据；closedTime 不表示收款、退款或订单取消。 */
@Data
public class TicketPurchaseFence {
    private Long customerId;
    private String idempotencyKey;
    private LocalDateTime closedTime;
}
