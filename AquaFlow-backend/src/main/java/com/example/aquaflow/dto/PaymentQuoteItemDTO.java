package com.example.aquaflow.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 支付试算（quote）的单项商品，对齐 miniapp 实际发送字段 {productId, quantity} */
@Data
public class PaymentQuoteItemDTO {

    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少 1")
    private Integer quantity;
}
