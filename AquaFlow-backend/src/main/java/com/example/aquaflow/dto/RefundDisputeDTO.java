package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 顾客提出异议或责任站结案；对象仍由原申请/原款判权。 */
@Data @EqualsAndHashCode(callSuper=true)
public class RefundDisputeDTO extends ExceptionCloseoutDTO {
    @NotBlank(message="退款类型不能为空") private String refundType;
    @NotNull(message="原退款记录未提供，请刷新后重试") @Positive(message="原退款记录无效，请刷新后重试") private Long refundId;
}
