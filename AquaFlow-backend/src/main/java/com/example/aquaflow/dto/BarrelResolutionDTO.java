package com.example.aquaflow.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
/** 实交数量来自配送流水；双方只能协商补桶方式和总金额，不能改写实物事实。 */
@Data
public class BarrelResolutionDTO {
    @NotBlank @Pattern(regexp="RETURN_EMPTY|SETTLE_BARREL") private String barrelMode;
    @NotNull @Digits(integer=7,fraction=2) @DecimalMin("0.00") @DecimalMax("1000000.00") private BigDecimal barrelAmount;
    @NotBlank @Size(max=500) private String note;
}
