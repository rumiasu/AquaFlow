package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.constant.ReservationStatus;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.InventoryReservation;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.InventoryReservationMapper;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.InventoryLedgerService;
import com.example.aquaflow.service.InventoryReservationService;
import com.example.aquaflow.util.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 库存预留凭据服务实现（规格 {@code docs/design/28} §11，本类是**唯一**分配入口）。
 *
 * <h3>锁协议（2026-09-25 二次收口 B1 —— 全仓唯一，没有例外）</h3>
 * <pre>
 *   ① 发现：用**普通读**（不加锁）查出"这次可能要动哪些 (站,商品) / 凭据"
 *            —— 结果**只用来决定锁谁**，不能当决策依据（它读的是旧快照）。
 *   ② 锁库存：把所有涉及的 (站,商品) 按 (station_id, product_id) **升序**逐个
 *            `select ... from inventory where ... for update`（多站多商品用同一把全局序）。
 *   ③ 当前读 + 重新验证：`... from inventory_reservation ... for update` 读活跃凭据，
 *            并比对②之前"发现"到的资源集合；不一致 ⇒ 抛可读冲突，让调用方**从事务外重试**
 *            （资源集合在发现到加锁之间变了，照着旧集合继续做就会锁错对象）。
 *   ④ 写：凭据（CAS + 检查受影响行数）→ `order_item` 镜像（单行 UPDATE，当前读）。
 *   ⑤ 补位：需要补位的 (站,商品) 的 inventory 行**已在②持锁**，此时再当前读凭据并分配。
 * </pre>
 *
 * <p>⚠️ 为什么必须"先库存后凭据"（首版是反的，二次验收 B1 打了回来）：首版里补位/预留先锁库存、
 * 出库/换站/释放却先锁凭据，于是只要"完成配送"和"同站同商品入库"撞上就成环 ——
 * T1 持凭据 R 等库存 I、T2 持库存 I 补位时等凭据 R，数据库只能回滚一个。
 * 现在**所有**路径都按①→⑤走，两个事务的等待方向就只剩一条，构造不出环。</p>
 *
 * <p>⚠️ 顺序里也包含调用方已经持有的锁：订单流程先 CAS `orders` 行（抢单/外派/取消）再进来，
 * 所以完整序是 <b>`orders` → `inventory`(升序) → `inventory_reservation` → `order_item`</b>。
 * 同一订单的状态机互斥**不能**用来证明"不同订单不会争同一库存/凭据"，本类的顺序是结构性保证。</p>
 *
 * <p>⚠️ `shipForOrder` 不再有例外：它先"发现"本单凭据挂在哪几个 (站,商品)（普通读），
 * 锁完这些库存行之后再当前读凭据；发现与当前读不一致 ⇒ 可读冲突（并发换站把凭据搬走了）。</p>
 *
 * <h3>需求量快照（二次收口 B2）</h3>
 * <p>补位的"需求量/排序时间"只读凭据行上的 {@code need_qty} / {@code need_time}（v65 新增），
 * <b>不再 join</b> `orders`/`order_item`：REPEATABLE READ 下当前读看得到刚提交的**新凭据**，
 * 普通读却看不到同一批提交里刚插入的**订单明细**，于是新等待单会被当成 need=0 静默跳过。
 * 真相源仍是 `order_item.quantity` / `orders.create_time`（下单时写一次、之后不变），
 * 凭据上的是它的只读副本，一致性由对账 `E15` 校验。</p>
 */
@Service
public class InventoryReservationServiceImpl implements InventoryReservationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationServiceImpl.class);

    @Autowired
    private InventoryReservationMapper reservationMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private OrderMapper orderMapper;

    /** 库存流水唯一写入口（拆出来是为了打断与 InventoryServiceImpl 的循环依赖） */
    @Autowired
    private InventoryLedgerService inventoryLedgerService;

    /* ==================== 查询（只读） ==================== */

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
    public int shortageOfOrder(Long orderId) {
        int missingItems = reservationMapper.countItemsByOrderId(orderId)
                - reservationMapper.countActiveByOrderId(orderId);
        if (missingItems > 0) {
            // 有明细没有凭据：缺口无法用"预留量之差"表达，直接用需求量兜底（调用方会据此拒绝出库）
            int need = 0;
            for (OrderItem item : orderItemMapper.listByOrderId(orderId)) {
                need += nz(item.getQuantity());
            }
            return need;
        }
        return reservationMapper.shortageOfOrder(orderId);
    }

    /**
     * 该单的备货情况（配送端展示用，契约 C4）。
     * <p>与 {@link #shortageOfOrder} 同一套判据（只读凭据的 {@code need_qty} 快照），
     * 差别只是把总量拆成"逐商品还缺多少"。**只读**：不做任何分配、不加锁，
     * 所以调用方只能拿它做展示 —— 真正拦住少扣的是 {@code shipForOrder} 出库前那次校验。</p>
     */
    @Override
    public Map<String, Object> prepInfoOfOrder(Long orderId) {
        Map<String, Object> info = new LinkedHashMap<>();
        List<Map<String, Object>> shortageItems = new ArrayList<>();
        info.put("ready", Boolean.FALSE);
        info.put("shortageTotal", 0);
        info.put("itemsWithoutCredential", 0);
        info.put("items", shortageItems);
        if (orderId == null) {
            return info;
        }
        int shortageTotal = 0;
        for (Map<String, Object> row : reservationMapper.listPrepByOrder(orderId)) {
            int need = num(row.get("needQty"));
            int reserved = num(row.get("reservedQty"));
            int shortage = Math.max(0, need - reserved);
            if (shortage <= 0) {
                continue;
            }
            shortageTotal += shortage;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", row.get("productId"));
            item.put("productName", row.get("productName"));
            item.put("needQty", need);
            item.put("reservedQty", reserved);
            item.put("shortage", shortage);
            shortageItems.add(item);
        }
        // 整条明细连凭据都没有（更严重的"备不了货"）：单独计数，别让它被 0 缺口的循环漏掉。
        // 注意这个方法在 mapper 里是**全库**计数（对账用）；本单视角要按 orderId 过滤，
        // 所以这里直接看"该单明细数 − 活跃凭据数"（与 shortageOfOrder 的判据同源）。
        int totalItems = reservationMapper.countItemsByOrderId(orderId);
        int activeCreds = reservationMapper.countActiveByOrderId(orderId);
        int missingCredential = Math.max(0, totalItems - activeCreds);
        info.put("shortageTotal", shortageTotal);
        info.put("itemsWithoutCredential", missingCredential);
        info.put("ready", Boolean.valueOf(shortageTotal == 0 && missingCredential == 0));
        return info;
    }

    /** Map 结果里的数值取整（JDBC 可能给 Integer / Long / BigDecimal）。 */
    private static int num(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    /* ==================== 分配命令 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reserveForItem(Long orderId, Long orderItemId, Long stationId, Long productId, int requestedQty) {
        // 需求量/需求时间快照：下单时写一次（这两个值是补位的唯一依据，见类注释 B2）
        NeedSnapshot need = readNeedSnapshot(orderId, orderItemId);
        // ① inventory 行锁 ② 活跃凭据当前读
        int available = lockAndComputeAvailable(stationId, productId);
        int reserveQty = Math.max(0, Math.min(available, Math.min(requestedQty, need.qty())));

        InventoryReservation r = new InventoryReservation();
        r.setOrderId(orderId);
        r.setOrderItemId(orderItemId);
        r.setProductId(productId);
        r.setStationId(stationId);
        r.setNeedQty(need.qty());
        r.setNeedTime(need.time());
        r.setReservedQty(reserveQty);
        r.setShippedQty(0);
        r.setReleasedQty(0);
        r.setStatus(ReservationStatus.RESERVED);
        reservationMapper.insert(r);

        syncDeductedQty(orderItemId, reserveQty);
        return reserveQty;
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
        List<InventoryReservation> actives = reservationMapper.listActiveForUpdate(stationId, productId);
        int free = nz(inv.getQuantity()) - sumReserved(actives);
        if (free <= 0) {
            return;
        }
        // ⚠️ 需求量/顺序只来自凭据行（当前读）—— 见类注释 B2：不许再 join orders/order_item
        for (InventoryReservation r : orderedByBusinessNeed(actives)) {
            if (free <= 0) {
                break;
            }
            int lack = requireNeed(r) - nz(r.getReservedQty());
            if (lack <= 0) {
                continue;
            }
            int add = Math.min(lack, free);
            if (add <= 0 || reservationMapper.addReservedIfActive(r.getId(), add) == 0) {
                continue;   // 凭据已被出库/释放，跳过
            }
            syncDeductedQty(r.getOrderItemId(), nz(r.getReservedQty()) + add);
            free -= add;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertStockNotBelowReserved(Long stationId, Long productId, int targetQuantity) {
        if (stationId == null || productId == null) {
            return;
        }
        Inventory inv = inventoryMapper.getByStationAndProductForUpdate(stationId, productId);
        if (inv == null) {
            return;   // 该商品本站还没配置库存行（不存在"盘亏"）
        }
        List<InventoryReservation> actives = reservationMapper.listActiveForUpdate(stationId, productId);
        int reserved = sumReserved(actives);
        if (targetQuantity >= reserved) {
            return;
        }
        // 把"占着货的单"列出来，站长才知道该去完成还是取消哪些单（只说"库存不足"没法处置）
        StringBuilder orders = new StringBuilder();
        TreeSet<Long> seen = new TreeSet<>();
        for (InventoryReservation r : actives) {
            if (r.getOrderId() == null || nz(r.getReservedQty()) <= 0 || !seen.add(r.getOrderId())) {
                continue;
            }
            orders.append(orders.length() == 0 ? "" : "、").append('#').append(r.getOrderId());
        }
        throw new BusinessException("盘点后库存 " + targetQuantity + " 桶低于已被订单预留的 " + reserved
                + " 桶" + (orders.length() == 0 ? "" : "（占用中：" + orders + "）")
                + "，请先完成或取消这些订单再减少库存");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void shipForOrder(Long orderId, Long expectedStationId) {
        // ① 发现：只用来决定"锁哪些库存行"
        List<InventoryReservation> discovered = reservationMapper.listActiveByOrderId(orderId);
        // ② 按 (站,商品) 升序锁库存行
        lockInventoryRows(pairsOf(discovered, null), "完成配送出库");
        // ③ 当前读 + 重新验证资源集合
        List<InventoryReservation> actives = reservationMapper.listActiveByOrderIdForUpdate(orderId);
        assertResourceSetUnchanged("完成配送", discovered, actives);

        List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
        // ④ 覆盖检查：每一条明细都必须有活跃凭据（含 reserved_qty = 0 的缺货明细）
        if (actives.size() != items.size()) {
            // ⚠️ **文案纪律（契约工作包 C3）**：这条消息会原样弹给配送员/站长（GlobalExceptionHandler →
            //    前端 toast），所以只说"发生了什么 + 他下一步能做什么 + 订单号"。
            //    条数、凭据 ID、站 ID、迁移脚本版本一律**只进日志**（log.warn 在下面），
            //    否则等于把内部状态机和运维口径教给一线（2026-09-25 界面走查的发现）。
            log.warn("完成配送被拒（备货记录不完整）：orderId={}, items={}, activeCredentials={}",
                    orderId, items.size(), actives.size());
            throw new BusinessException(businessMessage(orderId,
                    "本单的备货记录不完整，暂时无法完成配送。请联系站长核对库存后再送。"));
        }
        Map<Long, OrderItem> itemById = new HashMap<>();
        for (OrderItem it : items) {
            itemById.put(it.getId(), it);
        }
        // ⑤ 站别 + 数量检查（逐条给可读原因）
        for (InventoryReservation r : actives) {
            OrderItem it = itemById.get(r.getOrderItemId());
            if (it == null) {
                log.warn("完成配送被拒（凭据指向不存在的明细）：orderId={}, reservationId={}, orderItemId={}",
                        orderId, r.getId(), r.getOrderItemId());
                throw new BusinessException(businessMessage(orderId,
                        "本单的商品信息对不上，暂时无法完成配送。请联系站长核对。"));
            }
            Long credStation = r.getStationId();
            if (expectedStationId != null && !expectedStationId.equals(credStation)) {
                // ⚠️ 文案里给的处置动作必须是**当时真能执行**的（2026-09-25 界面走查的发现）：
                // 本检查在"配送中(2)"触发，而归属站的「召回到待配送」只放行状态 1
                //（OrderWorkflowServiceImpl.cancelDispatch），配送中点召回是无效操作；
                // 真能走的是配送端的「指定退回」——退回归属站后重新派给本站，凭据才会跟着换站。
                log.warn("完成配送被拒（凭据站别不符）：orderId={}, reservationId={}, credStation={}, expectedStation={}",
                        orderId, r.getId(), credStation, expectedStationId);
                throw new BusinessException(businessMessage(orderId,
                        "本单要在原来的水站出库，当前站在别处。请先在本站提交「指定退回」，"
                                + "把单退回归属站后重新派单，再完成配送。"));
            }
            int need = requireNeed(r);
            if (it.getQuantity() != null && it.getQuantity() != need) {
                // 快照与真相源分叉 ⇒ 不能拿它出库（对账 E15 会报同一条）
                log.warn("完成配送被拒（需求量快照与明细不一致）：orderId={}, reservationId={}, "
                                + "itemQuantity={}, needSnapshot={}",
                        orderId, r.getId(), it.getQuantity(), need);
                throw new BusinessException(businessMessage(orderId,
                        "本单的商品数量与备货记录对不上，暂时无法完成配送。请联系站长核对。"));
            }
            int reserved = nz(r.getReservedQty());
            if (reserved != need) {
                // 说清"谁该做这件事"：点完成的是配送员，而入库入口只有站长有
                //（InventoryController 限 STATION_MANAGER）—— 只说"请先入库"等于把球踢给没有权限的人。
                throw new BusinessException(businessMessage(orderId,
                        "本站的货还没备齐（还缺 " + (need - reserved) + " 桶），请让站长先入库补足后再完成配送。"));
            }
        }
        // ⑥ 出库：库存行已在②持锁，按 product_id 升序逐条出
        actives.sort(Comparator.comparing(InventoryReservation::getProductId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (InventoryReservation r : actives) {
            int qty = nz(r.getReservedQty());
            if (qty > 0) {
                int affected = inventoryMapper.decreaseStock(r.getStationId(), r.getProductId(), qty);
                if (affected == 0) {
                    log.warn("完成配送被拒（实物不足）：orderId={}, stationId={}, productId={}, qty={}",
                            orderId, r.getStationId(), r.getProductId(), qty);
                    throw new BusinessException(businessMessage(orderId,
                            "本站的货不够出这一单（差 " + qty + " 桶），请让站长先入库后再完成配送。"));
                }
                inventoryLedgerService.recordChange(r.getStationId(), r.getProductId(), -qty,
                        InventoryChangeType.CONSUME, orderId, AuthContext.getUserId(), "完成配送出库");
            }
            if (reservationMapper.shipIfActive(r.getId()) == 0) {
                log.warn("完成配送被拒（凭据已被并发改动）：orderId={}, reservationId={}", orderId, r.getId());
                throw new BusinessException(businessMessage(orderId, "本单的库存信息刚被改动，请刷新后重试。"));
            }
            syncDeductedQty(r.getOrderItemId(), 0);   // 该明细已无活跃凭据 ⇒ 镜像归 0
        }
    }

    /**
     * 面向一线（配送员/站长）的短文案：<b>一句事实 + 订单号</b>。
     * <p>订单号是客户/站长在系统里唯一能对上号的东西，前面那些内部术语（凭据条数、站 ID、
     * 迁移脚本名）对着一线没有任何可执行价值，只留日志（契约工作包 C3）。</p>
     */
    private static String businessMessage(Long orderId, String fact) {
        return orderId == null ? fact : fact + "（订单号 " + orderId + "）";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferForOrder(Long orderId, Long toStationId) {
        if (orderId == null || toStationId == null) {
            return;
        }
        // ① 发现（普通读）：旧站在哪、要新建到哪个站
        List<InventoryReservation> discovered = reservationMapper.listActiveByOrderId(orderId);
        if (discovered.isEmpty()) {
            return;
        }
        // ② 一次性按全局序锁住"旧站 + 目标站"的库存行（相向换站也不会互等）
        lockInventoryRows(pairsOf(discovered, toStationId), "换站");
        // ③ 当前读 + 重新验证：发现到加锁之间凭据可能已经被别的换站/取消改过
        List<InventoryReservation> actives = reservationMapper.listActiveByOrderIdForUpdate(orderId);
        if (actives.isEmpty()) {
            return;
        }
        assertResourceSetUnchanged("换站", discovered, actives);

        TreeSet<Long[]> oldPairs = new TreeSet<>(pairOrder());
        for (InventoryReservation r : actives) {
            Long from = r.getStationId();
            if (toStationId.equals(from)) {
                continue;
            }
            int need = requireNeed(r);
            Inventory target = inventoryMapper.getByStationAndProductForUpdate(toStationId, r.getProductId());
            int stock = target == null ? 0 : nz(target.getQuantity());
            int alreadyReserved = sumReserved(reservationMapper.listActiveForUpdate(toStationId, r.getProductId()));
            int newReserved = Math.max(0, Math.min(Math.max(0, stock - alreadyReserved), need));

            if (reservationMapper.releaseIfActive(r.getId()) == 0) {
                throw new BusinessException("库存凭据已变更，请刷新后重试");
            }
            InventoryReservation moved = new InventoryReservation();
            moved.setOrderId(r.getOrderId());
            moved.setOrderItemId(r.getOrderItemId());
            moved.setProductId(r.getProductId());
            moved.setStationId(toStationId);
            // ⚠️ 需求快照跟着搬（这是"业务需求时间"而不是"凭据创建时间"：换站会新建凭据、id 会变，
            //    但客户是什么时候下的单没有变 —— FIFO 必须按后者）
            moved.setNeedQty(need);
            moved.setNeedTime(r.getNeedTime());
            moved.setReservedQty(newReserved);
            moved.setShippedQty(0);
            moved.setReleasedQty(0);
            moved.setStatus(ReservationStatus.RESERVED);
            reservationMapper.insert(moved);
            syncDeductedQty(r.getOrderItemId(), newReserved);
            oldPairs.add(new Long[]{from, r.getProductId()});
        }
        // ④ 旧站释放出来的货按 FIFO 补给旧站的等待需求（库存行已在②持锁）
        for (Long[] pair : oldPairs) {
            backfillReservations(pair[0], pair[1]);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseForOrder(Long orderId) {
        if (orderId == null) {
            return 0;
        }
        // ① 发现（普通读）
        List<InventoryReservation> discovered = reservationMapper.listActiveByOrderId(orderId);
        if (discovered.isEmpty()) {
            return 0;
        }
        // ② 先锁涉及到的库存行，③ 再当前读凭据并重新验证
        lockInventoryRows(pairsOf(discovered, null), "释放预留");
        List<InventoryReservation> actives = reservationMapper.listActiveByOrderIdForUpdate(orderId);
        assertResourceSetUnchanged("释放预留", discovered, actives);

        TreeSet<Long[]> pairs = new TreeSet<>(pairOrder());
        int released = 0;
        for (InventoryReservation r : actives) {
            if (reservationMapper.releaseIfActive(r.getId()) == 0) {
                continue;
            }
            syncDeductedQty(r.getOrderItemId(), 0);
            pairs.add(new Long[]{r.getStationId(), r.getProductId()});
            released++;
        }
        for (Long[] pair : pairs) {
            backfillReservations(pair[0], pair[1]);
        }
        return released;
    }

    /* ==================== 内部工具 ==================== */

    /** 下单需求量/需求时间（= 真相源在**下单那一刻**的值；本事务刚插入的行，普通读即可见）。 */
    private record NeedSnapshot(int qty, LocalDateTime time) {
    }

    private NeedSnapshot readNeedSnapshot(Long orderId, Long orderItemId) {
        OrderItem item = orderItemId == null ? null : orderItemMapper.getById(orderItemId);
        if (item == null || item.getQuantity() == null) {
            throw new BusinessException("订单明细不存在或缺少数量，无法建立库存预留凭据");
        }
        Orders order = orderId == null ? null : orderMapper.getById(orderId);
        if (order == null || order.getCreateTime() == null) {
            throw new BusinessException("订单不存在或缺少下单时间，无法建立库存预留凭据");
        }
        return new NeedSnapshot(item.getQuantity(), order.getCreateTime());
    }

    /**
     * 凭据的需求量快照。**缺失就是数据损坏，不许当成 0 静默跳过**（二次验收 B2 的最后一句）：
     * 列是 {@code NOT NULL}，走到这里说明有人动过表结构或绕过本服务写库 —— 直接报错，
     * 让对账/迁移去修，而不是让某个等货的订单悄悄少分货。
     */
    private int requireNeed(InventoryReservation r) {
        Integer need = r.getNeedQty();
        if (need == null || need <= 0) {
            log.error("[库存预留] 凭据缺少需求量快照：reservationId={} orderId={} itemId={} needQty={}",
                    r.getId(), r.getOrderId(), r.getOrderItemId(), need);
            throw new BusinessException("库存预留凭据缺少需求量快照（数据异常），"
                    + "请先跑对账 E15/迁移修复后再操作库存");
        }
        return need;
    }

    /** 两件套：① 锁 (站,商品) 的 inventory 行 ② **当前读**活跃凭据并求和 ⇒ 可用量。 */
    private int lockAndComputeAvailable(Long stationId, Long productId) {
        Inventory inv = inventoryMapper.getByStationAndProductForUpdate(stationId, productId);
        int stock = inv == null ? 0 : nz(inv.getQuantity());
        int reserved = sumReserved(reservationMapper.listActiveForUpdate(stationId, productId));
        return Math.max(0, stock - reserved);
    }

    /**
     * 按 (station_id, product_id) 升序锁住一批库存行（协议第②步）。
     * <p>缺库存行**不算错**：实物不存在时该对没有可分配量（下单也预留不出来），
     * 真正的失败会在"出库时实物不足"那一步以可读文案报出来。</p>
     */
    private void lockInventoryRows(List<Long[]> pairs, String op) {
        for (Long[] pair : pairs) {
            inventoryMapper.getByStationAndProductForUpdate(pair[0], pair[1]);
        }
        if (log.isDebugEnabled()) {
            log.debug("[库存预留] {}：已按全局序锁定 {} 个 (站,商品) 的库存行", op, pairs.size());
        }
    }

    /** 本次要动的全部 (站,商品) 对，按 (station_id, product_id) 升序去重（全局锁序）。 */
    private List<Long[]> pairsOf(List<InventoryReservation> rows, Long extraStation) {
        TreeSet<Long[]> pairs = new TreeSet<>(pairOrder());
        for (InventoryReservation r : rows) {
            pairs.add(new Long[]{r.getStationId(), r.getProductId()});
            if (extraStation != null) {
                pairs.add(new Long[]{extraStation, r.getProductId()});
            }
        }
        return new ArrayList<>(pairs);
    }

    private Comparator<Long[]> pairOrder() {
        return Comparator.<Long[], Long>comparing(p -> p[0]).thenComparing(p -> p[1]);
    }

    /**
     * 重新验证：②之后当前读到的活跃凭据，必须与①"发现"到的**资源集合**一致
     * （按明细 → 所在站比对；站变了就等于资源集合变了）。
     *
     * <p>不一致时**必须失败**：说明在"发现→加锁"这段窗口里，有并发事务把这单的凭据搬走/换站了，
     * 我们锁住的库存行已经不是要动的那批。契约明确：这种情况要"重新验证"，
     * 需要重试就**从事务外重开**（由调用方/用户重发请求），不许在事务里 catch 死锁或猜着继续。</p>
     */
    private void assertResourceSetUnchanged(String op, List<InventoryReservation> discovered,
                                           List<InventoryReservation> current) {
        Map<Long, Long> before = stationByItem(discovered);
        Map<Long, Long> now = stationByItem(current);
        if (!before.equals(now)) {
            log.warn("[库存预留] {}：资源集合在发现与加锁之间发生变化（发现={} 当前={}），拒绝执行",
                    op, before, now);
            throw new BusinessException("该订单的库存凭据刚刚被其他操作变更（并发换站/取消），"
                    + "本次" + op + "未执行，请刷新后重试");
        }
    }

    private Map<Long, Long> stationByItem(List<InventoryReservation> rows) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (InventoryReservation r : rows) {
            out.put(r.getOrderItemId(), r.getStationId());
        }
        return out;
    }

    private int sumReserved(List<InventoryReservation> rows) {
        int sum = 0;
        for (InventoryReservation r : rows) {
            sum += nz(r.getReservedQty());
        }
        return sum;
    }

    /**
     * 补位顺序 = **业务需求时间**（凭据上的 {@code need_time} 快照，再按明细 id）。
     * ⚠️ 不能用凭据 id：换站会新建凭据（新 id），而它的业务需求时间其实是原下单时间。
     * ⚠️ 读的是凭据行自己的快照（当前读），不再 join `orders`（见类注释 B2）。
     */
    private List<InventoryReservation> orderedByBusinessNeed(List<InventoryReservation> rows) {
        List<InventoryReservation> copy = new ArrayList<>(rows);
        copy.sort(Comparator
                .comparing(InventoryReservation::getNeedTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.getOrderItemId() == null ? Long.MAX_VALUE : r.getOrderItemId()));
        return copy;
    }

    /** `order_item.deducted_qty` 的唯一语义：**当前活跃凭据的预留量镜像**（无活跃凭据时为 0）。 */
    private void syncDeductedQty(Long orderItemId, int qty) {
        if (orderItemId != null) {
            orderItemMapper.updateDeductedQty(orderItemId, qty);
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
