package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 顾客提出异议或责任站结案；对象仍由原申请/原款判权。 */
@Data @EqualsAndHashCode(callSuper=true)
public class RefundDisputeDTO extends ExceptionCloseoutDTO {
    @NotBlank private String refundType;
    @NotNull @Positive private Long refundId;
}
