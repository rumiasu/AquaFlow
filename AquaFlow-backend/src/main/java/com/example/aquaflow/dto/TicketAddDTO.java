package com.example.aquaflow.dto;

import lombok.Data;

@Data
public class TicketAddDTO {

    private Integer customerId;

    private Integer waterTypeId;

    private Integer quantity;
}
