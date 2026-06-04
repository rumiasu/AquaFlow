package com.example.aquaflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class InventoryInboundDTO {
    private List<ItemDTO> items;

    @Data
    public static class ItemDTO {
        private Integer waterTypeId;
        private Integer quantity;
    }
}
