package com.example.aquaflow.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 异常动作只收版本、请求键与理由；身份、站别、金额不由客户端提供。 */
@Data
public class ExceptionCloseoutDTO {
    @NotNull(message="原记录已变化，请刷新后重试") @PositiveOrZero(message="原记录已变化，请刷新后重试") private Long expectedVersion;
    @NotBlank(message="提交编号无效，请刷新后重试") @Size(max=64, message="提交编号无效，请刷新后重试") private String idempotencyKey;
    @NotBlank(message="办理理由不能为空") @Size(max=1000, message="办理理由最多1000字") private String reason;
}
