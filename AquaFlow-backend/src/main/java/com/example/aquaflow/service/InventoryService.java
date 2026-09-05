package com.example.aquaflow.service;

import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;

import java.util.List;

public interface InventoryService {
    List<Inventory> list(Long stationId);

    void inbound(Long stationId, List<InventoryInboundDTO.ItemDTO> items);

    void checkStock(Long stationId, Long productId, Integer needQuantity);
}
