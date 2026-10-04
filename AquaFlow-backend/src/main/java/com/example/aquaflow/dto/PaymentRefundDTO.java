package com.example.aquaflow.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** PUT /api/payments/{id}/refund：范围、金额确认与备注。 */
@Data
public class PaymentRefundDTO {
    /** 剩余票退出必须回传站长确认的数量与金额，防止预览后并发用票改变实退。 */
    @jakarta.validation.constraints.Min(1)
    private Integer expectedTicketQty;
    @jakarta.validation.constraints.DecimalMin("0.00")
    @jakarta.validation.constraints.Digits(integer=10,fraction=2)
    private java.math.BigDecimal expectedTicketAmount;
    /** 随单押金新凭据要求回传所选预览金额；历史无此凭据保持原路径。 */
    @jakarta.validation.constraints.DecimalMin(value="0.00",inclusive=false,message="确认退款金额须大于零")
    @jakarta.validation.constraints.Digits(integer=10,fraction=2)
    private java.math.BigDecimal expectedRefundAmount;
    /** WATER 默认只退水费；SERVICE 退配送/楼层费；ALL_CONSUMPTION 退剩余消费费用。 */
    @jakarta.validation.constraints.Pattern(regexp="WATER|SERVICE|ALL_CONSUMPTION",message="退款范围不合法")
    private String scope;

    @Size(max = 500, message = "备注过长")
    private String note;
}
