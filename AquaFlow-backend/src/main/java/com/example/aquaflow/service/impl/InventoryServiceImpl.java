package com.example.aquaflow.service.impl;

import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryServiceImpl implements InventoryService {

    @Autowired
    private InventoryMapper inventoryMapper;

    @Override
    public List<Inventory> list(Long stationId) {
        return inventoryMapper.listByStationId(stationId);
    }

    @Override
    public void checkStock(Long stationId, Long productId, Integer needQuantity) {
        Inventory inventory = inventoryMapper.getByStationAndProduct(stationId, productId);
        if (inventory == null || inventory.getQuantity() < needQuantity) {
            throw new RuntimeException("水站[" + stationId + "]库存不足，需要:" + needQuantity);
        }
    }

    @Override
    @Transactional
    public void inbound(Long stationId, List<InventoryInboundDTO.ItemDTO> items) {
        for (InventoryInboundDTO.ItemDTO item : items) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new RuntimeException("入库数量必须大于0");
            }
            inventoryMapper.upsertQuantity(stationId, item.getProductId(), item.getQuantity());
        }
    }
}
