package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Exact versions displayed when the person clicked login; no actor, timestamp or processing-consent bool. */
@Data
public class AgreementLoginDTO {
    @NotBlank @Pattern(regexp="(customer|staff)-user-[0-9a-f]{64}")
    private String termsVersionId;
    @NotBlank @Pattern(regexp="(customer|staff)-privacy-[0-9a-f]{64}")
    private String privacyVersionId;
}
