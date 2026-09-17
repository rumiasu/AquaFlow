package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.exception.BusinessException;
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
            throw new BusinessException("水站库存不足，还差 " + needQuantity + " 桶，请先入库后再接单");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inbound(Long stationId, List<InventoryInboundDTO.ItemDTO> items) {
        Long operatorId = AuthContext.getUserId();
        for (InventoryInboundDTO.ItemDTO item : items) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BusinessException("入库数量必须大于0");
            }
            inventoryMapper.upsertQuantity(stationId, item.getProductId(), item.getQuantity());
            // [AQ-029] 入库写流水，与库存变动同事务
            recordChange(stationId, item.getProductId(), item.getQuantity(), InventoryChangeType.INBOUND,
                    null, operatorId, "入库");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveStationSetting(Inventory setting) {
        if (setting == null || setting.getStationId() == null || setting.getProductId() == null) {
            throw new BusinessException("本站设置参数异常");
        }
        Inventory current = inventoryMapper.getByStationAndProduct(setting.getStationId(), setting.getProductId());
        // 库存不从设置入口改：沿用当前值（新行=0），保证 inventory.quantity 与 inventory_record 永远成对
        setting.setQuantity(current != null && current.getQuantity() != null ? current.getQuantity() : 0);
        inventoryMapper.upsertSettings(setting);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int setStock(Long stationId, Long productId, Integer targetQuantity, String type, Long refId, String note) {
        if (stationId == null || productId == null) {
            throw new BusinessException("库存参数异常");
        }
        if (targetQuantity == null || targetQuantity < 0) {
            throw new BusinessException("库存数量不能为负");
        }
        // 先锁行再算差额：并发盘点下"读旧值→算 delta→增量写"必须串行，否则最后一次覆盖前一次
        Inventory current = inventoryMapper.getByStationAndProductForUpdate(stationId, productId);
        if (current == null) {
            throw new BusinessException("该商品尚未在本站配置，请先在商品页选用后再入库");
        }
        int before = current.getQuantity() == null ? 0 : current.getQuantity();
        int delta = targetQuantity - before;
        if (delta == 0) {
            return 0;
        }
        inventoryMapper.upsertQuantity(stationId, productId, delta);
        recordChange(stationId, productId, delta, type, refId, AuthContext.getUserId(), note);
        return delta;
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

    @Override
    public List<InventoryRecord> listRecords(Long stationId, Long productId, int limit) {
        if (productId == null) {
            return inventoryRecordMapper.listByStation(stationId, limit);
        }
        return inventoryRecordMapper.listByStationAndProduct(stationId, productId, limit);
    }
}
