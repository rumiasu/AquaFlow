package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositDTO {

    private Integer customerId;

    private Integer type;

    private BigDecimal amount;

    private String note;

    private Long stationId;
}
