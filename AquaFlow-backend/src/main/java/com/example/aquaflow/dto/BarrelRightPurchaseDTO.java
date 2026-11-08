package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 顾客独立办理押金；顾客身份取登录态，金额不接受客户端输入。 */
@Data
public class BarrelRightPurchaseDTO {
    @NotNull(message="水站不能为空") private Long stationId;
    @NotNull(message="商品不能为空") private Long productId;
    @NotNull(message="数量不能为空") @Min(value=1, message="数量不能小于1") @Max(value=1000, message="数量不能超过1000") private Integer quantity;
    @NotNull(message="付款方式不能为空") @Min(value=1, message="付款方式无效，请重新选择") @Max(value=2, message="付款方式无效，请重新选择") private Integer paymentMethod;
    @NotBlank(message="提交编号无效，请刷新后重试") @Size(max = 64, message="提交编号无效，请刷新后重试") private String idempotencyKey;
}
