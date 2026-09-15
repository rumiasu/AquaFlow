package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 调整单「试算」请求体：与创建同参数，但不落库。 */
@Data
public class AdjustmentPreviewDTO {

    @NotNull(message = "客户不能为空")
    private Long customerId;

    @NotBlank(message = "调整类型不能为空")
    private String adjustType;

    private Long productId;

    private Integer qty;

    private BigDecimal amount;

    private BigDecimal unitPrice;
}
