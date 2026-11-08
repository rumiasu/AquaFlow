package com.example.aquaflow.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class InventoryLossDTO {
    @NotNull(message="盘亏后库存不能为空") @Min(value=0, message="盘亏后库存不能小于0") private Integer targetQuantity;
    @NotNull(message="原库存数量不能为空") @Min(value=0, message="原库存数量不能小于0") private Integer expectedQuantity;
    @NotBlank(message="说明不能为空") @Size(max=180, message="说明最多180字") private String note;
}
