package com.example.aquaflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * POST /api/barrels/return-empty 纯还桶请求体（对齐 miniapp {customerId, items:[{productId,qty}], clientToken, note}）。
 *
 * <p>customerId 由员工端代客录入（顾客不可自助还桶，防虚报）；clientToken 幂等，必填。</p>
 */
@Data
public class BarrelReturnEmptyDTO {

    @NotNull(message = "客户不能为空")
    private Long customerId;

    @NotNull(message = "缺少幂等 token")
    private String clientToken;

    @NotEmpty(message = "请填写还桶明细")
    @Valid
    private List<BarrelReturnEmptyItemDTO> items;

    private String note;

    @Data
    public static class BarrelReturnEmptyItemDTO {

        @NotNull(message = "还桶明细必须包含商品")
        private Long productId;

        @NotNull(message = "还桶明细必须包含数量")
        private Integer qty;
    }
}
