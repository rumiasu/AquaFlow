package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.InventoryRecord;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.InventoryRecordMapper;
import com.example.aquaflow.service.InventoryLedgerService;
import com.example.aquaflow.service.InventoryReservationService;
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

    /**
     * 库存流水唯一写入口（拆出来是为了打断与预留服务的循环依赖：入库后要补预留，
     * 而出库/补位要写流水 —— 两边直接互指会在 Spring Boot 2.6+ 的"禁止循环引用"下启动失败）。
     */
    @Autowired
    private InventoryLedgerService inventoryLedgerService;

    /**
     * 唯一分配入口（{@code InventoryReservationService}）：入库/盘点增加之后调它的
     * {@code backfillReservations} 把新货补给等货的单；盘点减少前用它检查不变量。
     * <p>⚠️ 分配算法**只有它一份实现**（返工 R4：首版把补位放在本类，导致"取消释放"那条路径天然漏了补位）。</p>
     */
    @Autowired
    private InventoryReservationService inventoryReservationService;

    @Override
    public List<Inventory> list(Long stationId) {
        return inventoryMapper.listByStationId(stationId);
    }

    /**
     * ⚠️ 判据是**可用量**（实物 − 活跃预留），不是实物：已被别的单预留下的货不能再卖一次。
     * <p>下单路径**不用**本方法（2026-09-25 库存预留模型）：下单缺货是允许的（先预留、到货补位），
     * 见 {@code InventoryReservationService.reserveForItem}。</p>
     */
    @Override
    public void checkStock(Long stationId, Long productId, Integer needQuantity) {
        int available = inventoryReservationService.availableQty(stationId, productId);
        if (available < needQuantity) {
            throw new BusinessException("水站可用库存不足，还差 " + (needQuantity - available) + " 桶，请先入库后再接单");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inbound(Long stationId, List<InventoryInboundDTO.ItemDTO> items) {
        Long operatorId = AuthContext.getUserId();
        // ⚠️ 按 productId 升序处理（返工 V05）：每轮都会锁住 (站,商品) 的 inventory 行并做补位，
        // 照请求顺序处理时，两张商品顺序相反的入库单会互相等锁（固定锁环 → 死锁，MySQL 只回滚其中一个，
        // 用户看到系统错误）。入库结果与处理顺序无关，故可以重排。
        List<InventoryInboundDTO.ItemDTO> ordered = new java.util.ArrayList<>(items);
        ordered.sort(java.util.Comparator.comparing(InventoryInboundDTO.ItemDTO::getProductId,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        for (InventoryInboundDTO.ItemDTO item : ordered) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BusinessException("入库数量必须大于0");
            }
            inventoryMapper.upsertQuantity(stationId, item.getProductId(), item.getQuantity());
            // [AQ-029] 入库写流水，与库存变动同事务
            recordChange(stationId, item.getProductId(), item.getQuantity(), InventoryChangeType.INBOUND,
                    null, operatorId, "入库");
            // [2026-09-25 库存预留模型] 新到的货先补给"等货的单"（按业务需求时间 FIFO），
            // 否则那些单完成配送时会被 shipForOrder 拦下（站长会以为"明明入了库还不能送"）
            inventoryReservationService.backfillReservations(stationId, item.getProductId());
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
        if (delta < 0) {
            // [2026-09-25 返工 R6/V06] 盘亏不得把实物盘到"活跃预留"以下：那会让 Δ(实物 − 预留) 变负
            // （= 已卖出去的货凭空消失），而站长看到的只是"保存成功"。真实盘亏本轮不做专用命令 ⇒ 明确拒绝。
            // 本方法已锁 inventory 行，这里再取活跃凭据的当前读，与预留服务的取锁顺序一致（先库存后凭据）。
            inventoryReservationService.assertStockNotBelowReserved(stationId, productId, targetQuantity);
        }
        inventoryMapper.upsertQuantity(stationId, productId, delta);
        recordChange(stationId, productId, delta, type, refId, AuthContext.getUserId(), note);
        if (delta > 0) {
            // 盘点增加 / 入库同样要把新货补给等货的单（分配算法在预留服务里，见其类注释）
            inventoryReservationService.backfillReservations(stationId, productId);
        }
        return delta;
    }

    /**
     * [AQ-029] 库存流水写入**委托**给 {@link InventoryLedgerService}（唯一写入口）。
     * <p>为什么要有这一跳：本类与预留服务互有调用需求，两边都能写流水就会互相注入 ⇒ 循环依赖启动失败。
     * 流水口径与事务语义都还在这一层（{@code delta = 0} 静默跳过、与库存变动同事务）。</p>
     */
    @Override
    public void recordChange(Long stationId, Long productId, Integer delta, String type,
                             Long refId, Long operatorId, String note) {
        inventoryLedgerService.recordChange(stationId, productId, delta, type, refId, operatorId, note);
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
