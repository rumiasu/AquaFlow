package com.example.aquaflow.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/** v1沿用历史调用；安排变更后的确认必须携带客户实际看到的版本。 */
@Data
public class BarrelReturnConfirmationDTO {
    @Min(value=1, message="原记录已变化，请刷新后重试") private Integer expectedVersion;
}
