package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 顾客本人或归属站站长明确选择新安排；不接受资产数量、退款金额或服务费改写。 */
@Data
public class BarrelReturnArrangementDTO {
    @NotBlank @Pattern(regexp="STORE|PICKUP|COMBINED") private String pickupMode;
    private Long companionOrderId;
    @NotNull @Min(1) private Integer expectedVersion;
    @NotBlank @Size(max=64) private String idempotencyKey;
    @NotBlank @Size(max=200) private String reason;
}
