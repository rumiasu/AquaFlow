package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.CustomerBarrelOver;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.CustomerBarrelAssetMapper;
import com.example.aquaflow.mapper.CustomerBarrelInTransitMapper;
import com.example.aquaflow.mapper.CustomerBarrelOverMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.DepositRecordMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.service.CustomerService;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.vo.CustomerProfileVO;
import com.example.aquaflow.vo.CustomerStationAssetVO;
import com.example.aquaflow.vo.CustomerStationVO;
import com.example.aquaflow.util.CustomerSearchMatcher;
import com.example.aquaflow.util.PriceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    /** 资产流水单次返回上限（避免长年在站客户把响应撑爆） */
    private static final int RECORDS_LIMIT = 30;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

    @Autowired
    private CustomerBarrelOverMapper customerBarrelOverMapper;

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Autowired
    private BarrelRecordMapper barrelRecordMapper;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private StationMapper stationMapper;

    /** 货到付款的欠款/首单判据要读订单（v48）。 */
    @Autowired
    private OrderMapper orderMapper;

    /** 货到付款能不能用由 PaymentService 一处判定，这里只取结论与原因（v48），不另拼一套。 */
    @Autowired
    private PaymentService paymentService;

    @Override
    public void update(Customer customer) {
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.update(customer);
    }

    @Override
    public Customer getById(Long id) {
        Customer customer = customerMapper.getById(id);
        if (customer == null) {
            throw new BusinessException("客户不存在");
        }
        return customer;
    }

    @Override
    public void save(Customer customer) {
        customer.setCreateTime(LocalDateTime.now());
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.insert(customer);
    }

    @Override
    public List<Customer> list(Long stationId) {
        if (stationId != null) {
            return customerMapper.listByStationId(stationId);
        }
        return customerMapper.list();
    }

    @Override
    public Map<String, Object> getCustomerStats(Long customerId) {
        Customer customer = customerMapper.getById(customerId);
        if (customer == null) {
            throw new BusinessException("客户不存在");
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("firstOrderTime", customer.getFirstOrderTime());
        stats.put("lastDeliveryTime", customer.getLastDeliveryTime());
        return stats;
    }

    @Override
    public CustomerStationConfig getOfflinePaymentConfig(Long customerId, Long stationId) {
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        if (config == null) {
            // 确保记录存在，默认关闭
            customerStationConfigMapper.ensureExists(customerId, stationId);
            config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        }
        return config;
    }

    @Override
    public void updateOfflinePaymentConfig(Long customerId, Long stationId, Integer enabled) {
        customerStationConfigMapper.ensureExists(customerId, stationId);
        customerStationConfigMapper.updateOfflinePaymentEnabled(customerId, stationId, enabled);
    }

    @Override
    public Map<String, Object> offlinePaymentSummary(Long customerId, Long stationId) {
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("offlinePaymentEnabled", config == null ? 0 : config.getOfflinePaymentEnabled());
        // 欠款/逾期（与"欠款即停"同源：逾期未结的现金单）
        out.put("overdueCount", orderMapper.countOverdueCashOrders(customerId, stationId));
        out.put("overdueAmount", orderMapper.sumOverdueCashAmount(customerId, stationId));
        // 历史订单数：站长在弹窗里一眼看到"这个客户在本站下过几单"
        out.put("orderCount", orderMapper.countCustomerOrdersAtStation(customerId, stationId));
        // 当前能不能用 + 原因（唯一判据在 PaymentService，这里不另拼文案）
        String reason = paymentService.offlinePaymentBlockReason(customerId, stationId);
        out.put("blockReason", reason);
        out.put("usable", reason == null);
        return out;
    }

    @Override
    public List<CustomerStationVO> listStationCustomers(Long stationId, String keyword) {
        if (stationId == null) {
            return new java.util.ArrayList<>();
        }
        List<CustomerStationVO> list = customerMapper.listStationCustomers(stationId);
        if (list == null) {
            return new ArrayList<>();
        }
        for (CustomerStationVO vo : list) {
            vo.deriveProfileMeta();
        }
        if (CustomerSearchMatcher.isBlank(keyword)) {
            return list;
        }
        // 带关键字：地址不在 listStationCustomers 的返回列里（那是客户画像口径，逐行再查一次地址
        // 会让"每个客户一次地址扫描"），所以这里用统一的候选集（含地址）打分，
        // 再按得分顺序把命中的 VO 取出来。口径差异：客户画像是 orders 驱动，
        // 候选集是"绑定 ∪ 本站订单"——只绑定没下单的客户不在本列表里，那是刻意的（见 CustomerMapper）。
        Map<Long, Map<String, Object>> candidateById = new java.util.HashMap<>();
        for (Map<String, Object> candidate : loadSearchCandidates(stationId)) {
            Object id = candidate.get("id");
            if (id instanceof Number) {
                candidateById.put(((Number) id).longValue(), candidate);
            }
        }
        List<CustomerStationVO> matched = CustomerSearchMatcher.rank(keyword, list,
                CustomerStationVO::getName, CustomerStationVO::getPhone,
                vo -> searchTextOf(candidateById.get(vo.getId())),
                CustomerSearchMatcher.MAX_RESULTS);
        for (CustomerStationVO vo : matched) {
            // 回填展示用地址：站长是靠地址认人的，只给姓名/电话等于没回答"为什么命中"
            vo.setAddressText(displayAddressOf(candidateById.get(vo.getId())));
        }
        return matched;
    }

    @Override
    public List<Map<String, Object>> searchStationCustomers(Long stationId, String keyword) {
        if (stationId == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> candidates = loadSearchCandidates(stationId);
        if (CustomerSearchMatcher.isBlank(keyword)) {
            // 不筛 = 最近建档的若干条。代客下单页首屏就是这条路径，返回空列表会让站长
            // 以为"客户没建档成功"（原 listOrderCustomers 的 null 语义，必须保持）
            List<Map<String, Object>> recent = new ArrayList<>();
            for (Map<String, Object> candidate : candidates) {
                if (recent.size() >= CustomerSearchMatcher.MAX_RESULTS) {
                    break;
                }
                recent.add(toSearchItem(candidate));
            }
            return recent;
        }
        List<Map<String, Object>> ranked = CustomerSearchMatcher.rank(keyword, candidates,
                c -> str(c, "name"), c -> str(c, "phone"), CustomerServiceImpl::searchTextOf,
                CustomerSearchMatcher.MAX_RESULTS);
        List<Map<String, Object>> out = new ArrayList<>(ranked.size());
        for (Map<String, Object> candidate : ranked) {
            out.add(toSearchItem(candidate));
        }
        return out;
    }

    /** 取本站搜索候选集（含地址文本），并处理"候选被截断"这一边界 */
    private List<Map<String, Object>> loadSearchCandidates(Long stationId) {
        List<Map<String, Object>> candidates =
                customerMapper.listSearchCandidates(stationId, CustomerSearchMatcher.MAX_CANDIDATES);
        if (candidates == null) {
            return new ArrayList<>();
        }
        if (candidates.size() >= CustomerSearchMatcher.MAX_CANDIDATES) {
            // 超限：按建档倒序截断（新客户优先保住），接口照常返回，只把"结果可能不全"记进日志。
            // 真到这一步说明单站客户量已超出产品口径（几百~几千），该换检索引擎而不是加大 LIMIT
            log.warn("[客户搜索] 站点 {} 候选数达到上限 {}，地址搜索可能查不到较老的客户（按建档倒序截断）",
                    stationId, CustomerSearchMatcher.MAX_CANDIDATES);
        }
        return candidates;
    }

    /** 打分用的地址文本：档案地址（默认在前、多条换行分隔）+ 本站订单地址快照 */
    private static String searchTextOf(Map<String, Object> candidate) {
        if (candidate == null) {
            return null;
        }
        return str(candidate, "addressText") + "\n" + str(candidate, "orderAddressText");
    }

    /** 展示用地址：默认地址那条（多地址时取第一行） */
    private static String displayAddressOf(Map<String, Object> candidate) {
        String text = str(candidate, "addressText");
        if (text == null || text.isEmpty()) {
            return null;
        }
        int cut = text.indexOf('\n');
        return cut >= 0 ? text.substring(0, cut) : text;
    }

    /** 列表项字段由后端定死：{@code searchTextOf} 用的 orderAddressText 不外泄给前端 */
    private static Map<String, Object> toSearchItem(Map<String, Object> candidate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", candidate.get("id"));
        item.put("name", candidate.get("name"));
        item.put("phone", candidate.get("phone"));
        item.put("customerType", candidate.get("customerType"));
        item.put("addressText", displayAddressOf(candidate));
        return item;
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : value.toString();
    }

    @Override
    public CustomerStationVO getStationCustomerDetail(Long customerId, Long stationId) {
        CustomerStationVO vo = customerMapper.getStationCustomer(customerId, stationId);
        if (vo != null) {
            vo.deriveProfileMeta();
        }
        return vo;
    }

    @Override
    public CustomerProfileVO getCustomerProfile(Long customerId, Long stationId) {
        CustomerStationVO base = customerMapper.getStationCustomer(customerId, stationId);
        if (base == null) {
            return null;
        }
        Customer c = customerMapper.getById(customerId);
        if (c == null) {
            return null;
        }

        CustomerProfileVO vo = new CustomerProfileVO();
        // 基础档案
        vo.setId(base.getId());
        vo.setName(base.getName());
        vo.setPhone(base.getPhone());
        vo.setCustomerType(base.getCustomerType());
        vo.setNote(base.getNote());
        vo.setTags(base.getTags());
        vo.setCreateTime(base.getCreateTime());
        vo.setFirstOrderTime(base.getFirstOrderTime());
        vo.setDefaultAddress(customerMapper.getDefaultAddress(customerId));

        // 消费画像
        vo.setTotalOrders(customerMapper.countCompletedOrders(customerId, stationId));
        vo.setTotalConsumption(customerMapper.sumConsumption(customerId, stationId));
        vo.setMonthOrders(customerMapper.countMonthOrders(customerId, stationId));
        vo.setMonthConsumption(customerMapper.sumMonthConsumption(customerId, stationId));
        vo.setLastOrderTime(customerMapper.getLastOrderTime(customerId, stationId));
        vo.setAvgCycleDays(c.getAvgCycleDays());

        // 资产
        vo.setDepositBalance(base.getDepositBalance());
        vo.setTicketBalance(customerMapper.getTicketBalance(customerId, stationId));
        vo.setOwedBarrels(customerMapper.getOwedBarrels(customerId, stationId));

        // 行为
        vo.setExceptionCount(customerMapper.countExceptions(customerId, stationId));
        vo.setFavoriteProducts(customerMapper.listFavoriteProducts(customerId, stationId));
        vo.setRecentOrders(decorateOrders(customerMapper.listRecentOrders(customerId, stationId)));

        // 权限
        vo.setCodEnabled(base.getCodEnabled());
        vo.setOfflinePaymentEnabled(base.getOfflinePaymentEnabled());
        return vo;
    }

    // ==================== 客户在本站的资产 ====================

    @Override
    public CustomerStationAssetVO getStationAssets(Long customerId, Long stationId) {
        if (customerId == null || stationId == null) {
            return null;
        }
        // 归属校验：客户必须属于该水站。这一步是"只能查本站资产"的第一道闸门 ——
        // getStationCustomer 的 SQL 同时带 customer_id 与 station_id，查不到即为无权查看。
        CustomerStationVO base = customerMapper.getStationCustomer(customerId, stationId);
        if (base == null) {
            return null;
        }

        CustomerStationAssetVO vo = new CustomerStationAssetVO();
        vo.setCustomerId(customerId);
        vo.setCustomerName(base.getName());
        vo.setPhone(base.getPhone());
        vo.setStationId(stationId);
        var station = stationMapper.getById(stationId);
        vo.setStationName(station != null ? station.getName() : "");

        // 商品缓存：名称/规格/押金多处要用，避免同一商品重复查库
        Map<Long, Product> productCache = new HashMap<>();

        // ---------- 水桶 ----------
        // 配送中桶先按商品聚合（只计 PENDING），一次查询供"明细"与"概览"共用。
        // [2026-09-15] 旧实现只排除 CANCELLED → 送达后（行被标 DELIVERED 但保留，供对账 E5 用）
        // 仍被算进"配送中"，于是站长看到「持有 2 个，配送中 2 个」——同一批桶被数了两遍。
        // 口径与 BarrelServiceImpl.getBarrelSummary 保持一致：只有 PENDING 才算配送中。
        Map<Long, Integer> inTransitByProduct = new LinkedHashMap<>();
        int inTransitTotal = 0;
        List<CustomerBarrelInTransit> transits =
                customerBarrelInTransitMapper.listByCustomerAndStation(customerId, stationId);
        if (transits != null) {
            for (CustomerBarrelInTransit t : transits) {
                if (!"PENDING".equals(t.getStatus())) continue;
                int q = t.getQty() != null ? t.getQty() : 0;
                inTransitTotal += q;
                if (t.getProductId() != null) {
                    inTransitByProduct.merge(t.getProductId(), q, Integer::sum);
                }
            }
        }

        List<CustomerBarrelAsset> assets =
                customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);

        // 明细要按"出现过桶的商品"取并集：
        // customer_barrel_asset 只记录客户手上的持有桶，配送中桶可能存在而持有记录为 0
        // （例如首单还在配送途中）。若只遍历 assets，就会出现"概览显示配送中 4 个、
        // 明细却一行都没有"的情况，站长无从判断是配送中的哪种桶。
        Map<Long, int[]> byProduct = new LinkedHashMap<>();   // productId -> [权益(已到手), 配送中(PENDING)]
        if (assets != null) {
            for (CustomerBarrelAsset a : assets) {
                if (a.getProductId() == null) continue;
                int[] slot = byProduct.computeIfAbsent(a.getProductId(), k -> new int[2]);
                slot[0] += a.getQuantity() != null ? a.getQuantity() : 0;
            }
        }
        for (Map.Entry<Long, Integer> e : inTransitByProduct.entrySet()) {
            if (e.getKey() == null) continue;
            int[] slot = byProduct.computeIfAbsent(e.getKey(), k -> new int[2]);
            slot[1] += e.getValue() != null ? e.getValue() : 0;
        }

        // ---------- over：按商品（可为负） ----------
        // 放在明细组装之前，因为 over 里出现的商品也可能不在 asset / inTransit 中
        // （例如权益已退完但还欠着桶），那种商品同样要在明细里露出来。
        int owedTotal = 0;
        Map<Long, Integer> overByProduct = new LinkedHashMap<>();
        List<CustomerBarrelOver> overs = customerBarrelOverMapper.listByCustomerAndStation(customerId, stationId);
        if (overs != null) {
            for (CustomerBarrelOver o : overs) {
                if (o.getProductId() == null) continue;
                int v = o.getOverQty() == null ? 0 : o.getOverQty();
                if (v == 0) continue;
                overByProduct.merge(o.getProductId(), v, Integer::sum);
                // 欠桶只统计正值：A 水多还的桶不能抵 B 水的欠桶
                if (v > 0) owedTotal += v;
            }
        }
        for (Long pid : overByProduct.keySet()) {
            byProduct.computeIfAbsent(pid, k -> new int[2]);
        }

        // 押金金额按**批次快照**算（与 BarrelServiceImpl / doRefund / 对账同口径）：
        //   权益部分   = customer_barrel_asset.right_amount（Σ lot.remain_qty × lot.unit_price）
        //   配送中部分 = customer_barrel_in_transit.unit_price × qty（下单时快照）
        // 不能用"当前本站押金 × 桶数"：站长调价后客户**已经付过的钱**会跟着变（docs/design/12 §4.3）。
        Map<Long, BigDecimal> assetAmountByProduct = new HashMap<>();
        if (assets != null) {
            for (CustomerBarrelAsset a : assets) {
                if (a.getProductId() == null) continue;
                assetAmountByProduct.merge(a.getProductId(),
                        a.getRightAmount() != null ? a.getRightAmount() : BigDecimal.ZERO, BigDecimal::add);
            }
        }
        Map<Long, BigDecimal> pendingAmountByProduct = new HashMap<>();
        if (transits != null) {
            for (CustomerBarrelInTransit t : transits) {
                if (!"PENDING".equals(t.getStatus()) || t.getProductId() == null) continue;
                int q = t.getQty() != null ? t.getQty() : 0;
                if (q == 0) continue;
                BigDecimal unit = t.getUnitPrice();
                if (unit == null || unit.compareTo(BigDecimal.ZERO) <= 0) {
                    // 无快照的历史行才回落到本站押金（与 BarrelLedgerService#resolveUnitPrice 一致）
                    unit = PriceUtil.calcDeposit(product(t.getProductId(), productCache),
                            inventoryMapper.getByStationAndProduct(stationId, t.getProductId()));
                }
                pendingAmountByProduct.merge(t.getProductId(), unit.multiply(BigDecimal.valueOf(q)), BigDecimal::add);
            }
        }

        int heldTotal = 0;
        BigDecimal barrelDepositTotal = BigDecimal.ZERO;
        List<CustomerStationAssetVO.BarrelItem> barrels = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : byProduct.entrySet()) {
            // [2026-09-15] slot[0] 是**权益**（已到手），不是"持有"：
            //   持有（展示）= 权益 + 配送中 —— 客户视角"买了就是你的"
            //   占用（还桶上限）= 权益 + over —— 物理在手，不含配送中（那批桶还没到手上）
            int right = e.getValue()[0];
            int inTransit = e.getValue()[1];
            int held = right + inTransit;
            int over = overByProduct.getOrDefault(e.getKey(), 0);
            // 全为 0 的行没有展示价值（历史残留的 0 行）
            if (held == 0 && over == 0) continue;

            Product p = product(e.getKey(), productCache);
            BigDecimal amount = assetAmountByProduct.getOrDefault(e.getKey(), BigDecimal.ZERO)
                    .add(pendingAmountByProduct.getOrDefault(e.getKey(), BigDecimal.ZERO));

            CustomerStationAssetVO.BarrelItem item = new CustomerStationAssetVO.BarrelItem();
            item.setProductId(e.getKey());
            item.setProductName(p != null ? p.getName() : "未知商品");
            item.setProductSpec(p != null ? p.getSpec() : "");
            item.setHeldQty(held);
            item.setRightQty(right);
            item.setInTransitQty(inTransit);
            item.setOverQty(over);
            item.setOccupiedQty(right + over); // 物理在手 = 权益 + over（不含配送中）
            // 单桶押金：按快照金额摊回（各批次买入价可能不同，展示取加权平均）
            item.setDepositPerBucket(held > 0
                    ? amount.divide(BigDecimal.valueOf(held), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            item.setDepositAmount(amount);
            barrels.add(item);

            heldTotal += held;
            barrelDepositTotal = barrelDepositTotal.add(amount);
        }

        // ---------- 押金 ----------
        BigDecimal depositBalance = customerDepositAccountMapper.getBalance(customerId, stationId);
        if (depositBalance == null) depositBalance = BigDecimal.ZERO;

        // ---------- 水票 ----------
        // 单价以站级库存 ticket_price 为准（与下单/试算的 PriceUtil 同口径），缺失时回退商品售价
        List<TicketAccount> accounts =
                ticketAccountMapper.listByCustomerAndStation(customerId, stationId);
        int ticketQtyTotal = 0;
        BigDecimal ticketValue = BigDecimal.ZERO;
        List<CustomerStationAssetVO.TicketItem> tickets = new ArrayList<>();
        if (accounts != null) {
            for (TicketAccount ta : accounts) {
                int remain = ta.getRemainQuantity() != null ? ta.getRemainQuantity() : 0;
                if (remain <= 0) continue;

                Product p = product(ta.getProductId(), productCache);
                Inventory inv = ta.getProductId() != null
                        ? inventoryMapper.getByStationAndProduct(stationId, ta.getProductId()) : null;
                // 水票"价值"= 水票支付时真正会扣的单价，所以直接走唯一计价入口：
                // 站级 ticket_price → product.ticket_price → 站级售价 → product.price（旧实现少了两级）
                BigDecimal unit = PriceUtil.calcUnitPrice(p, inv, PayMethod.TICKET);
                BigDecimal value = unit.multiply(BigDecimal.valueOf(remain));

                CustomerStationAssetVO.TicketItem item = new CustomerStationAssetVO.TicketItem();
                item.setProductId(ta.getProductId());
                item.setProductName(p != null ? p.getName() : "未知商品");
                item.setProductSpec(p != null ? p.getSpec() : "");
                item.setRemainQuantity(remain);
                item.setUnitPrice(unit);
                item.setTotalValue(value);
                item.setUpdateTime(ta.getUpdateTime());
                tickets.add(item);

                ticketQtyTotal += remain;
                ticketValue = ticketValue.add(value);
            }
        }

        // ---------- 流水（桶 / 水票 / 押金 三源合并，时间倒序） ----------
        List<CustomerStationAssetVO.AssetRecord> records = new ArrayList<>();
        appendBarrelRecords(records, barrelRecordMapper.listByCustomerAndStation(customerId, stationId), productCache);
        appendTicketRecords(records, ticketRecordMapper.listByCustomerAndStation(customerId, stationId), productCache);
        appendDepositRecords(records, depositRecordMapper.listByCustomerAndStation(customerId, stationId));
        records.sort(Comparator.comparing(
                CustomerStationAssetVO.AssetRecord::getTime,
                Comparator.nullsLast(Comparator.reverseOrder())));

        boolean truncated = records.size() > RECORDS_LIMIT;
        if (truncated) {
            records = new ArrayList<>(records.subList(0, RECORDS_LIMIT));
        }

        // ---------- 组装 ----------
        vo.setHeldBuckets(heldTotal);
        vo.setInTransitBuckets(inTransitTotal);
        vo.setOwedBuckets(owedTotal);
        vo.setBarrelDepositTotal(barrelDepositTotal);
        vo.setDepositBalance(depositBalance);
        vo.setTicketQuantity(ticketQtyTotal);
        vo.setTicketValue(ticketValue);
        vo.setTotalAssetValue(depositBalance.add(ticketValue));
        vo.setBarrels(barrels);
        vo.setTickets(tickets);
        vo.setRecords(records);
        vo.setRecordsTruncated(truncated);
        vo.setRecordsLimit(RECORDS_LIMIT);
        return vo;
    }

    private Product product(Long productId, Map<Long, Product> cache) {
        if (productId == null) return null;
        if (cache.containsKey(productId)) return cache.get(productId);
        Product p = productMapper.getById(productId);
        cache.put(productId, p);
        return p;
    }

    private void appendBarrelRecords(List<CustomerStationAssetVO.AssetRecord> out,
                                     List<BarrelRecord> list,
                                     Map<Long, Product> productCache) {
        if (list == null) return;
        for (BarrelRecord r : list) {
            int qty = r.getQuantity() != null ? r.getQuantity() : 0;
            // type=2 退桶：客户交回空桶，桶数减少；其余类型（新增押金桶/人工调整等）按增加处理
            boolean outbound = r.getType() != null && r.getType() == 2;

            CustomerStationAssetVO.AssetRecord rec = new CustomerStationAssetVO.AssetRecord();
            rec.setCategory("BARREL");
            rec.setCategoryText("水桶");
            rec.setTypeText(r.getTypeText());
            rec.setDirection(qty == 0 ? "FLAT" : (outbound ? "OUT" : "IN"));
            rec.setChangeText(qty == 0 ? "" : (outbound ? "-" : "+") + qty + " 个");
            rec.setStatusText(r.getStatus() != null && r.getType() != null && r.getType() == 2
                    ? r.getStatusText() : null);
            Product p = product(r.getProductId(), productCache);
            rec.setProductName(p != null ? p.getName() : null);
            rec.setOrderId(r.getRelatedOrderId());
            rec.setNote(r.getNote());
            rec.setTime(r.getCreateTime());
            rec.setTimeText(r.getCreateTime() == null ? "" : r.getCreateTime().format(FMT));
            out.add(rec);
        }
    }

    private void appendTicketRecords(List<CustomerStationAssetVO.AssetRecord> out,
                                     List<TicketRecord> list,
                                     Map<Long, Product> productCache) {
        if (list == null) return;
        for (TicketRecord r : list) {
            int inc = r.getIncreaseQty() != null ? r.getIncreaseQty() : 0;
            int dec = r.getDecreaseQty() != null ? r.getDecreaseQty() : 0;
            int delta = inc - dec;

            CustomerStationAssetVO.AssetRecord rec = new CustomerStationAssetVO.AssetRecord();
            rec.setCategory("TICKET");
            rec.setCategoryText("水票");
            rec.setTypeText(inc > 0 ? "水票入账" : (dec > 0 ? "水票消费" : "水票变动"));
            rec.setDirection(delta > 0 ? "IN" : (delta < 0 ? "OUT" : "FLAT"));
            rec.setChangeText(delta == 0 ? "" : (delta > 0 ? "+" : "") + delta + " 张");
            Product p = product(r.getProductId(), productCache);
            rec.setProductName(p != null ? p.getName() : null);
            rec.setOrderId(r.getOrderId());
            rec.setNote(r.getSource());
            rec.setTime(r.getCreateTime());
            rec.setTimeText(r.getCreateTime() == null ? "" : r.getCreateTime().format(FMT));
            out.add(rec);
        }
    }

    private void appendDepositRecords(List<CustomerStationAssetVO.AssetRecord> out,
                                      List<DepositRecord> list) {
        if (list == null) return;
        for (DepositRecord r : list) {
            BigDecimal amount = r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
            // 流水表里退款类金额常以负数落库；这里统一按"正数 = 入账，负数 = 退还"展示，
            // 并用类型判断兜底，避免有的历史数据只存了正数却表示退款。
            boolean negative = amount.compareTo(BigDecimal.ZERO) < 0
                    || (amount.compareTo(BigDecimal.ZERO) == 0)
                    || (amount.compareTo(BigDecimal.ZERO) > 0 && DepositType.isRefund(r.getType()));

            CustomerStationAssetVO.AssetRecord rec = new CustomerStationAssetVO.AssetRecord();
            rec.setCategory("DEPOSIT");
            rec.setCategoryText("押金");
            rec.setTypeText(DepositType.textOf(r.getType()));
            rec.setDirection(amount.compareTo(BigDecimal.ZERO) == 0 ? "FLAT"
                    : (negative ? "OUT" : "IN"));
            rec.setChangeText(amount.compareTo(BigDecimal.ZERO) == 0 ? ""
                    : (negative ? "-" : "+") + "¥" + amount.abs().setScale(2, java.math.RoundingMode.HALF_UP));
            rec.setOrderId(r.getRelatedOrderId());
            rec.setNote(r.getNote());
            rec.setTime(r.getCreateTime());
            rec.setTimeText(r.getCreateTime() == null ? "" : r.getCreateTime().format(FMT));
            out.add(rec);
        }
    }

    /** 给订单 map 补上后端派生的状态文案，前端只渲染 */
    private List<Map<String, Object>> decorateOrders(List<Map<String, Object>> orders) {
        if (orders == null) {
            return new java.util.ArrayList<>();
        }
        for (Map<String, Object> m : orders) {
            Object st = m.get("status");
            if (st instanceof Number) {
                m.put("statusText", com.example.aquaflow.constant.OrderStatus.textOf(((Number) st).intValue()));
            }
        }
        return orders;
    }
}
