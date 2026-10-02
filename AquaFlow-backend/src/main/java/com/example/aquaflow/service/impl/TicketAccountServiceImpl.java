package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.PriceUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TicketAccountServiceImpl implements TicketAccountService {
    @Autowired private com.example.aquaflow.service.BarrelBusinessPolicy barrelPolicy;
    @Autowired private com.example.aquaflow.service.BarrelLedgerService barrelLedger;

    private static final Logger log = LoggerFactory.getLogger(TicketAccountServiceImpl.class);

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private com.example.aquaflow.service.TicketPurchaseFenceService purchaseFenceService;

    @Autowired
    private com.example.aquaflow.mapper.InventoryMapper inventoryMapper;

    /**
     * 水票批次账（v36）：余额的真相源是 {@code Σ ticket_lot.remain_qty}，
     * 本类所有改变水票数量的地方都必须同步走它，否则对账 E8 立刻报不平。
     */
    @Autowired
    private com.example.aquaflow.service.TicketLotService ticketLotService;

    /** 水票档位（v36）：站级定价结构，仅用于在线购票定价与档位校验 */
    @Autowired
    private com.example.aquaflow.mapper.TicketPackageMapper ticketPackageMapper;

    /** 站级统一折扣档（v58）—— 只用于购票时的定价与"本站有没有配"的判据 */
    @Autowired
    private com.example.aquaflow.mapper.StationTicketDiscountMapper stationTicketDiscountMapper;

    /** 档位判据（定制 or 统一）的唯一实现 */
    @Autowired
    private TicketTierService ticketTierService;

    @Override
    public List<TicketAccount> listByCustomerAndStation(Long customerId, Long stationId) {
        return ticketAccountMapper.listByCustomerAndStation(customerId, stationId);
    }

    /** 该客户在本站该商品账户里的剩余张数；没有账户或没配站都按 0 处理（"没有票"）。 */
    @Override
    public int balanceOf(Long customerId, Long productId, Long stationId) {
        if (customerId == null || productId == null || stationId == null) {
            return 0;
        }
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        return account != null && account.getRemainQuantity() != null ? account.getRemainQuantity() : 0;
    }

    /**
     * 本站的"统一折扣是否生效"：配了**上架**的 {@code station_ticket_discount} 档位即为生效。
     *
     * <p>不新增开关列的原因：产品口径是「统一水票是<b>可以设置项</b>」——
     * 站长把档位全下架，统一折扣自然就不再生效；多一个开关列就多一处可能与档位状态打架的真值。</p>
     *
     * <p>⚠️ 别把它当成"该商品能用票"的判据：那还要看该商品是否走定制
     * （唯一实现 {@code TicketTierService.usesCustomTicket}，"定制优先"）。</p>
     */
    @Override
    public boolean unifiedDiscountConfigured(Long stationId) {
        return stationId != null && stationTicketDiscountMapper.countOnShelf(stationId) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addTicket(Long customerId, Long productId, Integer qty, Long stationId) {
        // [AQ-001] 水票按 (customer, product, station) 三维隔离，任缺一维隔离即失效。
        // 尤其 stationId 为 null 时，MySQL 唯一键中 NULL 互不相等，可插出多行 —— 水票串站 / 重复入账。
        // 数据库列已改为 NOT NULL 兜底，这里提前给出可读的报错。
        if (customerId == null) {
            throw new BusinessException("客户ID不能为空");
        }
        if (productId == null) {
            throw new BusinessException("商品ID不能为空");
        }
        if (stationId == null) {
            throw new BusinessException("水站ID不能为空，水票必须归属到具体水站");
        }
        if (qty == null || qty <= 0) {
            throw new BusinessException("水票数量必须大于0");
        }
        // [AQ-051] 单次入账上限：防止误操作或脚本把水票余额刷成天文数字（原实现无任何上限）
        if (qty > 5000) {
            throw new BusinessException("单次水票数量不能超过 5000");
        }

        // 水票按水站隔离
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            account = new TicketAccount();
            account.setCustomerId(customerId);
            account.setProductId(productId);
            account.setStationId(stationId);
            account.setRemainQuantity(qty);
            account.setUpdateTime(LocalDateTime.now());
            ticketAccountMapper.insert(account);
        } else {
            ticketAccountMapper.incrementQuantity(account.getId(), qty);
        }

        // [v36] 先建批次、再写流水：批次的单价快照就是流水 unitPrice 的来源。
        // 站长加票没有真实付款，所以单价取**本站当前水票价**并标记为推断值（退票需二次确认）——
        // 与桶账 LotOrigin.manual(priceIsInferred=true) 同一口径。
        BigDecimal addUnitPrice = inferredUnitPrice(stationId, productId);
        com.example.aquaflow.entity.TicketLot addLot = ticketLotService.createLot(
                customerId, stationId, productId, addUnitPrice, qty,
                com.example.aquaflow.entity.TicketLot.SourceType.MANUAL,
                com.example.aquaflow.entity.TicketLot.PriceSource.INFERRED,
                true, null, "站长加票（单价为推断值）");

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(qty);
        record.setDecreaseQty(0);
        record.setOrderId(null);
        record.setSource("购买");
        record.setTicketSource(1);
        record.setUnitPrice(addLot.getUnitPrice());
        record.setTicketLotId(addLot.getId());
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);
        // [AQ-051] 水票入账审计日志（谁给谁加了多少张）
        log.info("[AQ-051] 水票入账: customerId={}, productId={}, stationId={}, qty={}, operatorId={}",
                customerId, productId, stationId, qty, com.example.aquaflow.util.AuthContext.getUserId());
    }

    /**
     * 站长资产调整单专用：按 delta 调整水票（正=补录，负=扣减）。
     *
     * <p>不复用 {@link #addTicket}（无幂等键）与 {@link #consumeTicket}（它的幂等键是**客户端**传的，
     * 调整场景的键必须由服务端签发、客户端编不出来）：调整由站长发起、键是调整单号本身，
     * 幂等由 {@code uk_ticket_adjustment(adjustment_id, product_id, source)} 兜底 ——
     * 重复执行会命中唯一键抛异常并回滚整个事务，而不是静默再加一次。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustTicket(Long customerId, Long productId, Integer delta, Long stationId, Long adjustmentId) {
        if (customerId == null || productId == null || stationId == null) {
            throw new BusinessException("水票调整必须指定客户、商品与水站");
        }
        if (delta == null || delta == 0) {
            throw new BusinessException("水票调整量不能为 0");
        }
        if (adjustmentId == null) {
            throw new BusinessException("水票调整必须关联调整单（否则无法保证幂等）");
        }
        int qty = Math.abs(delta);
        boolean increase = delta > 0;
        String source = increase ? "人工调整补入" : "人工调整扣减";

        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);

        // [v36] 人工调整也必须过批次账 —— 它同样改变"客户手上有多少张票"，
        // 不过批次的话对账 E8 会在每次站长订正后报不平。
        BigDecimal adjustUnitPrice;
        Long adjustLotId;
        if (increase) {
            if (account == null) {
                account = new TicketAccount();
                account.setCustomerId(customerId);
                account.setProductId(productId);
                account.setStationId(stationId);
                account.setRemainQuantity(0);
                account.setUpdateTime(LocalDateTime.now());
                ticketAccountMapper.insert(account);
            }
            // 人工补录没有真实付款，单价取本站当前水票价并标记为推断值（与 addTicket 同口径）
            BigDecimal price = inferredUnitPrice(stationId, productId);
            com.example.aquaflow.entity.TicketLot lot = ticketLotService.createLot(
                    customerId, stationId, productId, price, qty,
                    com.example.aquaflow.entity.TicketLot.SourceType.MANUAL,
                    com.example.aquaflow.entity.TicketLot.PriceSource.INFERRED,
                    true, null, "资产调整单补录（单价为推断值）");
            ticketAccountMapper.incrementQuantity(account.getId(), qty);
            adjustUnitPrice = lot.getUnitPrice();
            adjustLotId = lot.getId();
        } else {
            if (account == null) {
                throw new BusinessException("该客户在本站无此商品的水票账户，无法扣减");
            }
            int affected = ticketAccountMapper.decrementQuantity(account.getId(), qty);
            if (affected == 0) {
                throw new BusinessException("水票余额不足，无法扣减");
            }
            com.example.aquaflow.service.TicketLotService.ConsumeResult cr =
                    ticketLotService.consumeFifo(customerId, stationId, productId, qty);
            adjustUnitPrice = cr.getWeightedUnitPrice();
            adjustLotId = cr.getSingleLotId();
        }

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(increase ? qty : 0);
        record.setDecreaseQty(increase ? 0 : qty);
        record.setOrderId(null);
        record.setSource(source);
        record.setTicketSource(1);
        record.setUnitPrice(adjustUnitPrice);
        record.setTicketLotId(adjustLotId);
        record.setCreateTime(LocalDateTime.now());
        record.setAdjustmentId(adjustmentId);
        ticketRecordMapper.insert(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void creditPurchasedTickets(Long customerId, Long productId, Integer qty, Long stationId,
                                       Long paymentRecordId, BigDecimal paidAmount) {
        if (qty == null || qty <= 0) {
            throw new BusinessException("入账张数必须大于 0");
        }
        // ⚠️ 顺序：**先确保账户存在，再建批次**。createLot 内部会 refreshRightAmount，
        // 它在账户不存在时直接返回（没有可同步的汇总行），批次的金额就同步不上去。
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            account = new TicketAccount();
            account.setCustomerId(customerId);
            account.setProductId(productId);
            account.setStationId(stationId);
            account.setRemainQuantity(0);
            account.setUpdateTime(LocalDateTime.now());
            ticketAccountMapper.insert(account);
        }

        // 单价 = 实付均价。**这是整个批次模型里最可信的一个价格来源**：
        // 它不依赖站级配置，是客户真金白银付出来的。档位套餐下它与站级单张价不同 ——
        // 用站级价记，退票时就会按 9.00 退给一个只付了 8.00 的客户。
        BigDecimal unit = (paidAmount != null && paidAmount.compareTo(BigDecimal.ZERO) > 0)
                ? paidAmount.divide(BigDecimal.valueOf(qty), 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        com.example.aquaflow.entity.TicketLot lot = ticketLotService.createLot(
                customerId, stationId, productId, unit, qty,
                com.example.aquaflow.entity.TicketLot.SourceType.PURCHASE,
                com.example.aquaflow.entity.TicketLot.PriceSource.PAID,
                false, paymentRecordId, "在线购票（实付均价）");

        ticketAccountMapper.incrementQuantity(account.getId(), qty);

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(qty);
        record.setDecreaseQty(0);
        record.setOrderId(null);
        record.setSource("购买");
        record.setTicketSource(1);
        record.setUnitPrice(lot.getUnitPrice());
        record.setTicketLotId(lot.getId());
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);

        log.info("[v36] 在线购票入账: customerId={}, productId={}, stationId={}, qty={}, 实付均价={}, lotNo={}",
                customerId, productId, stationId, qty, lot.getUnitPrice(), lot.getLotNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId) {
        if (customerId == null || productId == null || stationId == null || qty == null || qty <= 0) {
            throw new BusinessException("退款水票参数不合法");
        }
        // 账户恒为**该商品**（{@code productId}）。2026-09-20 产品澄清"按统一折扣买的票只能抵那款水"
        // 之后，同一张订单里"订单行商品"与"扣票账户"必然相等 —— v54 曾为此加过
        // ticket_record.account_product_id（当时把统一票做成了站级通用账户），该列已由 v59 撤回。
        // 水票按水站隔离：先取（不存在则建），再回补
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            account = new TicketAccount();
            account.setCustomerId(customerId);
            account.setProductId(productId);
            account.setStationId(stationId);
            account.setRemainQuantity(qty);
            account.setUpdateTime(LocalDateTime.now());
            ticketAccountMapper.insert(account);
        } else {
            ticketAccountMapper.incrementQuantity(account.getId(), qty);
        }

        // [v36] 回补批次。单价必须取**当时消耗的批次单价**（流水里记着），不能取当前价 ——
        // 否则站长在这中间调过一次价，客户拿回的票就凭空变了值。
        // 查不到消费流水时（历史单 / 人工退款）退化为"当前站级水票价"并标记为推断值，退票需二次确认。
        TicketRecord consumeRecord = orderId != null
                ? ticketRecordMapper.getConsumeRecord(orderId, productId) : null;
        BigDecimal restorePrice;
        boolean restoreInferred;
        if (consumeRecord != null && consumeRecord.getUnitPrice() != null) {
            restorePrice = consumeRecord.getUnitPrice();
            restoreInferred = false;
        } else {
            Product rp = productMapper.getById(productId);
            com.example.aquaflow.entity.Inventory rInv = inventoryMapper.getByStationAndProduct(stationId, productId);
            restorePrice = rp == null ? BigDecimal.ZERO : PriceUtil.calcUnitPrice(rp, rInv, PayMethod.TICKET);
            restoreInferred = true;
        }
        com.example.aquaflow.entity.TicketLot restoreLot = ticketLotService.createLot(
                customerId, stationId, productId, restorePrice, qty,
                com.example.aquaflow.entity.TicketLot.SourceType.REFUND_RESTORE,
                restoreInferred ? com.example.aquaflow.entity.TicketLot.PriceSource.INFERRED
                                : com.example.aquaflow.entity.TicketLot.PriceSource.PAID,
                restoreInferred, orderId, "订单取消/退款回补水票");

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(qty);
        record.setDecreaseQty(0);
        record.setOrderId(orderId);
        record.setSource("退款");
        record.setTicketSource(1);
        record.setUnitPrice(restoreLot.getUnitPrice());
        record.setTicketLotId(restoreLot.getId());
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);
    }

    /**
     * 扣票（订单内扣票 + 站长手工扣票共用）。
     *
     * <p><b>[v70 / 台账 F-24] 判据不是"有没有订单"，而是"有没有客户端幂等键"</b>：</p>
     * <ul>
     *   <li><code>idempotencyKey</code> 非空（HTTP 端点 {@code /api/tickets/consume} 一律如此，
     *       DTO 上有 {@code @NotBlank}）⇒ 走<b>键幂等</b>：先按 {@code (customer_id, key)} 命中即
     *       原样返回、不再扣一次；并发由 {@code uk_ticket_consume_idem} 兜底。这条路径
     *       <b>不区分有没有 orderId</b> —— 端点允许客户端传 orderId，那条分支上
     *       {@code uk_ticket_consume} 只在"插入第二条同 (order,product,'消费') 流水"时报错，
     *       而余额<b>已经扣过一次了</b>，靠它兜不住。</li>
     *   <li><code>idempotencyKey</code> 为空 ⇒ 只允许订单内扣票（调用方 {@code PaymentServiceImpl}），
     *       幂等交给 {@code uk_ticket_consume(order_id, product_id, source)}（它自己会先查
     *       {@code countConsumeByOrderAndProduct}）。<b>无订单又不给键 = 数据库层零保护</b>
     *       （MySQL 唯一键中 NULL 互不冲突），故直接拒绝。</li>
     * </ul>
     *
     * <p><b>为什么带键路径撞 1062 时必须抛异常</b>：本方法在事务内，而余额扣减与批次 FIFO 消耗
     * <b>已经发生</b>。若像旧实现那样捕获后继续提交，结果是"扣了两次、只留一条流水" ——
     * 正是 F-24 要修的重复扣。抛出即整笔回滚，输掉竞争的那一次连同它的扣减与批次消耗一起撤销
     * （v33 的 {@code purchaseTicket} 同一选择）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consumeTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId,
                              String idempotencyKey) {
        if (qty == null || qty <= 0) {
            throw new BusinessException("水票扣减张数必须大于 0");
        }
        // ===== [v70] 客户端幂等键：无订单扣票必传；给了键就一律走键幂等 =====
        // 判据与其他无订单写路径一致（v33 在线购票 / v62 下单）：**必传而不是可选** ——
        // "可选"等于默认没有保护，而漏传的代价是真金白银。
        String key = (idempotencyKey == null) ? null : idempotencyKey.trim();
        if (key != null && key.isEmpty()) {
            key = null;
        }
        if (key == null) {
            if (orderId == null) {
                throw new BusinessException("缺少幂等键 idempotencyKey");
            }
            // 订单内扣票：保持升级前的行为不变（键为 NULL → 不参与 uk_ticket_consume_idem 的判定）
        } else {
            if (key.length() > 64) {
                throw new BusinessException("幂等键长度不能超过 64");
            }
            // 幂等命中：同一笔扣票意图的重放，原样返回、**不再扣一次**。
            // 放在余额校验之前：这笔若已扣成功过，此后余额被别处花掉也不该让重放报"余额不足"。
            TicketRecord existing = ticketRecordMapper.getByCustomerAndIdempotencyKey(customerId, key);
            if (existing != null) {
                log.info("[v70] 扣票幂等命中: customerId={}, idempotencyKey={}, recordId={}",
                        customerId, key, existing.getId());
                return;
            }
        }

        // 账户恒为**该商品**：统一折扣只是买票时的定价规则，买到的票进的是这一款水自己的账户，
        // 所以这里不再有"选账户"这一步（v54 的 util/TicketScope 已随形态收口删除）。
        // 水票按水站隔离：A 站买的票不能在 B 站用。
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            // 文案会原样出现在客户端 toast 上，要说清"为什么不能用票"而不是笼统的余额不足
            throw new BusinessException("当前水站水票余额不足，请先补充水票库存");
        }
        int affected = ticketAccountMapper.decrementQuantity(account.getId(), qty);
        if (affected == 0) {
            throw new BusinessException("水票余额不足，请先购买水票后再试");
        }

        // [v36] 按 FIFO 消耗批次。批次单价快照决定"这次消耗值多少钱"，
        // 订单取消回补时按它还原 —— 否则站长中途调一次价，客户拿回的票就凭空变了值。
        com.example.aquaflow.service.TicketLotService.ConsumeResult cr =
                ticketLotService.consumeFifo(customerId, stationId, productId, qty);

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(0);
        record.setDecreaseQty(qty);
        record.setOrderId(orderId);
        // 有客户端键就落库；订单内扣票（PaymentServiceImpl）保持 NULL（它的幂等归 uk_ticket_consume）
        record.setIdempotencyKey(key);
        record.setSource("消费");
        record.setTicketSource(1);
        record.setUnitPrice(cr.getWeightedUnitPrice());
        record.setTicketLotId(cr.getSingleLotId());
        record.setCreateTime(LocalDateTime.now());
        try {
            ticketRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            if (key != null) {
                // [v70] 真并发：两个请求都通过了上面的存在性检查（check-then-act），
                // 由 uk_ticket_consume_idem(customer_id, idempotency_key) 拦住第二条。
                // ⚠️ 必须抛出 —— 见方法 javadoc：不抛就会留下"扣了两次、只记一条流水"。
                // 客户端用同一个 key 重试即会命中上面的幂等分支拿回原结果。
                log.warn("[v70] 手工扣票并发重复提交被唯一键拦截: customerId={}, idempotencyKey={}", customerId, key);
                throw new BusinessException("该笔扣票已提交，请勿重复提交");
            }
            // 订单内扣票：唯一键 uk_ticket_consume(order_id, product_id, source) 兜底并发双扣，
            // 冲突即视为已扣、幂等跳过（AQ-019）。调用方 PaymentServiceImpl 在同一事务内已先查过
            // countConsumeByOrderAndProduct；这里保留原行为不变（本条路径的 1062 语义**不同**于上面那条，
            // 别为了"统一"把它一起改成抛异常 —— 那会改动订单支付路径的回滚语义）。
            log.warn("[TicketAccount] 水票消费记录已存在(并发幂等跳过): orderId={}, productId={}", orderId, productId);
        }
    }

    /** 2026-10-02：原查回在 Controller 注入 mapper 越过分层；只读归入现有购票服务，不依赖在售价。 */
    @Override
    @Transactional(readOnly = true)
    public PaymentRecord findPurchaseResult(Long customerId, String idempotencyKey) {
        if (customerId == null || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.trim().length() > 64) {
            throw new BusinessException("购买编号不正确");
        }
        PaymentRecord original = paymentRecordMapper.getByCustomerAndIdempotencyKey(customerId, idempotencyKey.trim());
        return original == null || original.getOrderId() != null || original.getTicketQty() == null ? null : original;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentRecord purchaseTicket(Long customerId, Long productId, Integer qty, Integer paymentMethod,
                                        Long stationId, String idempotencyKey, Long packageId, Integer unifiedQty) {
        // ===== [v33] 幂等键必传 =====
        // 这条路径是「无订单支付」（order_id 为 NULL），此前完全没有任何防重：
        //   · PaymentServiceImpl.createPayment 的存在性检查整段包在 if (orderId != null) 里，跳过；
        //   · uk_payment_active_order 建在生成列 active_order_id 上，order_id 为 NULL 时生成列
        //     也是 NULL，MySQL 唯一键中 NULL 互不冲突 → 零保护。
        // 连点两次就会落两条待收款流水，站长在「待确认收款」看到两行、两条都确认即入账两次。
        // 设成必传而非可选：「可选」等于默认没有保护，而漏传的代价是重复入账真金白银。
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new BusinessException("缺少幂等键 idempotencyKey");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 64) {
            throw new BusinessException("幂等键长度不能超过 64");
        }

        // 2026-10-02：结束空意图与迟到建款共用永久主键锁；锁须覆盖下面插款的整个事务。
        purchaseFenceService.requireOpen(customerId, key);

        // ===== 幂等命中：同一笔购买意图的重放，返回原流水 =====
        // 放在所有业务校验**之前**：这笔购买若已成功创建过流水，那么此后站长即使把该商品的
        // 水票开关关掉、或改了价，重放也应当返回原流水，而不是报"未开启水票"或按新价再建一笔。
        PaymentRecord existing = paymentRecordMapper.getByCustomerAndIdempotencyKeyForUpdate(customerId, key);
        if (existing != null) {
            com.example.aquaflow.util.TicketPurchaseIntent.requireSame(existing, customerId, stationId,
                    productId, qty, paymentMethod, packageId, unifiedQty);
            log.info("[v33] 在线购票幂等命中: customerId={}, idempotencyKey={}, paymentId={}, status={}",
                    customerId, key, existing.getId(), existing.getStatus());
            return existing;
        }

        // #30: 先创建PENDING支付记录，再入账水票（支付确认后再真正入账）
        if (qty == null || qty <= 0) {
            throw new BusinessException("购买数量必须大于0");
        }
        // [2026-09-20] 收款方式只允许「微信/线下收款申请(1)」与「现金(2)」。
        // 3 = 水票：购票场景下"用水票买水票"没有任何业务含义，而此前是**传什么存什么**，
        // 实测 paymentMethod=3 也能建出一条待收款流水（凭据记的收款方式与事实不符）。
        // ⚠️ 这里**不**套用"货到付款授权"（offlinePaymentBlockReason）：购票是**预付** ——
        //    客户先把钱给水站才拿到票，水站没有赊账风险；那个授权管的是"送水时再收钱"。
        if (!Integer.valueOf(PayMethod.WECHAT).equals(paymentMethod)
                && !Integer.valueOf(PayMethod.CASH).equals(paymentMethod)) {
            throw new BusinessException("购票只支持微信/现金两种收款方式");
        }
        if (packageId != null && unifiedQty != null) {
            throw new BusinessException("一次只能按一种档位购票，请重新选择");
        }
        // 买的一定是**真实商品**的票（2026-09-20 形态收口）：不存在"站级通用票"这种商品。
        // 统一折扣与定制档位的区别只在**怎么定价**，账户、批次、退款路径完全一样。
        Product product = productMapper.getById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (barrelPolicy.isEnabled() && com.example.aquaflow.util.BarrelScope.isBarrel(product)
                && barrelLedger.rightQty(customerId, stationId, productId) <= 0) {
            throw new BusinessException("请先办理本站该商品的桶押金，再购买水票");
        }
        // 水票开关与票价以「站级库存」为准（与下单/试算走的 PriceUtil 同一口径）。
        // 注意 product.ticket_enabled 是商品级默认值，水站可对本站单独开启，因此必须查 inventory。
        com.example.aquaflow.entity.Inventory inv =
                stationId != null ? inventoryMapper.getByStationAndProduct(stationId, productId) : null;
        boolean custom = ticketTierService.usesCustomTicket(product, inv);
        if (!custom && !(ticketTierService.unifiedConfigured(stationId)
                && com.example.aquaflow.util.BarrelScope.isBarrel(product))) {
            // 既没给这款水开定制票、本站也没配统一折扣（或它不是桶装水）→ 这款水不能卖票
            throw new BusinessException("该商品在本水站未开通水票，暂不支持购买");
        }
        // [2026-09-16] 改走唯一计价入口：旧实现是"inventory.ticket_price → product.price"两段式，
        // 跳过了 product.ticket_price 这一级，与水票支付/报价（PriceUtil）的阶梯不一致 ——
        // 同一种水票，买票与用票可能算出两个价。
        // [v36] 定制档位：张数与总价一律以**服务端档位配置**为准，绝不能信客户端传来的价。
        // [v58] 统一折扣档：张数以客户端选的档为准（服务端按 (站,张数) 查档），
        //       **价格由服务端按该款水自己的水票价 × 折扣现算**（这就是"对应水怎么统一打折"）。
        BigDecimal unitPrice;
        BigDecimal totalAmount;
        if (packageId != null) {
            com.example.aquaflow.entity.TicketPackage pkg = ticketPackageMapper.getById(packageId);
            if (pkg == null || !pkg.isOnShelf()) {
                throw new BusinessException("该水票档位不存在或已下架");
            }
            // 档位是站级的：A 站的档位不能拿到 B 站用（否则 A 站买的便宜票在 B 站有价差）
            if (!java.util.Objects.equals(stationId, pkg.getStationId())
                    || !java.util.Objects.equals(productId, pkg.getProductId())) {
                throw new BusinessException("该档位与本水站/本商品不匹配");
            }
            if (!pkg.getQty().equals(qty)) {
                throw new BusinessException("购买张数与档位不一致，请重新选择");
            }
            unitPrice = pkg.getUnitPrice();
            totalAmount = pkg.getPrice();
        } else if (unifiedQty != null) {
            // 走统一折扣：**只有档位可买**（折扣长在张数档上，没有"单张统一价"这种东西）
            if (custom) {
                // 定制优先：这款水自己开了定制票，就不该走站级统一折扣
                throw new BusinessException("该商品已开通专属水票，请按其档位购买");
            }
            com.example.aquaflow.entity.StationTicketDiscount tier =
                    ticketTierService.requireUnifiedTier(stationId, unifiedQty);
            if (!tier.getQty().equals(qty)) {
                throw new BusinessException("购买张数与折扣档不一致，请重新选择");
            }
            java.util.Map<String, BigDecimal> priced =
                    ticketTierService.priceUnifiedTier(product, inv, tier);
            unitPrice = priced.get("unitPrice");
            totalAmount = priced.get("totalPrice");
        } else {
            if (!custom) {
                // 这款水没有"单张水票价"可依（定制票没开），散买没有折扣依据 → 只能按档买
                throw new BusinessException("该商品请按统一折扣档位购买");
            }
            // 散买：唯一计价入口（站级水票价 → product.ticket_price 阶梯）
            unitPrice = PriceUtil.calcUnitPrice(product, inv, PayMethod.TICKET);
            totalAmount = unitPrice.multiply(BigDecimal.valueOf(qty));
        }

        PaymentRecord record = new PaymentRecord();
        record.setCustomerId(customerId);
        record.setIdempotencyKey(key);
        record.setPurchaseRequestDigest(com.example.aquaflow.util.TicketPurchaseIntent.digest(
                customerId, stationId, productId, qty, paymentMethod, packageId, unifiedQty));
        record.setStationId(stationId);
        record.setAmount(totalAmount);
        record.setPaymentMethod(paymentMethod);
        record.setStatus(PaymentStatus.PENDING);
        // 备注要能自证"这笔是按哪种方式定的价"：定制档位 vs 站级统一折扣 vs 散买。
        // 定制档另有 ticket_package_id 可查；统一折扣档**不需要新列** ——
        // 档位由 (station_id, qty) 唯一确定，而 ticket_qty 已经落库，读者拿张数就能查到当时那一档。
        record.setNote(unifiedQty != null
                ? "线上购买水票（站级统一折扣 " + unifiedQty + " 张档）"
                : "线上购买水票");
        // 记录"买的是哪种水票、买几张"，支付确认后据此入账。
        // 此前这两个信息没有落库，导致支付成功也无从入账 —— 客户付了钱水票永远不到账。
        record.setTicketWaterTypeId(productId);
        record.setTicketQty(qty);
        // 记下"这笔记的是哪个档位"：档位价会变，历史流水必须能自证。
        // ⚠️ 统一折扣档没有 ticket_package 行（价格按各款水现算、不落库），这里就是 NULL ——
        // 那不是漏记：判别方式见上面的备注与 ticket_qty。
        record.setTicketPackageId(packageId);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        try {
            paymentRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 真并发：两个请求都通过了上面的存在性检查（check-then-act），由
            // uk_payment_idempotency(customer_id, idempotency_key) 拦住第二条。
            //
            // ⚠️ 这里**必须抛出**，不能吞掉后返回已存在的那条：本方法在事务内，
            // 捕获 DuplicateKeyException 后继续提交会让 Spring 抛 UnexpectedRollbackException，
            // 把一次正常的并发拒绝伪装成 500。客户端用同一个 key 重试即可命中上面的幂等分支
            // 拿到原流水（这正是幂等键的用法）。同款处理见 PaymentServiceImpl.createPayment。
            log.warn("[v33] 在线购票并发重复提交被唯一键拦截: customerId={}, idempotencyKey={}", customerId, key);
            throw new BusinessException("该笔购买已提交，请勿重复提交");
        }

        // 水票入账由 PaymentServiceImpl.confirmPayment 在支付确认成功后执行。
        // ⚠️ 它的乐观锁（CAS PENDING→PAID）只保证**单条流水**只入账一次，管不住"重复流水"——
        // 这正是上面必须从源头挡住第二条流水的原因。
        //
        // [2026-09-26 产品口径] 购票是客户的**自助预付**，不是「等水站同意」：
        //   · 微信：模拟渠道下由 `PaymentService.confirmMockChannelIfApplicable` 当场确认
        //     （调用点在 TicketAccountController.purchase），客户不需要任何水站操作；
        //   · 现金：留在待收款，等站长确认**收到钱**后再入账 —— 那是收款确认，不是审批；
        //   · 真实微信渠道接入时，本方法**不动**：把"统一下单"放在调用方（或本方法末尾发起），
        //     "付款成功"由支付回调调 `confirmPayment` 驱动 —— 别把入账写到这里来，
        //     那会和回调里的入账路径形成两条入账口（重复入账就是这么来的）。

        return record;
    }

    /**
     * 「站长加票 / 资产调整补录」时用的**推断单价** —— 这两条路径都没有真实付款，
     * 只能照当前价推一个值，并且一律标记 {@code PriceSource.INFERRED}（退票需二次确认）。
     *
     * <p>与单价真相源的关系：真正可信的单价只有两种 —— 在线购票的<b>实付均价</b>
     * （{@code creditPurchasedTickets}）和批次 FIFO 快照。本方法只服务于"没有付款凭据"的场景。</p>
     *
     * <p>取该商品在本站的**水票价**（{@link PriceUtil#calcUnitPrice} 传 {@code TICKET} 那一级：
     * 站级水票价 → 通用库水票价 → 零售价）。取不到商品就退化为 0（仍标记为推断值）——
     * 宁可显示 0 也不要凭空编一个价。</p>
     */
    private BigDecimal inferredUnitPrice(Long stationId, Long productId) {
        Product p = productMapper.getById(productId);
        com.example.aquaflow.entity.Inventory inv = inventoryMapper.getByStationAndProduct(stationId, productId);
        return p == null ? BigDecimal.ZERO : PriceUtil.calcUnitPrice(p, inv, PayMethod.TICKET);
    }
}
