package com.example.aquaflow.service;

import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.BarrelScope;
import com.example.aquaflow.util.PriceUtil;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 桶权益总账 —— 全系统<b>唯一</b>的桶账写入口。
 *
 * <h3>模型（业务方确认，不要凭直觉改）</h3>
 * <pre>
 * 权益 Right  = Σ lot.remain_qty               （顾客在该站该桶型买下的可占用桶数）
 * 占用 Occupied = 顾客手上实际有几个桶          （派生，不落表）
 * over        = 占用 − 权益                    （按 customer × station × product）
 *
 * 恒等式：占用 = 权益 + over
 * </pre>
 *
 * <p>桶是<b>流动实体</b>（工厂→水站→配送员→顾客→水站→工厂）。
 * 「实体桶在谁手里」和「顾客拥有多少权益」是两件不同的事，绝不能混为一谈：
 * 正常换桶（收回 3 个空桶、送出 3 个满桶）权益 3→3 恒定不变。</p>
 *
 * <h3>over 的四条硬约束</h3>
 * <ol>
 *   <li>{@code over < 0}（多还桶 / 水站暂存）是<b>合法状态</b>，本类<b>不做任何非负校验</b>。</li>
 *   <li>over 永远不是退款依据；唯一能退款的是 lot 里剩余的权益。</li>
 *   <li>正常还桶<b>绝不扣权益</b>：{@link #returnEmpty} 只动 over，不碰 lot、不碰押金、不产生退款。</li>
 *   <li>所有方法都必须带 customer + station + product，A 水的多还桶不能抵 B 水的欠桶。</li>
 * </ol>
 */
@Service
public class BarrelLedgerService {

    @Autowired private CustomerBarrelLotMapper lotMapper;
    @Autowired private CustomerBarrelOverMapper overMapper;
    @Autowired private CustomerBarrelAssetMapper assetMapper;
    @Autowired private CustomerBarrelInTransitMapper inTransitMapper;
    @Autowired private OrderItemMapper orderItemMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private InventoryMapper inventoryMapper;

    // =========================================================================
    // 只读查询
    // =========================================================================

    /** 权益数：该顾客在该站该桶型买下的可占用桶数 */
    public int rightQty(Long customerId, Long stationId, Long productId) {
        return lotMapper.sumRemain(customerId, stationId, productId);
    }

    /** over：可为负（多还桶 / 水站暂存）。缺失记录按 0 处理。 */
    public int overQty(Long customerId, Long stationId, Long productId) {
        CustomerBarrelOver o = overMapper.get(customerId, stationId, productId);
        return (o == null || o.getOverQty() == null) ? 0 : o.getOverQty();
    }

    /**
     * 加锁读 over（当前读），仅用于并发写入路径。
     *
     * <p>与 {@link #overQty} 的区别：MySQL 默认 REPEATABLE READ 隔离级别下，
     * 普通 SELECT 读的是事务开始时的快照；并发的第二个事务即使 wait 到第一个提交、
     * 拿到了行锁，普通 SELECT 依然返回旧值，于是校验形同虚设。
     * {@code SELECT ... FOR UPDATE} 属于当前读，必然返回最新已提交值。</p>
     *
     * <p>调用前应先 {@code lockOrCreate} 确保行存在（FOR UPDATE 对不存在的行不加锁）。</p>
     */
    private int lockedOverQty(Long customerId, Long stationId, Long productId) {
        CustomerBarrelOver o = overMapper.getForUpdate(customerId, stationId, productId);
        return (o == null || o.getOverQty() == null) ? 0 : o.getOverQty();
    }

    /** 占用：顾客手上实际有几个桶（派生值） */
    public int occupiedQty(Long customerId, Long stationId, Long productId) {
        return rightQty(customerId, stationId, productId) + overQty(customerId, stationId, productId);
    }

    /** 应退桶款 = Σ remain_qty × unit_price（唯一真相源） */
    public BigDecimal rightAmount(Long customerId, Long stationId) {
        BigDecimal amt = lotMapper.sumRightAmount(customerId, stationId);
        return amt == null ? BigDecimal.ZERO : amt;
    }

    // =========================================================================
    // 写操作 1：配送完成（DEF-1 / DEF-2 / DEF-6）
    // =========================================================================

    /**
     * 配送完成时的桶账处理。
     *
     * <p><b>核心公式（旧实现漏了 rightPurchase 这一项）：</b></p>
     * <pre>newOver = oldOver + (delivered − returned) − rightPurchase</pre>
     *
     * <p>只有 rightPurchase（本单新购权益数）为 0 时，旧式
     * {@code delivered − returned} 才恰好正确——这就是主流程一直没暴露的原因。</p>
     *
     * <p><b>唯一校验是物理上限</b> {@code returned <= 占用_before}，
     * 不是旧的 {@code returned <= delivered}。后者会把「家里 3 个空桶全还了、这次只买 1 桶」
     * 这种合理场景直接拒单。over 允许被算成负数。</p>
     *
     * <p>首次桶装水订单无需特判：此时 right=0、over=0，买 3 送 3 收 0，
     * newOver = 0 + 3 − 0 − 3 = 0，公式自然成立。</p>
     *
     * @param returnedByProduct 按商品统计的实际收回空桶数（后端用 orderItemId 反查 productId，不信任客户端）
     */
    @Transactional
    public DeliveryOutcome applyDelivery(Long orderId, Long customerId, Long stationId,
                                         Map<Long, Integer> returnedByProduct, Long operatorId) {
        if (customerId == null || stationId == null) {
            throw new BusinessException("订单缺少客户或水站信息，无法登记桶账");
        }

        // 1) 本单送出：按订单明细统计
        // ⚠️ [2026-09-19] **只统计桶装水（category=1）**：本方法下面用 delta = delivered − returned −
        // rightPurchase 去改 customer_barrel_over，而非桶装商品既没有"配送中权益"也没有"还桶"入口
        // （returned 恒 0），于是 delivered 会被整笔记成"客户欠桶"，还会由 owed != 0 生成假桶异常单。
        // 品类判据的唯一实现在 util/BarrelScope —— 别在调用点写 category != 1（历史就是这么漏的）。
        Map<Long, Integer> deliveredByProduct = new LinkedHashMap<>();
        Map<Long, BigDecimal> depositByProduct = new HashMap<>();
        List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
        if (items != null) {
            for (OrderItem it : items) {
                if (it.getProductId() == null) continue;
                Product lineProduct = productMapper.getById(it.getProductId());
                if (!BarrelScope.isBarrel(lineProduct)) {
                    continue;
                }
                deliveredByProduct.merge(it.getProductId(),
                        it.getQuantity() == null ? 0 : it.getQuantity(), Integer::sum);
                if (it.getDeposit() != null) {
                    depositByProduct.putIfAbsent(it.getProductId(), it.getDeposit());
                }
            }
        }

        // 2) 本单新购权益：配送中记录（下单时算出的 shortage，自带商品维度）
        Map<Long, Integer> rightPurchaseByProduct = new LinkedHashMap<>();
        List<CustomerBarrelInTransit> pending = inTransitMapper.listPendingByOrderId(orderId);
        if (pending != null) {
            for (CustomerBarrelInTransit t : pending) {
                if (t.getProductId() == null) continue;
                rightPurchaseByProduct.merge(t.getProductId(),
                        t.getQty() == null ? 0 : t.getQty(), Integer::sum);
            }
        }

        // 3) 按商品逐个结算 over
        Set<Long> productIdSet = new LinkedHashSet<>();
        productIdSet.addAll(deliveredByProduct.keySet());
        productIdSet.addAll(returnedByProduct == null ? Collections.emptySet() : returnedByProduct.keySet());
        productIdSet.addAll(rightPurchaseByProduct.keySet());
        productIdSet.remove(null);

        // 与 returnEmpty 保持同一加锁顺序（按 productId 升序），避免并发事务互相等待成环。
        List<Long> productIds = new ArrayList<>(productIdSet);
        productIds.sort(Comparator.naturalOrder());

        DeliveryOutcome outcome = new DeliveryOutcome();
        for (Long pid : productIds) {
            int delivered = deliveredByProduct.getOrDefault(pid, 0);
            int returned = returnedByProduct == null ? 0 : returnedByProduct.getOrDefault(pid, 0);
            int rightPurchase = rightPurchaseByProduct.getOrDefault(pid, 0);
            if (returned < 0) throw new BusinessException("回收空桶数不能为负数");

            // [DEF-4] 先对 over 行加排他锁再读取/校验：并发送达结算在同一客户同一商品上
            // 同样存在「都读到旧 occupied → 都通过校验 → 重复冲减」的窗口，与纯还桶同类。
            overMapper.lockOrCreate(customerId, stationId, pid);

            int rightBefore = rightQty(customerId, stationId, pid);
            int overBefore = lockedOverQty(customerId, stationId, pid);
            int occupiedBefore = rightBefore + overBefore;

            // 唯一校验：物理上限。over 可以为负，这里不做任何非负约束。
            if (returned > occupiedBefore) {
                throw new BusinessException("回收空桶数(" + returned + ")超过该客户当前持有数(" + occupiedBefore + ")");
            }

            int delta = delivered - returned - rightPurchase;
            if (delta != 0) {
                overMapper.adjustOver(customerId, stationId, pid, delta);
                // [v29] over 一变就要同步欠桶起始时间：从<=0变>0写入、回到<=0清空、已是正数再增不重置。
                // 只影响展示（站长端欠桶台账/下单提醒），不参与任何校验。
                overMapper.syncOwedSince(customerId, stationId, pid);
            }
            outcome.add(pid, delivered, returned, rightPurchase, overBefore, overBefore + delta);
        }

        // 4) 配送中权益转正：建押金条(lot) + 增加权益，配送中记录标记 DELIVERED 而非删除
        if (pending != null) {
            for (CustomerBarrelInTransit t : pending) {
                int qty = t.getQty() == null ? 0 : t.getQty();
                if (t.getProductId() == null || qty <= 0) continue;
                BigDecimal unitPrice = resolveUnitPrice(t, depositByProduct);
                createLot(customerId, stationId, t.getProductId(), unitPrice, qty,
                        t.getRelatedOrderId() != null ? t.getRelatedOrderId() : orderId, operatorId);
                inTransitMapper.updateStatus(t.getId(), "DELIVERED");
            }
        }
        return outcome;
    }

    /**
     * 单价优先级：配送中表下单时快照 &gt; 订单明细 deposit 快照 &gt; 当前商品押金价。
     *
     * <p>[DEF-2] 只有<b>大于 0</b> 的候选值才算有效：桶装水(category=1)的
     * {@code order_item.deposit} 被刻意记 0（押金按下单缺桶数单独收，不走明细），
     * 若把 0 当成有效回退值，押金条单价会被写成 0 → 退桶退 ¥0。
     * 因此 0 一律视为「无快照」，继续向后回退到商品当前押金价。</p>
     *
     * <p>[2026-09-16 商品与库存重构] 第三级兜底从"全局 product.deposit"改为
     * <b>本站押金</b>（{@code inventory.deposit_price} 优先，走
     * {@code PriceUtil#calcDeposit}）：否则本站把押金调到 50、历史缺快照的批次仍按
     * 通用库参考押金 30 建条，退桶金额就错了。前两级仍是快照，改价不影响正常批次。</p>
     */
    private BigDecimal resolveUnitPrice(CustomerBarrelInTransit t, Map<Long, BigDecimal> depositByProduct) {
        if (t.getUnitPrice() != null && t.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            return t.getUnitPrice();
        }
        BigDecimal d = depositByProduct.get(t.getProductId());
        if (d != null && d.compareTo(BigDecimal.ZERO) > 0) {
            return d;
        }
        Product p = productMapper.getById(t.getProductId());
        Inventory inv = inventoryMapper.getByStationAndProduct(t.getStationId(), t.getProductId());
        return PriceUtil.calcDeposit(p, inv);
    }

    /** lot.note 为 varchar(200)：超长在 STRICT_TRANS_TABLES 下会直接 1406，这里统一截断 */
    private static String truncateNote(String note) {
        if (note == null) return null;
        return note.length() <= 200 ? note : note.substring(0, 200);
    }

    /**
     * 批次来源：订单购买 / 历史迁移 / 人工补录。
     *
     * <p>schema 早已为这三种来源预留了列（`customer_barrel_lot.source_type` / `price_source` / `is_migrated`），
     * 但旧实现把三者<b>写死成「订单购买」</b>（`setSourceType(1)` / `setPriceSource(1)` / `setIsMigrated(0)`），
     * 于是站长补录的历史账无法表达「这个单价是推断出来的」，退款时也就无法触发二次确认。</p>
     */
    public static class LotOrigin {

        /** 1订单购买 2历史迁移 3人工补录 */
        private final int sourceType;
        /** 1订单实付 2当时商品押金 3当前商品押金(兜底推断) */
        private final int priceSource;
        /** true = 单价为推断值，退款需二次确认（对应 is_migrated=1） */
        private final boolean migrated;
        private final String note;

        public LotOrigin(int sourceType, int priceSource, boolean migrated, String note) {
            this.sourceType = sourceType;
            this.priceSource = priceSource;
            this.migrated = migrated;
            this.note = note;
        }

        /** 订单链路：单价来自订单实付，非推断值（与旧实现行为逐字节一致） */
        public static LotOrigin fromOrder() {
            return new LotOrigin(1, 1, false, null);
        }

        /**
         * 站长人工补录。
         *
         * @param priceIsInferred true = 单价取自商品当前押金（推断值），退款需二次确认
         */
        public static LotOrigin manual(boolean priceIsInferred, String note) {
            return new LotOrigin(3, priceIsInferred ? 3 : 1, priceIsInferred, note);
        }

        /** 历史迁移：单价一律为推断值 */
        public static LotOrigin historyMigration(String note) {
            return new LotOrigin(2, 3, true, note);
        }

        public int getSourceType() { return sourceType; }
        public int getPriceSource() { return priceSource; }
        public boolean isMigrated() { return migrated; }
        public String getNote() { return note; }
    }

    /** 新建权益批次（押金条），并同步 customer_barrel_asset 的数量与派生金额 */
    @Transactional
    public CustomerBarrelLot createLot(Long customerId, Long stationId, Long productId,
                                       BigDecimal unitPrice, int qty, Long relatedOrderId, Long operatorId) {
        return createLot(customerId, stationId, productId, unitPrice, qty, relatedOrderId, operatorId,
                LotOrigin.fromOrder());
    }

    /**
     * 带来源的建批次。
     * <p>订单/配送链路传 {@link LotOrigin#fromOrder()}；站长补录历史账传 {@link LotOrigin#manual}；
     * 迁移导入传 {@link LotOrigin#historyMigration}。</p>
     */
    @Transactional
    public CustomerBarrelLot createLot(Long customerId, Long stationId, Long productId,
                                       BigDecimal unitPrice, int qty, Long relatedOrderId, Long operatorId,
                                       LotOrigin origin) {
        if (qty <= 0) throw new BusinessException("新增权益数必须大于 0");
        LotOrigin src = origin != null ? origin : LotOrigin.fromOrder();
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;

        CustomerBarrelLot lot = new CustomerBarrelLot();
        // [DEF-1] lot_no 列为 varchar(32)，占位号必须 ≤32 字符。
        // 旧实现写 "TMP-" + UUID(36 字符含连字符) = 40 字符，在 STRICT_TRANS_TABLES 下
        // 直接报 ERROR 1406 Data too long，导致「配送完成建押金条」整条链路不可用
        // （首次购买桶装水的顾客拿不到桶权益、后续无法退桶退款）。
        // 取 UUID 去掉连字符后的前 24 位十六进制（96 bit 熵）作占位，长度 28，足够唯一；
        // 插入后立即用自增 id 生成正式凭证号 DPyyyymmdd-000001（15 字符）。
        lot.setLotNo("TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        lot.setCustomerId(customerId);
        lot.setStationId(stationId);
        lot.setProductId(productId);
        lot.setUnitPrice(price);
        lot.setQty(qty);
        lot.setRemainQty(qty);
        lot.setSourceType(src.getSourceType());
        lot.setPriceSource(src.getPriceSource());
        lot.setRelatedOrderId(relatedOrderId);
        lot.setStatus(1);
        lot.setIsMigrated(src.isMigrated() ? 1 : 0);
        lot.setOperatorId(operatorId);
        // note 列为 varchar(200)：补录说明可能较长，超长直接报 1406，这里先截断（不静默丢关键信息，只截尾）
        lot.setNote(truncateNote(src.getNote()));
        lot.setCreateTime(LocalDateTime.now());
        lotMapper.insert(lot);

        String lotNo = String.format("DP%s-%06d",
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")),
                lot.getId());
        lotMapper.setLotNo(lot.getId(), lotNo);
        lot.setLotNo(lotNo);

        // 权益汇总（数量 + 派生金额）
        CustomerBarrelAsset asset = assetMapper.getByCustomerAndProductForUpdate(customerId, productId, stationId);
        if (asset == null) {
            CustomerBarrelAsset a = new CustomerBarrelAsset();
            a.setCustomerId(customerId);
            a.setProductId(productId);
            a.setStationId(stationId);
            a.setQuantity(qty);
            a.setRightAmount(price.multiply(BigDecimal.valueOf(qty)));
            a.setUpdateTime(LocalDateTime.now());
            assetMapper.insert(a);
        } else {
            assetMapper.increaseQuantity(asset.getId(), qty);
            assetMapper.addRightAmount(asset.getId(), price.multiply(BigDecimal.valueOf(qty)));
        }
        return lot;
    }

    // =========================================================================
    // 写操作 2：纯还桶（只冲 over，不扣权益、不退款）
    // =========================================================================

    /**
     * 纯还桶：顾客交回空桶但不带走满桶。
     *
     * <p><b>只动 over，允许变负。</b>不动权益、不动 lot、不动押金账户、不产生退款。
     * 退款只属于「终止权益」的退桶流程，见 {@link #consumeLots}。</p>
     *
     * <p>唯一校验同样是物理上限 {@code qty <= 占用}；因为占用 ≥ 0 恒成立，
     * 所以 over 自动满足 {@code over >= −权益}，无需任何 clamp。</p>
     */
    @Transactional
    public List<OverChange> returnEmpty(Long customerId, Long stationId,
                                        List<ItemQty> items, Long operatorId) {
        if (items == null || items.isEmpty()) throw new BusinessException("请填写还桶数量");

        // 按 productId 排序后再逐个加锁：并发事务的加锁顺序一致，避免互相等待成环（死锁）。
        List<ItemQty> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparing(ItemQty::getProductId,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        List<OverChange> changes = new ArrayList<>();
        for (ItemQty it : ordered) {
            if (it.getProductId() == null) throw new BusinessException("还桶必须指定商品");
            int qty = it.getQty() == null ? 0 : it.getQty();
            if (qty <= 0) continue;

            // [DEF-4] 先加排他行锁，再用【当前读】取 over，最后才校验。
            // 旧实现是「先读 occupied → 再原子 adjustOver」，两个并发请求都读到 occupied=1、
            // 都通过 qty(1) <= occupied(1)，各自把 over 冲成 -1 → 最终 over=-2，占用=权益(1)+(-2)=-1，
            // 物理上不可能，桶账被冲穿。
            //
            // 注意：只加锁还不够。MySQL 默认 REPEATABLE READ，普通 SELECT 走事务快照；
            // 第二个事务即使等到第一个提交后拿到了锁，普通 SELECT 读到的仍是它自己快照里的旧值
            // （表现为两个请求都上报 overBefore=0、双双通过）。
            // 所以必须用加锁读（getForUpdate / SELECT ... FOR UPDATE，当前读）取最新已提交值。
            Long pid = it.getProductId();
            overMapper.lockOrCreate(customerId, stationId, pid);

            int before = lockedOverQty(customerId, stationId, pid);
            int occupied = rightQty(customerId, stationId, pid) + before;
            if (qty > occupied) {
                throw new BusinessException("交回数(" + qty + ")超过该客户当前持有数(" + occupied + ")");
            }
            overMapper.adjustOver(customerId, stationId, pid, -qty);
            // [v29] 还桶可能把欠桶还清 → 同步 owed_since（见 syncOwedSince 的注释）
            overMapper.syncOwedSince(customerId, stationId, pid);
            changes.add(new OverChange(pid, qty, before, before - qty, operatorId));
        }
        if (changes.isEmpty()) throw new BusinessException("还桶数量必须大于 0");
        return changes;
    }

    // =========================================================================
    // 写操作 3：退桶（终止权益）—— FIFO 核销批次，金额只认批次单价
    // =========================================================================

    /**
     * 按 FIFO 核销权益批次，返回应退金额。
     *
     * <p>退款金额 = Σ qty_i × lot_i.unit_price，<b>与 over 完全无关</b>：
     * over&lt;0 不会多退一分钱，也不会阻止退款。</p>
     *
     * @param preferLotIds 站长指定的批次（为空则纯 FIFO）
     * @param dryRun       true=只试算不落库（preview 接口）
     */
    @Transactional
    public LotConsumption consumeLots(Long customerId, Long stationId, Long productId, int qty,
                                      List<Long> preferLotIds, boolean dryRun) {
        if (qty <= 0) throw new BusinessException("退桶数必须大于 0");

        int right = rightQty(customerId, stationId, productId);
        if (qty > right) {
            throw new BusinessException("退桶数(" + qty + ")超过拥有的桶权益数(" + right + ")");
        }

        List<CustomerBarrelLot> lots = lotMapper.listAvailableForUpdate(customerId, stationId, productId);
        if (preferLotIds != null && !preferLotIds.isEmpty()) {
            lots.sort(Comparator.comparingInt(l -> {
                int i = preferLotIds.indexOf(l.getId());
                return i < 0 ? Integer.MAX_VALUE : i;
            }));
        }

        LotConsumption result = new LotConsumption();
        int remaining = qty;
        BigDecimal amount = BigDecimal.ZERO;
        for (CustomerBarrelLot lot : lots) {
            if (remaining <= 0) break;
            int take = Math.min(remaining, lot.getRemainQty() == null ? 0 : lot.getRemainQty());
            if (take <= 0) continue;

            if (!dryRun) {
                int affected = lotMapper.consume(lot.getId(), take);
                if (affected == 0) {
                    throw new BusinessException("权益批次核销失败（可能已被其他操作占用），请重试");
                }
                lotMapper.markExhausted(lot.getId());
            }
            BigDecimal line = lot.getUnitPrice().multiply(BigDecimal.valueOf(take));
            amount = amount.add(line);
            result.getDetails().add(new LotConsumption.Detail(lot.getId(), take, lot.getUnitPrice(), line));
            remaining -= take;
        }
        if (remaining > 0) throw new BusinessException("可退权益不足，缺少 " + remaining + " 桶");

        result.setAmount(amount);
        result.setHasMigratedPrice(lots.stream().anyMatch(l -> Integer.valueOf(1).equals(l.getIsMigrated())));
        return result;
    }

    /** 退桶落库后同步权益汇总（数量与金额都要减，且必须校验 affected） */
    @Transactional
    public void decreaseRight(Long customerId, Long stationId, Long productId, int qty, BigDecimal amount) {
        CustomerBarrelAsset asset = assetMapper.getByCustomerAndProductForUpdate(customerId, productId, stationId);
        if (asset == null) throw new BusinessException("该客户无此桶权益记录");
        int affected = assetMapper.decreaseQuantity(asset.getId(), qty);
        if (affected == 0) throw new BusinessException("权益数量不足，扣减失败");
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            int am = assetMapper.subRightAmount(asset.getId(), amount);
            if (am == 0) throw new BusinessException("可退金额不足，扣减失败");
        }
    }

    // =========================================================================
    // 写操作 4：订正欠桶（站长资产调整单专用）
    // =========================================================================

    /**
     * 订正欠桶：按 delta 调整 over（正=补记欠桶，负=核销欠桶）。
     *
     * <p><b>为什么必须走这里</b>：{@code customer_barrel_over} 是桶账的唯一欠桶真相源，
     * 而并发写它必须先加排他行锁、再用<b>当前读</b>取最新值——MySQL 默认 REPEATABLE READ 下，
     * 普通 SELECT 读到的是事务开始时的快照，第二个事务即使等到了锁也会读到旧值（DEF-4）。
     * 直接调 mapper 的 {@code adjustOver} 会绕过这套协议。</p>
     *
     * <p>唯一校验是物理下限：调整后必须满足 {@code 占用 = 权益 + over ≥ 0}。
     * over 本身允许为负（多还桶 / 水站暂存），但不能负到让占用为负。</p>
     *
     * @param delta 正=补记欠桶，负=核销欠桶；0 视为非法（调用方不应发起空调整）
     * @return 变更结果（含变更前后 over）
     */
    @Transactional
    public OverChange adjustOver(Long customerId, Long stationId, Long productId, int delta, Long operatorId) {
        if (delta == 0) throw new BusinessException("欠桶调整量不能为 0");
        if (customerId == null || stationId == null || productId == null) {
            throw new BusinessException("欠桶调整必须指定客户、水站与商品");
        }

        // 与 returnEmpty / applyDelivery 同一套协议：先 upsert 建行（FOR UPDATE 对不存在的行不加锁），
        // 再当前读取最新已提交值，最后才校验。
        overMapper.lockOrCreate(customerId, stationId, productId);
        int before = lockedOverQty(customerId, stationId, productId);
        int after = before + delta;

        int right = rightQty(customerId, stationId, productId);
        if (right + after < 0) {
            throw new BusinessException("该调整会使占用为负（权益 " + right + " + 调整后 over " + after
                    + " < 0）；核销欠桶的数量不能超过（权益 + 当前 over）=" + (right + before));
        }

        overMapper.adjustOver(customerId, stationId, productId, delta);
        // [v29] 人工调整同样要维护欠桶起始时间（补记欠桶=开始计时；核销=清空/继续计时）
        overMapper.syncOwedSince(customerId, stationId, productId);
        return new OverChange(productId, delta, before, after, operatorId);
    }

    // =========================================================================
    // 内部 DTO
    // =========================================================================

    @Data
    public static class ItemQty {
        private Long productId;
        private Integer qty;
        public ItemQty() {}
        public ItemQty(Long productId, Integer qty) { this.productId = productId; this.qty = qty; }
    }

    @Data
    public static class OverChange {
        private Long productId;
        private Integer qty;
        private Integer overBefore;
        private Integer overAfter;
        private Long operatorId;
        public OverChange() {}
        public OverChange(Long productId, Integer qty, Integer overBefore, Integer overAfter, Long operatorId) {
            this.productId = productId; this.qty = qty;
            this.overBefore = overBefore; this.overAfter = overAfter; this.operatorId = operatorId;
        }
    }

    /** 配送完成的桶账结果（按商品） */
    @Data
    public static class DeliveryOutcome {
        private final List<Line> lines = new ArrayList<>();

        public void add(Long productId, int delivered, int returned, int rightPurchase,
                        int overBefore, int overAfter) {
            lines.add(new Line(productId, delivered, returned, rightPurchase, overBefore, overAfter));
        }

        /** 本单净欠桶变化（正=欠桶增加，负=欠桶减少 / 多还），仅用于日志与差异说明 */
        public int totalOverDelta() {
            int sum = 0;
            for (Line l : lines) sum += l.overAfter - l.overBefore;
            return sum;
        }

        @Data
        public static class Line {
            private Long productId;
            private Integer delivered;
            private Integer returned;
            private Integer rightPurchase;
            private Integer overBefore;
            private Integer overAfter;
            public Line() {}
            public Line(Long productId, Integer delivered, Integer returned, Integer rightPurchase,
                        Integer overBefore, Integer overAfter) {
                this.productId = productId; this.delivered = delivered; this.returned = returned;
                this.rightPurchase = rightPurchase; this.overBefore = overBefore; this.overAfter = overAfter;
            }
        }
    }

    /** 批次核销结果 */
    @Data
    public static class LotConsumption {
        private BigDecimal amount = BigDecimal.ZERO;
        private boolean hasMigratedPrice;
        private final List<Detail> details = new ArrayList<>();

        @Data
        public static class Detail {
            private Long lotId;
            private Integer qty;
            private BigDecimal unitPrice;
            private BigDecimal amount;
            public Detail() {}
            public Detail(Long lotId, Integer qty, BigDecimal unitPrice, BigDecimal amount) {
                this.lotId = lotId; this.qty = qty; this.unitPrice = unitPrice; this.amount = amount;
            }
        }
    }
}
