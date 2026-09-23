package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * PUT /api/customers/{id}/offline-payment 线下支付授权请求体。
 *
 * <p>对齐 miniapp {offlinePaymentEnabled:0|1}；原 Map 实现在缺字段时 NPE，
 * 这里用 @NotNull 把"授权标志必须存在"变成编译期可验证的契约。</p>
 */
@Data
public class CustomerOfflinePaymentDTO {

    @NotNull(message = "offlinePaymentEnabled 不能为空（0 或 1）")
    private Integer offlinePaymentEnabled;
}
