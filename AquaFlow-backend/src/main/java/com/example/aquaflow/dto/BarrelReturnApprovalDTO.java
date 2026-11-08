package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

/** 站长明确取桶服务报价；押金金额仍按预留批次推导。 */
@Data
public class BarrelReturnApprovalDTO {
    @NotNull(message="上门收桶费不能为空") @Digits(integer=5,fraction=2, message="上门收桶费格式不正确，请核对金额位数") @DecimalMin(value="0.00", message="上门收桶费不能小于0.00") @DecimalMax(value="10000.00", message="上门收桶费不能超过10000.00") private BigDecimal pickupFee;
    @Size(max=200, message="备注最多 200 字") private String note;
    @Min(value=1, message="原记录已变化，请刷新后重试") private Integer expectedVersion;
}
