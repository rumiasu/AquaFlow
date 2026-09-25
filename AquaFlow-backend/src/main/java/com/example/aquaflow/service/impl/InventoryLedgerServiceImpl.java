package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.InventoryRecord;
import com.example.aquaflow.mapper.InventoryRecordMapper;
import com.example.aquaflow.service.InventoryLedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 库存流水唯一写入口（见接口注释：拆出来是为了打断两个服务之间的循环依赖）。
 */
@Service
public class InventoryLedgerServiceImpl implements InventoryLedgerService {

    @Autowired
    private InventoryRecordMapper inventoryRecordMapper;

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
}
