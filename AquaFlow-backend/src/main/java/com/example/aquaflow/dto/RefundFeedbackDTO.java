package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Only an object reference and optional note; identity/station/money are never accepted. */
@Data
public class RefundFeedbackDTO {
    @NotBlank(message="退款类型不能为空") private String refundType;
    @NotNull(message="原退款记录未提供，请刷新后重试") @Positive(message="原退款记录无效，请刷新后重试") private Long refundId;
    @NotBlank(message="提交编号无效，请刷新后重试") @Size(max=64, message="提交编号无效，请刷新后重试") private String idempotencyKey;
    @Size(max=1000, message="补充说明最多1000字") private String content;
    @Size(max=100, message="联系方式最多100字") private String contact;
}
