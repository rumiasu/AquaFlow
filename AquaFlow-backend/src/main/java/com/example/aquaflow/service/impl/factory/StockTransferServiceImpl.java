package com.example.aquaflow.service.impl.factory;

import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.StockTransfer;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.factory.StockTransferMapper;
import com.example.aquaflow.service.factory.StockTransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StockTransferServiceImpl implements StockTransferService {

    @Autowired
    private StockTransferMapper transferMapper;
    @Autowired
    private InventoryMapper inventoryMapper;

    @Override
    public List<Map<String, Object>> availableStock() {
        List<Inventory> allInventory = inventoryMapper.listWithStation();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Inventory inv : allInventory) {
            if (inv.getQuantity() > 10) { // 只显示有富余库存的
                Map<String, Object> item = new HashMap<>();
                item.put("stationId", inv.getStationId());
                item.put("stationName", inv.getStationName());
                item.put("waterTypeId", inv.getWaterTypeId());
                item.put("waterTypeName", inv.getWaterTypeName());
                item.put("spec", inv.getSpec());
                item.put("quantity", inv.getQuantity());
                item.put("available", inv.getQuantity() - 10); // 保留10作为安全库存
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public void create(StockTransfer transfer) {
        transfer.setStatus(1); // 待审批
        transfer.setCreateTime(LocalDateTime.now());
        transfer.setUpdateTime(LocalDateTime.now());
        transferMapper.insert(transfer);
    }

    @Override
    public List<StockTransfer> list(Integer status) {
        if (status != null) {
            return transferMapper.listByStatus(status);
        }
        return transferMapper.listAll();
    }

    @Override
    @Transactional
    public void approve(Integer id, String note) {
        transferMapper.approve(id, 2, note);
    }

    @Override
    @Transactional
    public void complete(Integer id, String note) {
        StockTransfer transfer = transferMapper.getById(id);
        if (transfer == null || transfer.getStatus() != 2) {
            throw new RuntimeException("调拨记录不存在或状态不对");
        }

        // 扣减调出站库存
        Inventory fromInv = inventoryMapper.getByStationAndWaterType(transfer.getFromStationId(), transfer.getWaterTypeId());
        if (fromInv == null || fromInv.getQuantity() < transfer.getQuantity()) {
            throw new RuntimeException("调出水站库存不足");
        }
        inventoryMapper.decreaseStock(transfer.getFromStationId(), transfer.getWaterTypeId(), transfer.getQuantity());

        // 增加调入站库存
        Inventory toInv = inventoryMapper.getByStationAndWaterType(transfer.getToStationId(), transfer.getWaterTypeId());
        if (toInv == null) {
            // 调入站无此水类型库存，新建记录
            Inventory newInv = new Inventory();
            newInv.setStationId(transfer.getToStationId());
            newInv.setWaterTypeId(transfer.getWaterTypeId());
            newInv.setQuantity(transfer.getQuantity());
            newInv.setUpdateTime(LocalDateTime.now());
            inventoryMapper.insert(newInv);
        } else {
            inventoryMapper.increaseStock(transfer.getToStationId(), transfer.getWaterTypeId(), transfer.getQuantity());
        }

        transferMapper.complete(id, note);
    }

    @Override
    public void cancel(Integer id) {
        transferMapper.cancel(id);
    }
}
