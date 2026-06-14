package com.example.aquaflow.service.impl;

import com.example.aquaflow.dto.InventoryInboundDTO;
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

    /**
     * 查询库存
     * @return
     */
    @Override
    public List<Inventory> list() {
        return inventoryMapper.list();
    }

    /**
     * 入库
     * @param items
     */
    @Override
    @Transactional
    public void inbound(List<InventoryInboundDTO.ItemDTO> items) {
        for (InventoryInboundDTO.ItemDTO item : items) {
            Integer waterTypeId = item.getWaterTypeId();
            Integer quantity = item.getQuantity();

            if(quantity == null || quantity <= 0){
                throw new RuntimeException("入库数量必须大于0");
            }

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

    /**
     * 库存校验
     * @param waterTypeId
     * @param needQuantity
     */
    public void checkStock(Integer waterTypeId,Integer needQuantity){
        Inventory inventory = inventoryMapper.getByWaterTypeId(waterTypeId);

        if (inventory == null || inventory.getQuantity()<needQuantity){
            throw new RuntimeException("库存不足");
        }
    }
}
