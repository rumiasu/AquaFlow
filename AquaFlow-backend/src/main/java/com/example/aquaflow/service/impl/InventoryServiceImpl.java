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
    public List<Inventory> list(Integer stationId) {
        return inventoryMapper.listByStationId(stationId);
    }

    @Override
    public void checkStock(Integer stationId, Integer waterTypeId, Integer needQuantity) {
        Inventory inventory = inventoryMapper.getByStationAndWaterType(stationId, waterTypeId);
        if (inventory == null || inventory.getQuantity() < needQuantity) {
            throw new RuntimeException("水站[" + stationId + "]库存不足，需要:" + needQuantity);
        }
    }

    @Override
    @Transactional
    public void inbound(Integer stationId, List<InventoryInboundDTO.ItemDTO> items) {
        for (InventoryInboundDTO.ItemDTO item : items) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new RuntimeException("入库数量必须大于0");
            }
            Inventory existing = inventoryMapper.getByStationAndWaterType(stationId, item.getWaterTypeId());
            if (existing == null) {
                Inventory newInv = new Inventory();
                newInv.setStationId(stationId);
                newInv.setWaterTypeId(item.getWaterTypeId());
                newInv.setQuantity(item.getQuantity());
                inventoryMapper.insert(newInv);
            } else {
                inventoryMapper.increaseStock(stationId, item.getWaterTypeId(), item.getQuantity());
            }
        }
    }
}
