package com.example.aquaflow.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/** 本案资产站核对的争议商品/金额；不表达免债、收款或延期。 */
@Data
public class RefusalFreezeScopeDTO {
    private Long expectedVersion;
    private String idempotencyKey;
    private String reason;
    private List<Line> items;
    @Data public static class Line { private Long productId; private BigDecimal amount; }
}
