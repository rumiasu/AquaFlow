package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AgreementAcknowledgementDTO {
    @NotBlank @Pattern(regexp="user|privacy") private String type;
    @NotBlank @Pattern(regexp="(customer|staff)-(user|privacy)-[0-9a-f]{64}") private String versionId;
}
