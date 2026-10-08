package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Only an object reference and optional note; identity/station/money are never accepted. */
@Data
public class RefundFeedbackDTO {
    @NotBlank private String refundType;
    @NotNull @Positive private Long refundId;
    @NotBlank @Size(max=64) private String idempotencyKey;
    @Size(max=1000) private String content;
    @Size(max=100) private String contact;
}
