package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

/** 站长明确取桶服务报价；押金金额仍按预留批次推导。 */
@Data
public class BarrelReturnApprovalDTO {
    @NotNull @Digits(integer=5,fraction=2) @DecimalMin("0.00") @DecimalMax("10000.00") private BigDecimal pickupFee;
    @Size(max=200, message="备注最多 200 字") private String note;
    @Min(1) private Integer expectedVersion;
}
