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
import com.example.aquaflow.util.TicketScope;
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
     * 本站的统一水票"开关"：配了**上架**的 {@code product_id = 0} 档位即为开通。
     *
     * <p>不新增开关列的原因：产品口径是「统一水票是<b>可以设置项</b>」——
     * 站长把档位全下架，统一票自然就不再生效；多一个开关列就多一处可能与档位状态打架的真值。</p>
     */
    @Override
    public boolean unifiedTicketConfigured(Long stationId) {
        if (stationId == null) {
            return false;
        }
        List<com.example.aquaflow.entity.TicketPackage> pkgs =
                ticketPackageMapper.listOnShelf(stationId, TicketScope.UNIFIED_PRODUCT_ID);
        return pkgs != null && !pkgs.isEmpty();
    }

    /** 扣票账户的唯一判据入口；规则本身在 {@link TicketScope}（不要在调用点重写这三条 if）。 */
    @Override
    public Long resolveDeductAccount(Long customerId, Long productId, Long stationId) {
        if (customerId == null || productId == null || stationId == null) {
            return null;
        }
        if (TicketScope.isUnified(productId)) {
            // 传进来的就是站级通用票本身（购票入账、站长加票/调整），直接用，不再判定
            return TicketScope.UNIFIED_PRODUCT_ID;
        }
        return TicketScope.resolveAccount(
                productId,
                balanceOf(customerId, productId, stationId),
                unifiedTicketConfigured(stationId),
                TicketScope.unifiedEligible(productMapper.getById(productId)));
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
        // [v54] 新流水一律显式写账户列（NULL 只属于 v54 之前的存量行）：站长给客户补的若是
        // product_id=0 的统一票，这一行自证"进的是站级通用账户"，退票/对账都不必再猜。
        record.setAccountProductId(productId);
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
     * <p>不复用 {@link #addTicket}（无幂等键）与 {@link #consumeTicket}（要求 orderId）：
     * 调整场景没有订单，且必须能挡住"同一张单重复执行"。
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
        // [v54] 同 addTicket：显式写账户列（调整单同样可以对 product_id=0 的统一票发起）
        record.setAccountProductId(productId);
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
        // [v54] 统一水票购买时 productId=0：这一行自证"进的是站级通用账户"
        record.setAccountProductId(productId);
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
        // ===== [v54 统一水票] 回补必须回到**当初扣的那个账户** =====
        // 判据来自消费流水自证的 account_product_id（v54 之前的存量流水为 NULL，
        // 语义就是"与 product_id 同账户"，见迁移 v54 的列注释）。
        // ⚠️ **绝不能**在这里重新跑一遍 TicketScope 判定：退款这一刻客户余额早已变化
        // （定制票刚被扣光 → 会重新判成统一票），票就会退进一个客户从没扣过的账户，
        // 表现为"取消订单后定制票没回来、统一票反而多了几张"。
        TicketRecord consumeRecord = orderId != null
                ? ticketRecordMapper.getConsumeRecord(orderId, productId) : null;
        Long accountProductId = consumeRecord != null && consumeRecord.getAccountProductId() != null
                ? consumeRecord.getAccountProductId() : productId;

        // 水票按水站隔离：先取（不存在则建），再回补
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, accountProductId, stationId);
        if (account == null) {
            account = new TicketAccount();
            account.setCustomerId(customerId);
            account.setProductId(accountProductId);
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
        BigDecimal restorePrice;
        boolean restoreInferred;
        if (consumeRecord != null && consumeRecord.getUnitPrice() != null) {
            restorePrice = consumeRecord.getUnitPrice();
            restoreInferred = false;
        } else {
            // 退化分支一律按**订单行商品**取价：统一票没有商品也没有站级水票价可查，
            // 用它当基准只会得到 0。
            Product rp = productMapper.getById(productId);
            com.example.aquaflow.entity.Inventory rInv = inventoryMapper.getByStationAndProduct(stationId, productId);
            restorePrice = rp == null ? BigDecimal.ZERO : PriceUtil.calcUnitPrice(rp, rInv, PayMethod.TICKET);
            restoreInferred = true;
        }
        com.example.aquaflow.entity.TicketLot restoreLot = ticketLotService.createLot(
                customerId, stationId, accountProductId, restorePrice, qty,
                com.example.aquaflow.entity.TicketLot.SourceType.REFUND_RESTORE,
                restoreInferred ? com.example.aquaflow.entity.TicketLot.PriceSource.INFERRED
                                : com.example.aquaflow.entity.TicketLot.PriceSource.PAID,
                restoreInferred, orderId, "订单取消/退款回补水票");

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setAccountProductId(accountProductId);
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consumeTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId) {
        if (qty == null || qty <= 0) {
            throw new BusinessException("水票扣减张数必须大于 0");
        }
        // ===== [v54 统一水票] 先按**唯一判据**选账户（规则在 util/TicketScope，不要在这里重写）=====
        // 定制优先：该商品还有定制票余额 → 只用定制（不够也不拿统一票补差额，见 docs/design/26 §26.0）；
        // 统一兜底：没有定制票余额（没账户 / 已用光）且本站配了统一票档位、商品是桶装水 → 站级通用票。
        Long accountProductId = resolveDeductAccount(customerId, productId, stationId);
        if (accountProductId == null) {
            // 这些文案会原样出现在客户端 toast 上，要说清"为什么不能用票"而不是笼统的余额不足
            throw new BusinessException("该商品不能用票支付：没有该商品的定制水票余额，本站也未配置统一水票（统一水票只抵桶装水）");
        }
        boolean unified = TicketScope.isUnified(accountProductId);
        // 水票按水站隔离（统一票同理：A 站买的站级票不能在 B 站用）
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, accountProductId, stationId);
        if (account == null) {
            throw new BusinessException(unified
                    ? "统一水票余额不足，请先购买统一水票后再试"
                    : "当前水站水票余额不足，请先补充水票库存");
        }
        int affected = ticketAccountMapper.decrementQuantity(account.getId(), qty);
        if (affected == 0) {
            throw new BusinessException(unified
                    ? "统一水票余额不足，请先购买统一水票后再试"
                    : "水票余额不足，请先购买水票后再试");
        }

        // [v36] 按 FIFO 消耗批次。批次单价快照决定"这次消耗值多少钱"，
        // 订单取消回补时按它还原 —— 否则站长中途调一次价，客户拿回的票就凭空变了值。
        // ⚠️ 必须用 accountProductId（账户）而不是 productId（订单行商品）去消耗批次，
        // 否则会分裂成"汇总结余扣的是统一票、批次扣的是该商品定制票"（E8 随即报不平）。
        com.example.aquaflow.service.TicketLotService.ConsumeResult cr =
                ticketLotService.consumeFifo(customerId, stationId, accountProductId, qty);

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        // ⚠️ 流水仍记**订单行商品**：幂等键 uk_ticket_consume(order_id, product_id) 与
        // countConsumeByOrderAndProduct 都按它判重。写成 0（统一票）会让同一张单的第二条流水
        // 撞唯一键、被 catch 当成"已扣过"静默跳过 —— 也就是少扣票（v54 加 account_product_id 的起因）。
        record.setProductId(productId);
        record.setAccountProductId(accountProductId);
        record.setStationId(stationId);
        record.setIncreaseQty(0);
        record.setDecreaseQty(qty);
        record.setOrderId(orderId);
        record.setSource("消费");
        record.setTicketSource(1);
        record.setUnitPrice(cr.getWeightedUnitPrice());
        record.setTicketLotId(cr.getSingleLotId());
        record.setCreateTime(LocalDateTime.now());
        // 唯一键 uk_ticket_consume(order_id, product_id, source) 兜底并发双扣：冲突即视为已扣，幂等跳过（AQ-019）
        try {
            ticketRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.warn("[TicketAccount] 水票消费记录已存在(并发幂等跳过): orderId={}, productId={}", orderId, productId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentRecord purchaseTicket(Long customerId, Long productId, Integer qty, Integer paymentMethod,
                                        Long stationId, String idempotencyKey, Long packageId) {
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

        // ===== 幂等命中：同一笔购买意图的重放，返回原流水 =====
        // 放在所有业务校验**之前**：这笔购买若已成功创建过流水，那么此后站长即使把该商品的
        // 水票开关关掉、或改了价，重放也应当返回原流水，而不是报"未开启水票"或按新价再建一笔。
        PaymentRecord existing = paymentRecordMapper.getByCustomerAndIdempotencyKey(customerId, key);
        if (existing != null) {
            log.info("[v33] 在线购票幂等命中: customerId={}, idempotencyKey={}, paymentId={}, status={}",
                    customerId, key, existing.getId(), existing.getStatus());
            return existing;
        }

        // #30: 先创建PENDING支付记录，再入账水票（支付确认后再真正入账）
        if (qty == null || qty <= 0) {
            throw new BusinessException("购买数量必须大于0");
        }
        // ===== [v54 统一水票] productId = 0 是**站级通用票**，不是商品 ====
        // 所以这段必须分路：统一票没有 product 行、没有 inventory 行，走商品那套校验只会得到
        // "商品不存在 / 该商品在本水站未开启水票"。它的"开通"判据是**本站配了 product_id=0 的档位**。
        boolean unified = TicketScope.isUnified(productId);
        Product product = unified ? null : productMapper.getById(productId);
        com.example.aquaflow.entity.Inventory inv = null;
        if (unified) {
            if (stationId == null) {
                throw new BusinessException("购买统一水票必须指定水站");
            }
            // ⚠️ 统一票**只能按档位买**：散买价来自"站级水票价 → product.ticket_price"的两级阶梯，
            // 而 product_id=0 既没有商品也没有库存行，散买会算出 0 元 —— 那等于白送票。
            // 折扣本来就发生在档位上（买 10 张 9.5 折、30 张 9 折），所以这条限制不损失任何能力。
            if (packageId == null) {
                throw new BusinessException("购买统一水票必须选择档位套餐");
            }
            if (!unifiedTicketConfigured(stationId)) {
                throw new BusinessException("本水站未配置统一水票，暂不支持购买");
            }
        } else {
            if (product == null) {
                throw new BusinessException("商品不存在");
            }
            // 水票开关与票价以「站级库存」为准（与下单/试算走的 PriceUtil 同一口径）。
            // 注意 product.ticket_enabled 是商品级默认值，水站可对本站单独开启，
            // 因此必须查 inventory，不能只看 product。
            inv = stationId != null ? inventoryMapper.getByStationAndProduct(stationId, productId) : null;
            if (inv == null || !Integer.valueOf(1).equals(inv.getTicketEnabled())) {
                throw new BusinessException("该商品在本水站未开启水票，暂不支持购买");
            }
        }
        // [2026-09-16] 改走唯一计价入口：旧实现是"inventory.ticket_price → product.price"两段式，
        // 跳过了 product.ticket_price 这一级，与水票支付/报价（PriceUtil）的阶梯不一致 ——
        // 同一种水票，买票与用票可能算出两个价。
        // [v36] 档位套餐：张数与总价一律以**服务端档位配置**为准。
        // 绝不能信客户端传来的套餐价 —— 那等于让客户端自己定价。
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
        } else {
            // 散买：唯一计价入口（站级水票价 → product.ticket_price 阶梯）
            unitPrice = PriceUtil.calcUnitPrice(product, inv, PayMethod.TICKET);
            totalAmount = unitPrice.multiply(BigDecimal.valueOf(qty));
        }

        PaymentRecord record = new PaymentRecord();
        record.setCustomerId(customerId);
        record.setIdempotencyKey(key);
        record.setStationId(stationId);
        record.setAmount(totalAmount);
        record.setPaymentMethod(paymentMethod);
        record.setStatus(PaymentStatus.PENDING);
        record.setNote("线上购买水票");
        // 记录"买的是哪种水票、买几张"，支付确认后据此入账。
        // 此前这两个信息没有落库，导致支付成功也无从入账 —— 客户付了钱水票永远不到账。
        record.setTicketWaterTypeId(productId);
        record.setTicketQty(qty);
        // 记下"这笔记的是哪个档位"：档位价会变，历史流水必须能自证
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
        // 真实环境微信支付到位后，这里应改为：统一下单 -> 等待支付回调 -> 回调中确认支付并入账。

        return record;
    }

    /**
     * 「站长加票 / 资产调整补录」时用的**推断单价** —— 这两条路径都没有真实付款，
     * 只能照当前价推一个值，并且一律标记 {@code PriceSource.INFERRED}（退票需二次确认）。
     *
     * <p>与单价真相源的关系：真正可信的单价只有两种 —— 在线购票的<b>实付均价</b>
     * （{@code creditPurchasedTickets}）和批次 FIFO 快照。本方法只服务于"没有付款凭据"的场景。</p>
     *
     * <p>统一水票（{@code productId = 0}）取<b>本站上架档位里均价最低的那个</b>：
     * 档位价会变、客户究竟按哪档买也无从得知，"买得越多越便宜"的设计下最低均价是客户能拿到的
     * 真实最低单张成本。取不到档位就退化为 0（仍标记为推断值）——
     * 宁可显示 0 也不要凭空编一个价。</p>
     */
    private BigDecimal inferredUnitPrice(Long stationId, Long productId) {
        if (TicketScope.isUnified(productId)) {
            List<com.example.aquaflow.entity.TicketPackage> pkgs =
                    ticketPackageMapper.listOnShelf(stationId, TicketScope.UNIFIED_PRODUCT_ID);
            BigDecimal best = null;
            if (pkgs != null) {
                for (com.example.aquaflow.entity.TicketPackage pkg : pkgs) {
                    BigDecimal u = pkg.getUnitPrice();
                    if (u == null || u.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    if (best == null || u.compareTo(best) < 0) {
                        best = u;
                    }
                }
            }
            return best != null ? best : BigDecimal.ZERO;
        }
        Product p = productMapper.getById(productId);
        com.example.aquaflow.entity.Inventory inv = inventoryMapper.getByStationAndProduct(stationId, productId);
        return p == null ? BigDecimal.ZERO : PriceUtil.calcUnitPrice(p, inv, PayMethod.TICKET);
    }
}
