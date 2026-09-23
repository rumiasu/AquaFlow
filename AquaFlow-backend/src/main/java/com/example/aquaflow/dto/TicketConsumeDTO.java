package com.example.aquaflow.dto;

import lombok.Data;

@Data
public class TicketConsumeDTO {

    private Long customerId;

    private Long productId;

    private Integer quantity;

    private Long orderId;

    /** 支付方式（购买时可选）：1=微信 2=现金 3=水票 */
    private Integer paymentMethod;

    /** 购买时指定的服务水站 */
    private Long stationId;
}
