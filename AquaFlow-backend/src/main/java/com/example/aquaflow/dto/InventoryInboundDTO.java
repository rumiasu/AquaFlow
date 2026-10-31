package com.example.aquaflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class InventoryInboundDTO {
    private List<ItemDTO> items;
    /** 原入库意图编号；旧客户端可不传，新客户端必须沿用原编号恢复未知结果。 */
    private String clientRequestId;

    @Data
    public static class ItemDTO {
        private Long productId;
        private Integer quantity;
        /** 选填本次进货总成本；NULL未知，0为实际零成本。 */
        private java.math.BigDecimal costAmount;
    }
}
