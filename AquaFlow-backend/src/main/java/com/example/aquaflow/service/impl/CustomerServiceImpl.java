package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.CustomerBarrelOwed;
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
import com.example.aquaflow.mapper.CustomerBarrelOwedMapper;
import com.example.aquaflow.mapper.CustomerBarrelOverMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.DepositRecordMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.service.CustomerService;
import com.example.aquaflow.vo.CustomerProfileVO;
import com.example.aquaflow.vo.CustomerStationAssetVO;
import com.example.aquaflow.vo.CustomerStationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
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
    private CustomerBarrelOwedMapper customerBarrelOwedMapper;

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

    @Override
    public void update(Customer customer) {
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.update(customer);
    }

    @Override
    public Customer getById(Long id) {
        Customer customer = customerMapper.getById(id);
        if (customer == null) {
            throw new RuntimeException("客户不存在");
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
            throw new RuntimeException("客户不存在");
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
    public List<CustomerStationVO> listStationCustomers(Long stationId) {
        if (stationId == null) {
            return new java.util.ArrayList<>();
        }
        List<CustomerStationVO> list = customerMapper.listStationCustomers(stationId);
        if (list != null) {
            for (CustomerStationVO vo : list) {
                vo.deriveProfileMeta();
            }
        }
        return list;
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
        // 配送中桶先按商品聚合（排除已取消），一次查询供"明细"与"概览"共用
        Map<Long, Integer> inTransitByProduct = new LinkedHashMap<>();
        int inTransitTotal = 0;
        List<CustomerBarrelInTransit> transits =
                customerBarrelInTransitMapper.listByCustomerAndStation(customerId, stationId);
        if (transits != null) {
            for (CustomerBarrelInTransit t : transits) {
                if ("CANCELLED".equals(t.getStatus())) continue;
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
        Map<Long, int[]> byProduct = new LinkedHashMap<>();   // productId -> [held, inTransit]
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

        int heldTotal = 0;
        BigDecimal barrelDepositTotal = BigDecimal.ZERO;
        List<CustomerStationAssetVO.BarrelItem> barrels = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : byProduct.entrySet()) {
            int held = e.getValue()[0];
            int inTransit = e.getValue()[1];
            int over = overByProduct.getOrDefault(e.getKey(), 0);
            // 全为 0 的行没有展示价值（历史残留的 0 行）
            if (held == 0 && inTransit == 0 && over == 0) continue;

            Product p = product(e.getKey(), productCache);
            BigDecimal per = p != null && p.getDeposit() != null ? p.getDeposit() : BigDecimal.ZERO;
            BigDecimal amount = per.multiply(BigDecimal.valueOf(held));

            CustomerStationAssetVO.BarrelItem item = new CustomerStationAssetVO.BarrelItem();
            item.setProductId(e.getKey());
            item.setProductName(p != null ? p.getName() : "未知商品");
            item.setProductSpec(p != null ? p.getSpec() : "");
            item.setHeldQty(held);
            item.setInTransitQty(inTransit);
            item.setOverQty(over);
            item.setOccupiedQty(held + over); // 恒等式：占用 = 权益 + over
            item.setDepositPerBucket(per);
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
                BigDecimal unit = BigDecimal.ZERO;
                if (inv != null && inv.getTicketPrice() != null
                        && inv.getTicketPrice().compareTo(BigDecimal.ZERO) > 0) {
                    unit = inv.getTicketPrice();
                } else if (p != null && p.getPrice() != null) {
                    unit = p.getPrice();
                }
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
