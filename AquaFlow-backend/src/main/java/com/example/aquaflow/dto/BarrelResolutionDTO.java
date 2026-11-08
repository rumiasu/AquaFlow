package com.example.aquaflow.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
/** 实交数量来自配送流水；双方只能协商补桶方式和总金额，不能改写实物事实。 */
@Data
public class BarrelResolutionDTO {
    @NotBlank(message="桶交接方式不能为空") @Pattern(regexp="RETURN_EMPTY|SETTLE_BARREL", message="桶交接方式无效，请重新选择") private String barrelMode;
    @NotNull(message="桶折款金额不能为空") @Digits(integer=7,fraction=2, message="桶折款金额格式不正确，请核对金额位数") @DecimalMin(value="0.00", message="桶折款金额不能小于0.00") @DecimalMax(value="1000000.00", message="桶折款金额不能超过1000000.00") private BigDecimal barrelAmount;
    @NotBlank(message="说明不能为空") @Size(max=500, message="说明最多500字") private String note;
}
