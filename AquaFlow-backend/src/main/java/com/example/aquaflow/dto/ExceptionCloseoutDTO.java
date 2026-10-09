package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 异常动作只收版本、请求键与理由；身份、站别、金额不由客户端提供。 */
@Data
public class ExceptionCloseoutDTO {
    @NotNull @PositiveOrZero private Long expectedVersion;
    @NotBlank @Size(max=64) private String idempotencyKey;
    @NotBlank @Size(max=1000) private String reason;
}
