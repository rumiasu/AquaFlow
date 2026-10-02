package com.example.aquaflow.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** POST /api/barrels/return 退桶申请请求体（对齐 miniapp {productId, quantity, note}；stationId 可空，由登录态推导） */
@Data
public class BarrelReturnRequestDTO {

    /** 不传则取当前客户登录水站 */
    private Long stationId;

    @NotNull(message = "商品不能为空")
    private Long productId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少 1")
    private Integer quantity;

    @Size(max=200, message="备注最多 200 字")
    private String note;
    private String idempotencyKey;
    /** STORE到店 / PICKUP独立上门 / COMBINED随订单；由站长批准明确费用。 */
    private String pickupMode;
    private Long companionOrderId;
}
