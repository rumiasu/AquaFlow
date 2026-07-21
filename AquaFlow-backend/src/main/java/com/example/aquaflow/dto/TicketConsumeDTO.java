package com.example.aquaflow.dto;

import lombok.Data;

@Data
public class TicketConsumeDTO {

    private Integer customerId;

    private Integer waterTypeId;

    private Integer quantity;

    private Integer orderId;
}
