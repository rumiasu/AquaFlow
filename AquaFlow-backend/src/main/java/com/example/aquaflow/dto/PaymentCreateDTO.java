package com.example.aquaflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** POST /api/payments 创建支付记录请求体，对齐 miniapp 实际发送字段。
 *  注意：customerId 由服务端按登录态强取，客户端发送值一律忽略，故不在此 DTO 中。 */
@Data
public class PaymentCreateDTO {

    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    // 金额为可选项：服务端一律以订单金额重算，客户端不传时按 0 处理（与改造前行为一致）
    @DecimalMin(value = "0.00", message = "金额不能为负")
    private BigDecimal amount;

    @DecimalMin(value = "0.00", message = "水费不能为负")
    private BigDecimal waterAmount;

    @DecimalMin(value = "0.00", message = "桶押金不能为负")
    private BigDecimal barrelDeposit;

    @Min(value = 0, message = "超额桶数不能为负")
    private Integer excessBarrels;

    @NotNull(message = "支付方式不能为空")
    @Min(value = 1, message = "支付方式非法")
    private Integer paymentMethod;

    private Long ticketProductId;

    @Min(value = 1, message = "水票数至少 1")
    private Integer ticketQty;

    @Size(max = 255, message = "备注过长")
    private String note;
}
