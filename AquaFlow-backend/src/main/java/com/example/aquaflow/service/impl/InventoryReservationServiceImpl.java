package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.constant.ReservationStatus;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.InventoryReservation;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.InventoryReservationMapper;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.service.InventoryReservationService;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存预留凭据服务实现（规格 {@code docs/design/28-库存预留与履约凭据.md}）。
 *
 * <p><b>锁顺序（固定，不要改）</b>：`orders` 行（调用方的 CAS）→ `inventory_reservation` 行
 * → `inventory` 行。理由：换站必须先确认"订单还改得动"，再动凭据与实物；反过来会与
 * "完成配送"形成交叉等待。</p>
 *
 * <p>⚠️ 本类**只写** `inventory_reservation` 与（出库时的）`inventory.quantity` + `inventory_record`；
 * `reserved_qty` 的**增加**还有第二个入口 —— 入库后的补预留，它归
 * {@link InventoryService#backfillReservations}（那边反向依赖本类会成环，所以补预留放在库存服务里，
 * 两处都只经 {@link InventoryReservationMapper} 的 CAS 方法改，语义见各自注释）。</p>
 */
@Service
public class InventoryReservationServiceImpl implements InventoryReservationService {

    @Autowired
    private InventoryReservationMapper reservationMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private InventoryService inventoryService;

    @Override
    public int availableQty(Long stationId, Long productId) {
        if (stationId == null || productId == null) {
            return 0;
        }
        Inventory inv = inventoryMapper.getByStationAndProduct(stationId, productId);
        int stock = inv == null ? 0 : nz(inv.getQuantity());
        return Math.max(0, stock - reservationMapper.sumReserved(stationId, productId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reserveForItem(Long orderId, Long orderItemId, Long stationId, Long productId, int requestedQty) {
        // 行锁 + 当前读：并发下单必须串行地算"可用量"（AGENTS §8.2 —— 只加锁不做当前读仍会读旧快照）
        Inventory inv = inventoryMapper.getByStationAndProductForUpdate(stationId, productId);
        int stock = inv == null ? 0 : nz(inv.getQuantity());
        int alreadyReserved = reservationMapper.sumReserved(stationId, productId);
        int available = Math.max(0, stock - alreadyReserved);
        int reserveQty = Math.max(0, Math.min(available, requestedQty));

        InventoryReservation r = new InventoryReservation();
        r.setOrderId(orderId);
        r.setOrderItemId(orderItemId);
        r.setProductId(productId);
        r.setStationId(stationId);
        r.setReservedQty(reserveQty);
        r.setShippedQty(0);
        r.setReleasedQty(0);
        r.setStatus(ReservationStatus.RESERVED);
        reservationMapper.insert(r);

        // 预留量必须与 order_item.deducted_qty 一致：那一列是"下单时实际占用的库存量"，
        // 旧语义是"已扣减量"，新语义是"已预留在库量"（列注释与 OrderServiceImpl 的就地注释都已改）。
        orderItemMapper.updateDeductedQty(orderItemId, reserveQty);
        return reserveQty;
    }

    @Override
    public int shortageOfOrder(Long orderId) {
        return reservationMapper.shortageOfOrder(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void shipForOrder(Long orderId) {
        List<InventoryReservation> actives = reservationMapper.listActiveByOrderIdForUpdate(orderId);
        if (actives.isEmpty()) {
            // 兼容 v63 之前的存量单：它们的实物在下单那一刻就扣过了，这里**不能**再扣一次。
            // （迁移 v63 会把在途单的扣减加回并把凭据补上；这里兜的是"迁移之前就已完成"的历史单。）
            return;
        }
        // 硬门槛：缺货待补没补上就不许出库。宁可让站长先入库，也不许少扣 ——
        // 少扣会让这部分货永远不落账，而库存对账等式（quantity == Σ流水）根本看不出来。
        int shortage = reservationMapper.shortageOfOrder(orderId);
        if (shortage > 0) {
            throw new BusinessException("本站库存预留不足（缺 " + shortage
                    + " 桶），请先入库补足后再完成配送");
        }
        for (InventoryReservation r : actives) {
            int qty = nz(r.getReservedQty());
            if (qty > 0) {
                int affected = inventoryMapper.decreaseStock(r.getStationId(), r.getProductId(), qty);
                if (affected == 0) {
                    throw new BusinessException("出库失败：本站库存不足 " + qty + " 桶，请先入库");
                }
                // 实物减少才写流水；预留与释放**不写**（它们不动 quantity，
                // 写了反而会把 V1-4 等式「quantity == Σ流水」弄平不了）
                inventoryService.recordChange(r.getStationId(), r.getProductId(), -qty,
                        InventoryChangeType.CONSUME, orderId, AuthContext.getUserId(), "完成配送出库");
            }
            if (reservationMapper.shipIfActive(r.getId()) == 0) {
                throw new BusinessException("库存凭据已变更，请刷新后重试");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferForOrder(Long orderId, Long toStationId) {
        if (orderId == null || toStationId == null) {
            return;
        }
        List<InventoryReservation> actives = reservationMapper.listActiveByOrderIdForUpdate(orderId);
        for (InventoryReservation r : actives) {
            if (toStationId.equals(r.getStationId())) {
                continue;   // 已经在目标站，不用搬
            }
            // 需求量取订单明细（凭据上的 reserved_qty 只是"旧站当时锁到的量"，可能小于需求量）
            OrderItem item = orderItemMapper.getById(r.getOrderItemId());
            int need = item == null ? nz(r.getReservedQty()) : nz(item.getQuantity());

            Inventory inv = inventoryMapper.getByStationAndProductForUpdate(toStationId, r.getProductId());
            int stock = inv == null ? 0 : nz(inv.getQuantity());
            int available = Math.max(0, stock - reservationMapper.sumReserved(toStationId, r.getProductId()));
            int newReserved = Math.max(0, Math.min(available, need));

            if (reservationMapper.releaseIfActive(r.getId()) == 0) {
                throw new BusinessException("库存凭据已变更，请刷新后重试");
            }
            InventoryReservation moved = new InventoryReservation();
            moved.setOrderId(r.getOrderId());
            moved.setOrderItemId(r.getOrderItemId());
            moved.setProductId(r.getProductId());
            moved.setStationId(toStationId);
            moved.setReservedQty(newReserved);
            moved.setShippedQty(0);
            moved.setReleasedQty(0);
            moved.setStatus(ReservationStatus.RESERVED);
            reservationMapper.insert(moved);
            // order_item.deducted_qty 跟着改成"当前这份活跃凭据预留了多少"：
            // 它是"本单此刻占用了哪个站多少货"的镜像，站长端与取消链都读它。
            orderItemMapper.updateDeductedQty(r.getOrderItemId(), newReserved);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseForOrder(Long orderId) {
        return reservationMapper.releaseByOrderId(orderId);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
