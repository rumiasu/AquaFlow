package com.example.aquaflow.service;

import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
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
        Map<Long, Integer> deliveredByProduct = new LinkedHashMap<>();
        Map<Long, BigDecimal> depositByProduct = new HashMap<>();
        List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
        if (items != null) {
            for (OrderItem it : items) {
                if (it.getProductId() == null) continue;
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
        Set<Long> productIds = new LinkedHashSet<>();
        productIds.addAll(deliveredByProduct.keySet());
        productIds.addAll(returnedByProduct == null ? Collections.emptySet() : returnedByProduct.keySet());
        productIds.addAll(rightPurchaseByProduct.keySet());

        DeliveryOutcome outcome = new DeliveryOutcome();
        for (Long pid : productIds) {
            int delivered = deliveredByProduct.getOrDefault(pid, 0);
            int returned = returnedByProduct == null ? 0 : returnedByProduct.getOrDefault(pid, 0);
            int rightPurchase = rightPurchaseByProduct.getOrDefault(pid, 0);
            if (returned < 0) throw new BusinessException("回收空桶数不能为负数");

            int rightBefore = rightQty(customerId, stationId, pid);
            int overBefore = overQty(customerId, stationId, pid);
            int occupiedBefore = rightBefore + overBefore;

            // 唯一校验：物理上限。over 可以为负，这里不做任何非负约束。
            if (returned > occupiedBefore) {
                throw new BusinessException("回收空桶数(" + returned + ")超过该客户当前持有数(" + occupiedBefore + ")");
            }

            int delta = delivered - returned - rightPurchase;
            if (delta != 0) {
                overMapper.adjustOver(customerId, stationId, pid, delta);
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

    /** 单价优先级：配送中表下单时快照 > 订单明细 deposit 快照 > 当前商品押金价 */
    private BigDecimal resolveUnitPrice(CustomerBarrelInTransit t, Map<Long, BigDecimal> depositByProduct) {
        if (t.getUnitPrice() != null) return t.getUnitPrice();
        BigDecimal d = depositByProduct.get(t.getProductId());
        if (d != null) return d;
        Product p = productMapper.getById(t.getProductId());
        return (p != null && p.getDeposit() != null) ? p.getDeposit() : BigDecimal.ZERO;
    }

    /** 新建权益批次（押金条），并同步 customer_barrel_asset 的数量与派生金额 */
    @Transactional
    public CustomerBarrelLot createLot(Long customerId, Long stationId, Long productId,
                                       BigDecimal unitPrice, int qty, Long relatedOrderId, Long operatorId) {
        if (qty <= 0) throw new BusinessException("新增权益数必须大于 0");
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;

        CustomerBarrelLot lot = new CustomerBarrelLot();
        lot.setLotNo("TMP-" + UUID.randomUUID()); // 插入后按 id 生成正式凭证号
        lot.setCustomerId(customerId);
        lot.setStationId(stationId);
        lot.setProductId(productId);
        lot.setUnitPrice(price);
        lot.setQty(qty);
        lot.setRemainQty(qty);
        lot.setSourceType(1);
        lot.setPriceSource(1);
        lot.setRelatedOrderId(relatedOrderId);
        lot.setStatus(1);
        lot.setIsMigrated(0);
        lot.setOperatorId(operatorId);
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
        List<OverChange> changes = new ArrayList<>();
        for (ItemQty it : items) {
            if (it.getProductId() == null) throw new BusinessException("还桶必须指定商品");
            int qty = it.getQty() == null ? 0 : it.getQty();
            if (qty <= 0) continue;

            int occupied = occupiedQty(customerId, stationId, it.getProductId());
            if (qty > occupied) {
                throw new BusinessException("交回数(" + qty + ")超过该客户当前持有数(" + occupied + ")");
            }
            int before = overQty(customerId, stationId, it.getProductId());
            overMapper.adjustOver(customerId, stationId, it.getProductId(), -qty);
            changes.add(new OverChange(it.getProductId(), qty, before, before - qty, operatorId));
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
