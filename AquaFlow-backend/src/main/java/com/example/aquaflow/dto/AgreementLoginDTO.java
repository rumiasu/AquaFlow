package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Exact versions displayed when the person clicked login; no actor, timestamp or processing-consent bool. */
@Data
public class AgreementLoginDTO {
    @NotBlank(message="用户协议版本不能为空") @Pattern(regexp="(customer|staff)-user-[0-9a-f]{64}", message="用户协议版本无效，请刷新后重试")
    private String termsVersionId;
    @NotBlank(message="隐私政策版本不能为空") @Pattern(regexp="(customer|staff)-privacy-[0-9a-f]{64}", message="隐私政策版本无效，请刷新后重试")
    private String privacyVersionId;
}
