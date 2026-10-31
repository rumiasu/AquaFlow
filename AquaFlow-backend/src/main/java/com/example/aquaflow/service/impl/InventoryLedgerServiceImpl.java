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

    @Autowired private com.example.aquaflow.mapper.InventoryMapper inventoryMapper;

    @Override
    public void recordChange(Long stationId, Long productId, Integer delta, String type,
                             Long refId, Long operatorId, String note) {
        recordChangeWithCost(stationId, productId, delta, type, refId, operatorId, note, null);
    }

    @Override
    public void recordChangeWithCost(Long stationId, Long productId, Integer delta, String type,
                                     Long refId, Long operatorId, String note, java.math.BigDecimal incoming) {
        if (stationId == null || productId == null || delta == null || delta == 0) {
            return;
        }
        // 2026-10-28：数量已在同事务内更新；在同一库存锁内分摊并冻结成本，禁止日后用现价追填。
        var inventory = inventoryMapper.getByStationAndProductForUpdate(stationId, productId);
        if (delta > 0 && incoming == null && refId != null && ("CANCEL_RESTORE".equals(type) || "REFUND_RESTORE".equals(type))) {
            var original = inventoryRecordMapper.returnCost(stationId, productId, refId);
            if (original != null && original.get("remainingQty") instanceof Number q && q.intValue() >= delta
                    && original.get("unknownCost") instanceof Number u && u.intValue() == 0
                    && original.get("remainingCost") instanceof java.math.BigDecimal amount && amount.signum() >= 0) {
                incoming = delta == q.intValue() ? amount : amount.multiply(java.math.BigDecimal.valueOf(delta))
                        .divide(java.math.BigDecimal.valueOf(q.intValue()),2,java.math.RoundingMode.HALF_UP);
            }
        }
        var valuation = com.example.aquaflow.util.InventoryCostMath.move(inventory.getQuantity()-delta, inventory.getCostAmount(), delta, incoming);
        inventoryMapper.updateCostAmount(stationId, productId, valuation.remaining());
        InventoryRecord record = new InventoryRecord();
        record.setCostAmount(valuation.movement());
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
