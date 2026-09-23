package com.example.aquaflow.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** PUT /api/payments/{id}/refund 请求体（仅备注，可选） */
@Data
public class PaymentRefundDTO {

    @Size(max = 500, message = "备注过长")
    private String note;
}
