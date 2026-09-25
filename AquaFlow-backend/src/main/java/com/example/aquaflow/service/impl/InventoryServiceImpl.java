package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.InventoryRecord;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.InventoryRecordMapper;
import com.example.aquaflow.mapper.InventoryReservationMapper;
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

    /** 入库补预留要读"哪张单在等货"（只经它的 CAS 方法改 reserved_qty，见 backfillReservations） */
    @Autowired
    private InventoryReservationMapper inventoryReservationMapper;

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
            // [2026-09-25 库存预留模型] 新到的货先补给"等货的单"（按 FIFO），否则那些单
            // 完成配送时会被 shipForOrder 拦下（缺货待补没落账），站长会以为"明明入了库还不能送"
            backfillReservations(stationId, item.getProductId());
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
        if (delta > 0) {
            // 盘点增加 / 入库同样要把新货补给等货的单（见 backfillReservations 的注释）
            backfillReservations(stationId, productId);
        }
        return delta;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void backfillReservations(Long stationId, Long productId) {
        if (stationId == null || productId == null) {
            return;
        }
        Inventory inv = inventoryMapper.getByStationAndProductForUpdate(stationId, productId);
        if (inv == null) {
            return;
        }
        int free = (inv.getQuantity() == null ? 0 : inv.getQuantity())
                - inventoryReservationMapper.sumReserved(stationId, productId);
        if (free <= 0) {
            return;   // 没有新增可用量，或全都已经被别的单预留着
        }
        // 按 id 升序（= 下单先后）补，补到可用量用完为止：不得让 Σ预留 > 实物
        for (java.util.Map<String, Object> row : inventoryReservationMapper
                .listShortageNeedForUpdate(stationId, productId)) {
            if (free <= 0) {
                break;
            }
            Object needRaw = row.get("needQty");
            Object idRaw = row.get("id");
            if (needRaw == null || idRaw == null) {
                continue;
            }
            int need = ((Number) needRaw).intValue();
            if (need <= 0) {
                continue;
            }
            int add = Math.min(need, free);
            if (inventoryReservationMapper.addReservedIfActive(((Number) idRaw).longValue(), add) == 0) {
                continue;   // 这份凭据已被出库/释放，跳过
            }
            free -= add;
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

    @Override
    public List<InventoryRecord> listRecords(Long stationId, Long productId, int limit) {
        if (productId == null) {
            return inventoryRecordMapper.listByStation(stationId, limit);
        }
        return inventoryRecordMapper.listByStationAndProduct(stationId, productId, limit);
    }
}
