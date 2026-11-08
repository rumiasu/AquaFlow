package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AgreementAcknowledgementDTO {
    @NotBlank(message="协议类型不能为空") @Pattern(regexp="user|privacy", message="协议类型无效，请重新选择或刷新") private String type;
    @NotBlank(message="协议版本不能为空") @Pattern(regexp="(customer|staff)-(user|privacy)-[0-9a-f]{64}", message="协议版本无效，请重新选择或刷新") private String versionId;
}
