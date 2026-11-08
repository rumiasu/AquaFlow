package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 顾客本人或归属站站长明确选择新安排；不接受资产数量、退款金额或服务费改写。 */
@Data
public class BarrelReturnArrangementDTO {
    @NotBlank(message="收桶方式不能为空") @Pattern(regexp="STORE|PICKUP|COMBINED", message="收桶方式无效，请重新选择") private String pickupMode;
    private Long companionOrderId;
    @NotNull(message="原记录已变化，请刷新后重试") @Min(value=1, message="原记录已变化，请刷新后重试") private Integer expectedVersion;
    @NotBlank(message="提交编号无效，请刷新后重试") @Size(max=64, message="提交编号无效，请刷新后重试") private String idempotencyKey;
    @NotBlank(message="办理理由不能为空") @Size(max=200, message="办理理由最多200字") private String reason;
}
