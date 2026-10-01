package com.example.aquaflow.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class InventoryLossDTO {
    @NotNull @Min(0) private Integer targetQuantity;
    @NotNull @Min(0) private Integer expectedQuantity;
    @NotBlank @Size(max=180) private String note;
}
