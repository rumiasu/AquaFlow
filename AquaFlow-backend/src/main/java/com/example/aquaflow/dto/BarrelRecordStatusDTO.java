package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** PUT /api/barrels/records/{id}/status 退桶审批请求体 */
@Data
public class BarrelRecordStatusDTO {

    @NotNull(message = "请选择处理结果")
    private Integer status;

    private String handleNote;
}
