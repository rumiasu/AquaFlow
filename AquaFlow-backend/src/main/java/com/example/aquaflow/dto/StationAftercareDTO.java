package com.example.aquaflow.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class StationAftercareDTO {
    private Long expectedVersion;
    private String idempotencyKey;
    private BigDecimal amount;
    private String reason;
}
