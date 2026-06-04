package com.example.aquaflow.service;

import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;

import java.util.List;
import java.util.Map;

public interface InventoryService {
    List<Inventory> list();

    void inbound(List<InventoryInboundDTO.ItemDTO> items);

    void checkStock(Integer waterTypeId,Integer needQuantity);
}
