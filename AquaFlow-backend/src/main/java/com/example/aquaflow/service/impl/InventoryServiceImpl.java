package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.InventoryRecord;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.InventoryRecordMapper;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryServiceImpl implements InventoryService {

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private InventoryRecordMapper inventoryRecordMapper;

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
    @Transactional(rollbackFor = Exception.class)
    public void inbound(Long stationId, List<InventoryInboundDTO.ItemDTO> items) {
        Long operatorId = AuthContext.getUserId();
        for (InventoryInboundDTO.ItemDTO item : items) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new RuntimeException("入库数量必须大于0");
            }
            inventoryMapper.upsertQuantity(stationId, item.getProductId(), item.getQuantity());
            // [AQ-029] 入库写流水，与库存变动同事务
            recordChange(stationId, item.getProductId(), item.getQuantity(), InventoryChangeType.INBOUND,
                    null, operatorId, "入库");
        }
    }

    @Override
    public void recordChange(Long stationId, Long productId, Integer delta, String type,
                             Long refId, Long operatorId, String note) {
        if (stationId == null || productId == null || delta == null || delta == 0) {
            return;
        }
        InventoryRecord record = new InventoryRecord();
        record.setStationId(stationId);
        record.setProductId(productId);
        record.setDelta(delta);
        record.setType(type);
        record.setRefId(refId);
        record.setOperatorId(operatorId);
        record.setNote(note);
        inventoryRecordMapper.insert(record);
    }

    @Override
    public List<InventoryRecord> listRecords(Long stationId, int limit) {
        return inventoryRecordMapper.listByStation(stationId, limit);
    }
}
