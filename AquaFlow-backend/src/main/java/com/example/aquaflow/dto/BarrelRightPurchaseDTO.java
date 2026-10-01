package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 顾客独立办理押金；顾客身份取登录态，金额不接受客户端输入。 */
@Data
public class BarrelRightPurchaseDTO {
    @NotNull private Long stationId;
    @NotNull private Long productId;
    @NotNull @Min(1) @Max(1000) private Integer quantity;
    @NotNull @Min(1) @Max(2) private Integer paymentMethod;
    @NotBlank @Size(max = 64) private String idempotencyKey;
}
