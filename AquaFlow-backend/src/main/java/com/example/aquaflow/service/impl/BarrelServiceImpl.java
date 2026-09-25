package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.BarrelRecordLot;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.CustomerBarrelOver;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.BarrelRecordLotMapper;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.CustomerBarrelAssetMapper;
import com.example.aquaflow.mapper.CustomerBarrelInTransitMapper;
import com.example.aquaflow.mapper.CustomerBarrelOverMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.DepositRecordMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.service.BarrelService;
import com.example.aquaflow.service.CustomerRiskService;
import com.example.aquaflow.util.PriceUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@lombok.extern.slf4j.Slf4j
public class BarrelServiceImpl implements BarrelService {

    @Autowired
    private BarrelRecordMapper barrelRecordMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerBarrelOverMapper customerBarrelOverMapper;

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

    /** 桶账唯一写入口（权益/批次/over），退桶核销必须走它 */
    @Autowired
    private BarrelLedgerService barrelLedgerService;

    /** 退桶 → 押金条核销明细，用于事后审计"这笔钱按哪几张押金条算的" */
    @Autowired
    private BarrelRecordLotMapper barrelRecordLotMapper;

    /**
     * 信用风险（验资）—— 退桶预检时问一句"这个客户还欠着钱吗"。
     *
     * <p>它只依赖 {@code OrderMapper}，不会与本类形成循环。</p>
     */
    @Autowired
    private CustomerRiskService customerRiskService;

    @Override
    public List<CustomerBarrelAsset> getAssets(Long customerId, Long stationId) {
        return customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);
    }

    @Override
    public List<BarrelRecord> listRecords(Long customerId, Long stationId) {
        return barrelRecordMapper.listByCustomerAndStation(customerId, stationId);
    }

    /**
     * 权益部分的押金金额（按商品）：直接取 {@code customer_barrel_asset.right_amount}。
     *
     * <p>该列由桶账唯一写入口 {@code BarrelLedgerService} 维护，定义就是
     * {@code Σ lot.remain_qty × lot.unit_price}（买入时单价快照）。
     * 「可退多少押金」本来就要按它算（{@code doRefund} 也是这个口径），
     * 所以展示也必须用它 —— 否则站长一调价，客户页面上"我的押金"就跟着变，而对账不会变。</p>
     */
    private Map<Long, BigDecimal> assetAmountByProduct(List<CustomerBarrelAsset> assets) {
        Map<Long, BigDecimal> byProduct = new HashMap<>();
        if (assets == null) return byProduct;
        for (CustomerBarrelAsset a : assets) {
            if (a.getProductId() == null) continue;
            BigDecimal amount = a.getRightAmount() != null ? a.getRightAmount() : BigDecimal.ZERO;
            byProduct.merge(a.getProductId(), amount, BigDecimal::add);
        }
        return byProduct;
    }

    /**
     * 配送中部分的押金金额（按商品）：{@code customer_barrel_in_transit.unit_price × qty}（下单时快照）。
     *
     * <p>没有快照的历史/迁移行（unit_price 为 NULL 或 0）才回落到**当前本站押金**，
     * 与 {@code BarrelLedgerService#resolveUnitPrice} 的兜底阶梯保持一致。</p>
     */
    private Map<Long, BigDecimal> pendingAmountByProduct(List<CustomerBarrelInTransit> transits, Long stationId) {
        Map<Long, BigDecimal> byProduct = new HashMap<>();
        if (transits == null) return byProduct;
        for (CustomerBarrelInTransit t : transits) {
            if (!"PENDING".equals(t.getStatus()) || t.getProductId() == null) continue;
            int qty = t.getQty() != null ? t.getQty() : 0;
            if (qty == 0) continue;
            BigDecimal unit = t.getUnitPrice();
            if (unit == null || unit.compareTo(BigDecimal.ZERO) <= 0) {
                unit = PriceUtil.calcDeposit(productMapper.getById(t.getProductId()),
                        inventoryMapper.getByStationAndProduct(stationId, t.getProductId()));
            }
            byProduct.merge(t.getProductId(), unit.multiply(BigDecimal.valueOf(qty)), BigDecimal::add);
        }
        return byProduct;
    }

    @Override
    public List<Map<String, Object>> getBarrelSummaryByType(Long customerId, Long stationId) {
        List<CustomerBarrelAsset> assets = customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);

        // 配送中桶按产品聚合
        // [2026-09-15] 只把 PENDING 计入「配送中」——与下方 getBarrelSummary 的口径一致
        //（那里已写明理由：DELIVERED 表示桶已送到顾客手上，一并计入会把已收到的桶误判成还在路上）。
        // 旧实现只排除 CANCELLED，导致送达后 inTransitQty == assetQty，
        // 客户端「按类型」页显示成「持有 2 个，配送中 2 个」——同一批桶被数了两遍。
        Map<Long, Integer> inTransitByProduct = new HashMap<>();
        List<CustomerBarrelInTransit> transits = customerBarrelInTransitMapper.listByCustomerAndStation(customerId, stationId);
        if (transits != null) {
            for (CustomerBarrelInTransit t : transits) {
                if (!"PENDING".equals(t.getStatus())) continue;
                Long pid = t.getProductId();
                if (pid == null) continue;
                inTransitByProduct.put(pid, inTransitByProduct.getOrDefault(pid, 0) + (t.getQty() != null ? t.getQty() : 0));
            }
        }

        // over 按商品（**可为负**：负数 = 顾客多还的桶寄存在水站，是合法状态，不能当成 0）。
        // [2026-09-16 补] 此前本方法完全不下发 over 相关字段，导致两处错：
        //   · 客户端按商品那行「欠桶 M 桶」读 h.owedQty —— 字段不存在 → 恒显示 0；
        //   · 还桶上限只能靠前端猜，而唯一正确的上限是「占用 = 权益 + over」。
        Map<Long, Integer> overByProduct = new HashMap<>();
        List<CustomerBarrelOver> overs = customerBarrelOverMapper.listByCustomerAndStation(customerId, stationId);
        if (overs != null) {
            for (CustomerBarrelOver o : overs) {
                if (o.getProductId() == null || o.getOverQty() == null) continue;
                overByProduct.put(o.getProductId(), o.getOverQty());
            }
        }

        // [2026-09-16 修复] 商品集合必须取 **assets ∪ 在途 ∪ over** 的并集，不能只遍历 assets。
        // `customer_barrel_asset` 只记录**已到手**的桶，于是「首单还在配送途中」时 assets 为空
        // → 本方法返回空数组：客户端概览写着「配送中 2」，下方的按类型明细却一行都没有，
        // 客户看不出是哪种桶，连退桶弹窗里都选不到它。
        // 这与提案 §2.9 点名、并已在员工端 CustomerServiceImpl 修过的是同一个漏法（那边用 byProduct 取并集）。
        Map<Long, Integer> assetQtyByProduct = new LinkedHashMap<>();
        if (assets != null) {
            for (CustomerBarrelAsset a : assets) {
                if (a.getProductId() == null) continue;
                assetQtyByProduct.merge(a.getProductId(), a.getQuantity() == null ? 0 : a.getQuantity(), Integer::sum);
            }
        }
        Set<Long> productIds = new LinkedHashSet<>(assetQtyByProduct.keySet());
        productIds.addAll(inTransitByProduct.keySet());
        productIds.addAll(overByProduct.keySet());

        // 押金金额（批次快照口径，见下方 assetAmountByProduct / pendingAmountByProduct 的注释）
        Map<Long, BigDecimal> assetAmountByProduct = assetAmountByProduct(assets);
        Map<Long, BigDecimal> pendingAmountByProduct = pendingAmountByProduct(transits, stationId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Long pid : productIds) {
            Product product = productMapper.getById(pid);
            int assetQty = assetQtyByProduct.getOrDefault(pid, 0);
            int inTransitQty = inTransitByProduct.getOrDefault(pid, 0);
            int over = overByProduct.getOrDefault(pid, 0);
            // 权益、在途、over 全为 0 的行没有展示价值（历史残留的空行）
            if (assetQty == 0 && inTransitQty == 0 && over == 0) continue;
            BigDecimal deposit = PriceUtil.calcDeposit(product, inventoryMapper.getByStationAndProduct(stationId, pid));
            // [2026-09-16 定稿] 押金合计改**批次快照口径**：权益部分用 asset.right_amount
            // （= Σ lot.remain_qty × lot.unit_price，买入时快照），配送中部分用 in_transit.unit_price（下单快照）。
            // 不能用"当前押金 × 桶数"：站长调价后客户**已经付过的钱**会跟着变 —— 那是账，不是展示（docs/design/12 §4.3）。
            BigDecimal depositTotal = assetAmountByProduct.getOrDefault(pid, BigDecimal.ZERO)
                    .add(pendingAmountByProduct.getOrDefault(pid, BigDecimal.ZERO));
            Map<String, Object> map = new HashMap<>();
            map.put("productId", pid);
            map.put("productName", product != null ? product.getName() : "未知商品");
            map.put("productSpec", product != null ? product.getSpec() : "");
            map.put("assetQty", assetQty);
            map.put("inTransitQty", inTransitQty);
            // [2026-09-15] 展示口径的"持有" = 权益 + 配送中（买了就是你的）。
            // 注意 assetQty 必须保持"权益（已到手）"不变：小程序下单页用它算"我还差几个桶"
            // （miniapp-user/pages/order/create.js），改成含在途会让前端与后端下单抵扣口径打架。
            map.put("heldTotalQty", assetQty + inTransitQty);
            // [2026-09-16] over 三态，与 getBarrelSummary 同口径（正=欠桶 / 负=水站暂存 / 0=两清）：
            //   owedQty    = max(0, over)   —— 客户该还没还
            //   storageQty = max(0, -over)  —— 客户多还、寄存在水站（不是欠桶，也不是负债）
            //   occupiedQty= 权益 + over    —— 物理在手；**还桶上限就是它**（不含配送中）
            map.put("overQty", over);
            map.put("owedQty", Math.max(0, over));
            map.put("storageQty", Math.max(0, -over));
            map.put("occupiedQty", assetQty + over);
            map.put("deposit", deposit);
            map.put("depositTotal", depositTotal);
            result.add(map);
        }
        return result;
    }

    @Override
    public Map<String, Object> getBarrelSummary(Long customerId, Long stationId) {
        Map<String, Object> summary = new HashMap<>();

        // 桶账口径（[2026-09-15] 三个数各说各话，互不重叠）：
        //   · 权益 right    = 已到手（押金条 remain_qty 汇总 → customer_barrel_asset.quantity）
        //   · 配送中 pending = 已买下但还没送到（customer_barrel_in_transit 里 PENDING）
        //   · 持有 held     = 权益 + 配送中 —— **展示口径**："这个客户一共有几个桶"
        //   · 占用 occupied = 权益 + over —— 物理在手，**不含配送中**（那批桶还没到手上）
        // 为什么要拆开：下单抵扣与退押金只认"权益"（在途的桶在订单结束前是锁住的），
        // 而客户看页面时"买了就是我的"→ 持有要把在途算进去。两者混用一个字段就会互相打架。
        List<CustomerBarrelAsset> assets = customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);

        // 配送中桶：**只认 PENDING**（DELIVERED = 已送到顾客手上，不能再算在途）。
        // [2026-09-16 修复] 这里原先是「只排除 CANCELLED」，与 getBarrelSummaryByType /
        // CustomerServiceImpl 两个已修正的口径不一致：送达后 in_transit 行被标为 DELIVERED
        // 但**保留**（对账 E5 要用），于是客户首页仍把已到手的桶显示成「配送中 N」，
        // 同一批桶被数了两遍 —— 正是 §2.9 点名、却只修了「按类型」页的那处重复计数。
        int pendingDeliveryBuckets = 0;
        Map<Long, Integer> pendingByProduct = new HashMap<>();
        List<CustomerBarrelInTransit> transits = customerBarrelInTransitMapper.listByCustomerAndStation(customerId, stationId);
        if (transits != null) {
            for (CustomerBarrelInTransit t : transits) {
                if (!"PENDING".equals(t.getStatus())) continue;
                int q = t.getQty() != null ? t.getQty() : 0;
                pendingDeliveryBuckets += q;
                if (t.getProductId() != null) {
                    pendingByProduct.merge(t.getProductId(), q, Integer::sum);
                }
            }
        }

        // 权益（已到手）与持有（权益 + 配送中），以及对应的押金合计
        int rightBuckets = 0;
        int heldBuckets = 0;
        // [2026-09-16 定稿] 押金合计 = **批次快照口径**，不再"当前押金 × 桶数"：
        //   权益部分   = Σ customer_barrel_asset.right_amount（= Σ lot.remain_qty × lot.unit_price）
        //   配送中部分 = Σ customer_barrel_in_transit.unit_price × qty（下单时快照）
        // 站长调价只影响之后的新批次，客户已付过的钱不会跟着变（与退押金 doRefund、对账口径一致）。
        Map<Long, BigDecimal> assetAmountByProduct = assetAmountByProduct(assets);
        Map<Long, BigDecimal> pendingAmountByProduct = pendingAmountByProduct(transits, stationId);
        BigDecimal depositTotal = BigDecimal.ZERO;
        for (BigDecimal v : assetAmountByProduct.values()) {
            depositTotal = depositTotal.add(v);
        }
        for (BigDecimal v : pendingAmountByProduct.values()) {
            depositTotal = depositTotal.add(v);
        }
        if (assets != null) {
            for (CustomerBarrelAsset a : assets) {
                int q = a.getQuantity() != null ? a.getQuantity() : 0;
                rightBuckets += q;
                int pending = a.getProductId() == null ? 0 : pendingByProduct.getOrDefault(a.getProductId(), 0);
                heldBuckets += q + pending;
            }
        }
        // 「只有配送中、还没有权益」的桶型（asset 行不存在，例如首单还在路上）也必须计入持有，
        // 否则会出现"配送中 2 个，持有 0 个"的自相矛盾展示。
        for (Map.Entry<Long, Integer> e : pendingByProduct.entrySet()) {
            boolean hasAssetRow = false;
            if (assets != null) {
                for (CustomerBarrelAsset a : assets) {
                    if (e.getKey().equals(a.getProductId())) { hasAssetRow = true; break; }
                }
            }
            if (hasAssetRow) continue;
            heldBuckets += e.getValue();
            // 押金金额已由 pendingAmountByProduct 统一按快照算入 depositTotal，这里只补"持有"数量
        }

        // 欠桶：按商品统计 Σ max(0, over)。over 可为负（多还桶/水站暂存，合法状态），
        // 负值不参与汇总——A 水的多还桶不能抵 B 水的欠桶。
        int owedBuckets = 0;
        // 水站暂存：Σ max(0, −over)，即顾客多还、寄存在水站的桶。
        // 以前这个值被当成 0 直接丢掉，于是"顾客还了 3 个桶只拿走 1 个"在界面上完全看不出来，
        // 这是顾客打电话问「我的桶呢」的直接来源。它既不是欠桶也不是负债，必须单独展示。
        int storageBuckets = 0;
        // 实际持有 = Σ(权益 + over)：顾客手上真正有几个桶（派生值，可能为 0，不会为负）
        int occupiedBuckets = 0;
        List<CustomerBarrelOver> overs = customerBarrelOverMapper.listByCustomerAndStation(customerId, stationId);
        if (overs != null) {
            for (CustomerBarrelOver o : overs) {
                if (o.getOverQty() == null) continue;
                owedBuckets += Math.max(0, o.getOverQty());
                storageBuckets += Math.max(0, -o.getOverQty());
            }
        }
        // 占用 = Σ(权益 + over)，商品集合必须取 **assets ∪ over** 的并集。
        // [2026-09-16 修复] 原实现只遍历 assets，于是「权益已退光、但人还欠着桶」的商品
        //（over > 0 且没有 asset 行：客户把最后一个付过押金的桶退掉拿回押金，却还端着借来的桶）
        // 会被整行跳过 → 概览 occupiedBuckets 算成 0。客户端现在拿 occupiedBuckets 当**还桶上限**，
        // 于是客户在页面上根本还不了手上那几个桶，而后端 returnEmpty 的上限（权益 + over）明明是允许的
        // —— 又是「只遍历 assets」这个坑（本文件 getBarrelSummaryByType 与员工端 CustomerServiceImpl
        // 各修过一次，这是第 4 处）。两个汇总端点的占用口径必须永远一致，改一处就要改另一处。
        Map<Long, Integer> assetQtyByProduct = new LinkedHashMap<>();
        if (assets != null) {
            for (CustomerBarrelAsset a : assets) {
                if (a.getProductId() == null) continue;
                assetQtyByProduct.merge(a.getProductId(),
                        a.getQuantity() == null ? 0 : a.getQuantity(), Integer::sum);
            }
        }
        Map<Long, Integer> overByProduct = new LinkedHashMap<>();
        if (overs != null) {
            for (CustomerBarrelOver o : overs) {
                if (o.getProductId() == null) continue;
                overByProduct.put(o.getProductId(), o.getOverQty() == null ? 0 : o.getOverQty());
            }
        }
        Set<Long> occupiedProductIds = new LinkedHashSet<>(assetQtyByProduct.keySet());
        occupiedProductIds.addAll(overByProduct.keySet());
        for (Long pid : occupiedProductIds) {
            occupiedBuckets += assetQtyByProduct.getOrDefault(pid, 0) + overByProduct.getOrDefault(pid, 0);
        }

        // 配送中桶的统计已上移到"持有"计算之前（持有 = 权益 + 配送中，需要按桶型配对）。

        // 退桶记录统计（type=2 为退桶申请）
        int pendingReturns = 0, confirmedReturns = 0, returnBuckets = 0;
        List<BarrelRecord> records = barrelRecordMapper.listByCustomerAndStation(customerId, stationId);
        if (records != null) {
            for (BarrelRecord r : records) {
                if (r.getType() != null && r.getType() == 2) {
                    returnBuckets++;
                    if (r.getStatus() != null && r.getStatus() == 1) pendingReturns++;
                    else if (r.getStatus() != null && r.getStatus() >= 2) confirmedReturns++;
                }
            }
        }

        // 押金账户余额
        BigDecimal depositBalance = customerDepositAccountMapper.getBalance(customerId, stationId);
        if (depositBalance == null) depositBalance = BigDecimal.ZERO;

        // 平均单桶押金（用于详情页"最近一次每桶押金"展示）
        BigDecimal depositPerBucket = heldBuckets > 0
                ? depositTotal.divide(BigDecimal.valueOf(heldBuckets), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // heldBuckets  = 权益 + 配送中 → 展示"这个客户一共有几个桶"（买了就是你的）
        // rightBuckets = 权益（已到手）→ 下单抵扣与退押金只认这个数
        // occupiedBuckets = 权益 + over → 物理在手（还桶上限），不含配送中
        summary.put("heldBuckets", heldBuckets);
        summary.put("rightBuckets", rightBuckets);
        summary.put("actualBuckets", heldBuckets);
        summary.put("owedBuckets", owedBuckets);
        // 实际持有（权益 + over，顾客手上真有几个桶）与水站暂存（多还的桶）
        summary.put("occupiedBuckets", occupiedBuckets);
        summary.put("storageBuckets", storageBuckets);
        // [2026-09-16] 原 deliveryBuckets（「只排除 CANCELLED」→ 含 DELIVERED）已删除：
        // 它与 pendingDeliveryBuckets 语义重叠且口径错误，唯一消费方（顾客端首页 renderBarrelLine）
        // 已改用 pendingDeliveryBuckets。此后「配送中」一律读 pendingDeliveryBuckets，不留第二种口径。
        summary.put("pendingDeliveryBuckets", pendingDeliveryBuckets);
        summary.put("returnBuckets", returnBuckets);
        summary.put("pendingReturns", pendingReturns);
        summary.put("confirmedReturns", confirmedReturns);
        summary.put("depositBalance", depositBalance);
        summary.put("depositTotal", depositTotal);
        summary.put("depositPerBucket", depositPerBucket);
        return summary;
    }

    @Override
    public List<com.example.aquaflow.vo.OwedBarrelVO> listOwedCustomers(Long stationId, Integer minDays) {
        if (stationId == null) {
            throw new BusinessException("请先绑定水站");
        }
        List<com.example.aquaflow.vo.OwedBarrelVO> rows = customerBarrelOverMapper.listOwedByStation(stationId);
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        if (minDays == null || minDays <= 0) return rows;

        List<com.example.aquaflow.vo.OwedBarrelVO> filtered = new ArrayList<>();
        for (com.example.aquaflow.vo.OwedBarrelVO r : rows) {
            Integer d = r.getOwedDays();
            // 天数未知（历史存量行 owed_since 为空）时一并保留：按天数筛选不能变成"漏催收"
            if (d == null || d >= minDays) filtered.add(r);
        }
        return filtered;
    }

    @Override
    @Transactional
    public void handleBarrelException(Long customerId, Long stationId, Long productId, Integer type, Integer quantity, Long relatedOrderId, String note, Long operatorId) {
        // #25: 校验quantity必须大于0
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("数量必须大于0");
        }
        // 更新桶资产
        CustomerBarrelAsset asset = customerBarrelAssetMapper.getByCustomerAndProduct(customerId, productId, stationId);
        
        switch (type) {
            case 1: // 新增押金桶
                if (asset == null) {
                    asset = new CustomerBarrelAsset();
                    asset.setCustomerId(customerId);
                    asset.setProductId(productId);
                    asset.setStationId(stationId);
                    asset.setQuantity(quantity);
                    customerBarrelAssetMapper.insert(asset);
                } else {
                    customerBarrelAssetMapper.increaseQuantity(asset.getId(), quantity);
                }
                break;
            case 2: // 退桶（申请，待站长审批，暂不扣减桶资产）
                // 仅创建待审批记录（status=1），由站长在退桶审批页确认后才真正扣减桶资产并退押金
                break;
            case 3: // 丢失
            case 4: // 损坏
                if (asset == null || asset.getQuantity() < quantity) {
                    throw new BusinessException("可用桶权益不足，无法完成本次操作");
                }
                customerBarrelAssetMapper.decreaseQuantity(asset.getId(), quantity);
                break;
            case 5: // 赔偿
                // 赔偿不改变桶资产，只产生押金流水
                break;
            case 6: // 人工调整
                if (asset == null) {
                    asset = new CustomerBarrelAsset();
                    asset.setCustomerId(customerId);
                    asset.setProductId(productId);
                    asset.setStationId(stationId);
                    asset.setQuantity(quantity);
                    customerBarrelAssetMapper.insert(asset);
                } else {
                    customerBarrelAssetMapper.setQuantity(asset.getId(), quantity);
                }
                break;
        }

        // 记录异常流水
        BarrelRecord record = new BarrelRecord();
        record.setCustomerId(customerId);
        record.setStationId(stationId);
        record.setProductId(productId);
        record.setType(type);
        record.setQuantity(quantity);
        record.setRelatedOrderId(relatedOrderId);
        record.setNote(note);
        record.setOperatorId(operatorId);
        record.setCreateTime(LocalDateTime.now());
        // 退桶申请设为待审批状态(1)，其他类型直接完成(2)
        if (Integer.valueOf(2).equals(type)) {
            record.setStatus(1);
        } else {
            record.setStatus(2);
        }
        barrelRecordMapper.insert(record);
    }

    /**
     * 站长审批退桶申请 —— <b>状态机 1 → 2 → 3，不允许跳步</b>（DEF-7）。
     *
     * <pre>
     *   1 待处理 --确认收桶--> 2 已确认收到空桶 --退押金--> 3 已退押金
     *   1 或 2  ----------------驳回--------------> 4 已驳回
     * </pre>
     *
     * <p>为什么要强制两步：旧实现允许一次点击从 1 直接到 3，
     * 于是站长可以在<b>桶还没收回来的情况下就把押金退了</b>。
     * 更糟的是旧代码在押金扣减失败（affected=0）时依然无条件把状态置为 3，
     * 等于"退款失败但记录显示已退"。</p>
     *
     * <p><b>退款金额只认押金条批次</b>（DEF-4）：按 FIFO 核销 {@code customer_barrel_lot}，
     * 退款 = Σ 核销数量 × 该批次买入时单价，<b>不用当前 {@code product.deposit}</b>。
     * 否则一调价就错：当年 30 元买的桶、现在涨价到 40，按当前价退等于白送 10 元。</p>
     */
    @Override
    @Transactional
    public void handleBarrelReturn(Long id, Integer status, String handleNote, Long operatorId) {
        BarrelRecord record = barrelRecordMapper.getById(id);
        if (record == null) {
            throw new BusinessException("退桶记录不存在");
        }
        if (!Integer.valueOf(2).equals(record.getType())) {
            throw new BusinessException("仅退桶记录可审批");
        }
        int cur = record.getStatus() == null ? 1 : record.getStatus();

        // ---- 驳回：待处理 / 已确认 都可以驳回，已退押金(3)不行 ----
        if (Integer.valueOf(4).equals(status)) {
            if (cur != 1 && cur != 2) {
                throw new BusinessException("该申请已完结（" + record.getStatusText() + "），无法驳回");
            }
            if (barrelRecordMapper.reject(id, handleNote) == 0) {
                throw new BusinessException("该申请状态已被变更，请刷新后重试");
            }
            return;
        }

        // ---- 1 → 2：确认收到空桶（只登记，不动账） ----
        if (Integer.valueOf(2).equals(status)) {
            if (cur != 1) {
                throw new BusinessException("当前状态为「" + record.getStatusText() + "」，只有待处理的申请可以确认收桶");
            }
            if (barrelRecordMapper.confirmReceived(id, operatorId, handleNote) == 0) {
                throw new BusinessException("该申请状态已被变更，请刷新后重试");
            }
            return;
        }

        // ---- 2 → 3：退押金（真正动账） ----
        if (Integer.valueOf(3).equals(status)) {
            if (cur != 2) {
                throw new BusinessException("请先确认已收到空桶，再退押金（当前：" + record.getStatusText() + "）");
            }
            doRefund(record, handleNote, operatorId);
            return;
        }

        throw new BusinessException("未知的审批状态: " + status);
    }

    /**
     * 退押金落账：按押金条批次 FIFO 核销 → 同步权益 → 扣押金账户 → 写流水与核销明细。
     * 任一步失败都会抛异常，由 {@link Transactional} 整体回滚。
     */
    private void doRefund(BarrelRecord record, String handleNote, Long operatorId) {
        Long cid = record.getCustomerId();
        Long sid = record.getStationId();
        Long pid = record.getProductId();
        int returnQty = record.getQuantity() == null ? 0 : record.getQuantity();
        if (cid == null || sid == null || pid == null) {
            throw new BusinessException("退桶记录缺少客户/水站/商品信息，无法退款");
        }
        if (returnQty <= 0) {
            throw new BusinessException("退桶数量不合法");
        }

        // [DEF-3] 该商品上还有欠桶时不允许退桶：权益可以退，但占着的桶得先还回来。
        // 按商品判断（A 水的多还桶不能抵 B 水的欠桶）；over<0（多还桶）不拦，那是水站暂存，合法。
        int overQty = barrelLedgerService.overQty(cid, sid, pid);
        if (overQty > 0) {
            throw new BusinessException("客户当前在该商品上欠桶 " + overQty + " 个，需先归还欠桶后方可退桶");
        }

        // 1) 按批次 FIFO 核销，金额只认买入时单价（内部已校验 returnQty <= 权益）
        BarrelLedgerService.LotConsumption consumption =
                barrelLedgerService.consumeLots(cid, sid, pid, returnQty, null, false);
        BigDecimal refund = consumption.getAmount();

        // 2) 同步权益汇总（数量与派生金额一起减）
        barrelLedgerService.decreaseRight(cid, sid, pid, returnQty, refund);

        // 3) 核销明细留痕：这笔钱到底是按哪几张押金条算出来的，事后可查
        for (BarrelLedgerService.LotConsumption.Detail d : consumption.getDetails()) {
            BarrelRecordLot row = new BarrelRecordLot();
            row.setRecordId(record.getId());
            row.setLotId(d.getLotId());
            row.setQty(d.getQty());
            row.setUnitPrice(d.getUnitPrice());
            row.setAmount(d.getAmount());
            barrelRecordLotMapper.insert(row);
        }

        // 4) 扣押金账户：必须真的扣到钱。旧实现 affected==0 时只是静默跳过，
        //    导致"记录显示已退押金，但顾客账户一分钱没多/没少"。
        if (refund.compareTo(BigDecimal.ZERO) > 0) {
            int affected = customerDepositAccountMapper.decreaseBalance(cid, sid, refund);
            if (affected == 0) {
                throw new BusinessException("押金账户余额不足，退款失败（应退 ¥" + refund + "）");
            }
            DepositRecord dr = new DepositRecord();
            dr.setCustomerId(cid);
            dr.setStationId(sid);
            dr.setType(DepositType.RETURN_BARREL);
            dr.setAmount(refund.negate());
            dr.setNote("退桶退押金: recordId=" + record.getId()
                    + (consumption.isHasMigratedPrice() ? "（含历史推断单价批次）" : ""));
            dr.setOperatorId(operatorId);
            dr.setCreateTime(LocalDateTime.now());
            depositRecordMapper.insert(dr);
        }

        // 5) 置为已退押金，并把实退金额写回记录
        if (barrelRecordMapper.finishRefund(record.getId(), handleNote, refund) == 0) {
            throw new BusinessException("该申请状态已被变更，请刷新后重试");
        }

        log.info("[退桶] 已退押金. recordId={}, customer={}, product={}, qty={}, refund={}, migratedPrice={}",
                record.getId(), cid, pid, returnQty, refund, consumption.isHasMigratedPrice());
    }

    /**
     * 退桶试算（只读）：按批次 FIFO 算出退 N 个桶能拿回多少钱，以及依据（哪几张押金条）。
     *
     * <p>顾客申请前就能看到金额，柜台不用吵架。
     * {@code hasMigratedPrice} 用于提示"这批单价是历史迁移推断的、不是真实成交价"。</p>
     */
    @Override
    public Map<String, Object> previewReturn(Long customerId, Long stationId, Long productId, Integer quantity) {
        Map<String, Object> data = new HashMap<>();
        int qty = quantity == null ? 0 : quantity;

        int right = barrelLedgerService.rightQty(customerId, stationId, productId);
        int over = barrelLedgerService.overQty(customerId, stationId, productId);
        int occupied = right + over; // 恒等式：占用 = 权益 + over

        data.put("rightQty", right);
        data.put("overQty", over);
        data.put("occupiedQty", occupied);
        data.put("quantity", qty);

        // 展示语义：over<0 是"多还的桶寄在水站"，over>0 是欠桶。两者都如实告知，不要和 0 混为一谈。
        if (over > 0) {
            data.put("blocked", true);
            data.put("blockedReason", "该商品还欠桶 " + over + " 个，需先归还欠桶后才能退桶退款");
        } else if (over < 0) {
            data.put("storageQty", -over);
        }
        if (right <= 0) {
            data.put("blocked", true);
            data.put("blockedReason", "该商品没有可退的桶权益");
        } else if (qty > right) {
            // 退桶唯一校验：qty ≤ 权益。over 不参与（over<0 是水站暂存，不影响可退数量）
            data.put("blocked", true);
            data.put("blockedReason", "最多只能退 " + right + " 个桶权益");
        }

        // [2026-09-21 新增] 信用风险拦截：该客户在本站有**逾期未结的赊账**（或挂账超额度）时，
        // 先别把押金退出去。**复用现成的 blocked 机制**（与「欠桶不许退桶」同一条通道），不新造拦截。
        //
        // 两种情形都拦，而且必须给**清楚的原因**：
        //   ① 押金余额充足 → 退了就等于"欠着货款还能把押金拿走"；
        //   ② 押金穿底（余额不足）→ 下一步 decreaseBalance 本来就会失败，但它报的是
        //      「押金余额不足」，站长与客户都看不出真实原因是"他还欠着钱"。
        //
        // ⚠️ 只在**确实要退钱**时拦（qty > 0 且权益足够、且不欠桶）——
        //   查询性质的调用（qty=0、或本来就退不了）不该被这件事挡住。
        if (!Boolean.TRUE.equals(data.get("blocked")) && qty > 0 && qty <= right && over <= 0) {
            String level = customerRiskService.levelOf(customerId, stationId);
            if (CustomerRiskService.ALERT.equals(level) || CustomerRiskService.FREEZE.equals(level)) {
                data.put("blocked", true);
                data.put("blockedReason", customerRiskService.returnBlockedReason(customerId, stationId));
                data.put("riskLevel", level);
            }
        }

        if (Boolean.TRUE.equals(data.get("blocked"))) {
            data.put("refundAmount", BigDecimal.ZERO);
            data.put("lots", java.util.Collections.emptyList());
            return data;
        }

        int effective = qty;
        data.put("effectiveQty", effective);
        if (effective <= 0) {
            data.put("refundAmount", BigDecimal.ZERO);
            data.put("lots", java.util.Collections.emptyList());
            return data;
        }

        // dryRun=true：只算不落库
        BarrelLedgerService.LotConsumption c =
                barrelLedgerService.consumeLots(customerId, stationId, productId, effective, null, true);
        List<Map<String, Object>> lots = new ArrayList<>();
        for (BarrelLedgerService.LotConsumption.Detail d : c.getDetails()) {
            Map<String, Object> m = new HashMap<>();
            m.put("lotId", d.getLotId());
            m.put("qty", d.getQty());
            m.put("unitPrice", d.getUnitPrice());
            m.put("amount", d.getAmount());
            lots.add(m);
        }
        data.put("lots", lots);
        data.put("refundAmount", c.getAmount());
        data.put("hasMigratedPrice", c.isHasMigratedPrice());
        return data;
    }
}
