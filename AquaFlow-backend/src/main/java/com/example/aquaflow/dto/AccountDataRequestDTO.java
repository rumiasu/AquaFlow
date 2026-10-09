package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request registration only; no subject/station ID, credentials, export destination or document uploads. */
@Data
public class AccountDataRequestDTO {
    @NotBlank @Pattern(regexp="ACCESS|CORRECTION|EXPORT|DELETION|CLOSURE",message="资料请求类型无效")
    private String requestType;
    @Size(max=500,message="补充说明最多500字") private String note;
    @NotBlank(message="提交编号不能为空") @Size(max=64,message="提交编号过长")
    @Pattern(regexp="[A-Za-z0-9._:-]{1,64}",message="提交编号格式无效")
    private String idempotencyKey;
}
