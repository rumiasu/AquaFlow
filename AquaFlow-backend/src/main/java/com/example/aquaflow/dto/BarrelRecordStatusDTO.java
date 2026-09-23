package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** PUT /api/barrels/records/{id}/status 退桶审批请求体 */
@Data
public class BarrelRecordStatusDTO {

    @NotNull(message = "status 不能为空")
    private Integer status;

    private String handleNote;
}
