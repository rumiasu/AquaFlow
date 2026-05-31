package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class InventoryServiceImpl implements InventoryService {

    @Autowired
    private InventoryMapper inventoryMapper;

    @Override
    public List<Inventory> list() {
        return inventoryMapper.list();
    }

    @Override
    @Transactional
    public void inbound(List<Map<String, Integer>> items) {
        for (Map<String, Integer> item : items) {
            Integer waterTypeId = item.get("waterTypeId");
            Integer quantity = item.get("quantity");

            // 查询是否已有库存
            Inventory inventory = inventoryMapper.getByWaterTypeId(waterTypeId);

            if (inventory == null) {
                // 不存在，新增
                Inventory newInventory = new Inventory();
                newInventory.setWaterTypeId(waterTypeId);
                newInventory.setQuantity(quantity);
                newInventory.setUpdateTime(LocalDateTime.now());
                inventoryMapper.insert(newInventory);
            } else {
                // 存在，累加
                inventory.setQuantity(inventory.getQuantity() + quantity);
                inventory.setUpdateTime(LocalDateTime.now());
                inventoryMapper.update(inventory);
            }
        }
    }

    public void checkStock(Integer waterTypeId,Integer needQuantity){
        Inventory inventory = inventoryMapper.getByWaterTypeId(waterTypeId);

        if (inventory == null || inventory.getQuantity()<needQuantity){
            throw new RuntimeException("库存不足");
        }
    }
}
