package com.example.aquaflow.dto;

import lombok.Data;

@Data
public class TicketAddDTO {

    private Long customerId;

    private Long productId;

    private Integer quantity;

    private Long stationId;
}
