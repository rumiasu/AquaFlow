package com.example.aquaflow.service;

import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;

import java.util.List;
import java.util.Map;

public interface InventoryService {
    List<Inventory> list(Integer stationId);

    void inbound(Integer stationId, List<InventoryInboundDTO.ItemDTO> items);

    void checkStock(Integer stationId, Integer waterTypeId, Integer needQuantity);
}
