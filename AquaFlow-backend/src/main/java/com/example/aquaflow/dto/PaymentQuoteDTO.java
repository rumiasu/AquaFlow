package com.example.aquaflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** POST /api/payments/quote 请求体，对齐 miniapp 实际发送 {items, paymentMethod, stationId} */
@Data
public class PaymentQuoteDTO {

    @NotNull(message = "请先选择服务水站")
    private Long stationId;

    @NotNull(message = "支付方式不能为空")
    @Min(value = 1, message = "支付方式非法")
    private Integer paymentMethod;

    @NotNull(message = "商品明细不能为空")
    @Size(min = 1, message = "至少包含一个商品")
    @Valid
    private List<PaymentQuoteItemDTO> items;
}
