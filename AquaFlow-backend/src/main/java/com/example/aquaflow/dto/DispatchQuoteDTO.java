package com.example.aquaflow.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class DispatchQuoteDTO {
    @NotNull @Digits(integer=7,fraction=2) @DecimalMin("0.00") @DecimalMax("1000000.00") private BigDecimal serviceAmount;
    @NotBlank @Pattern(regexp="RETURN_EMPTY|SETTLE_BARREL") private String barrelMode;
    @NotNull @Digits(integer=7,fraction=2) @DecimalMin("0.00") @DecimalMax("1000000.00") private BigDecimal barrelAmount;
    @Size(max=500) private String note;
}
