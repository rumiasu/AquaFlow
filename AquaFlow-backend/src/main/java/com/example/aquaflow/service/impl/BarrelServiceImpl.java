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
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.service.BarrelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

    /** 桶账唯一写入口（权益/批次/over），退桶核销必须走它 */
    @Autowired
    private BarrelLedgerService barrelLedgerService;

    /** 退桶 → 押金条核销明细，用于事后审计"这笔钱按哪几张押金条算的" */
    @Autowired
    private BarrelRecordLotMapper barrelRecordLotMapper;

    @Override
    public List<CustomerBarrelAsset> getAssets(Long customerId, Long stationId) {
        return customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);
    }

    @Override
    public List<BarrelRecord> listRecords(Long customerId, Long stationId) {
        return barrelRecordMapper.listByCustomerAndStation(customerId, stationId);
    }

    @Override
    public List<Map<String, Object>> getBarrelSummaryByType(Long customerId, Long stationId) {
        List<CustomerBarrelAsset> assets = customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);

        // 配送中桶按产品聚合（排除已取消）
        Map<Long, Integer> inTransitByProduct = new HashMap<>();
        List<CustomerBarrelInTransit> transits = customerBarrelInTransitMapper.listByCustomerAndStation(customerId, stationId);
        if (transits != null) {
            for (CustomerBarrelInTransit t : transits) {
                if (t.getStatus() != null && "CANCELLED".equals(t.getStatus())) continue;
                Long pid = t.getProductId();
                if (pid == null) continue;
                inTransitByProduct.put(pid, inTransitByProduct.getOrDefault(pid, 0) + (t.getQty() != null ? t.getQty() : 0));
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (CustomerBarrelAsset asset : assets) {
            Long pid = asset.getProductId();
            Product product = pid != null ? productMapper.getById(pid) : null;
            int assetQty = asset.getQuantity() != null ? asset.getQuantity() : 0;
            BigDecimal deposit = product != null && product.getDeposit() != null ? product.getDeposit() : BigDecimal.ZERO;
            int inTransitQty = inTransitByProduct.getOrDefault(pid, 0);
            BigDecimal depositTotal = deposit.multiply(BigDecimal.valueOf(assetQty));
            Map<String, Object> map = new HashMap<>();
            map.put("productId", pid);
            map.put("productName", product != null ? product.getName() : "未知商品");
            map.put("productSpec", product != null ? product.getSpec() : "");
            map.put("assetQty", assetQty);
            map.put("inTransitQty", inTransitQty);
            map.put("deposit", deposit);
            map.put("depositTotal", depositTotal);
            result.add(map);
        }
        return result;
    }

    @Override
    public Map<String, Object> getBarrelSummary(Long customerId, Long stationId) {
        Map<String, Object> summary = new HashMap<>();

        // 持有桶 + 桶押金合计
        List<CustomerBarrelAsset> assets = customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);
        int heldBuckets = 0;
        BigDecimal depositTotal = BigDecimal.ZERO;
        if (assets != null) {
            for (CustomerBarrelAsset a : assets) {
                int q = a.getQuantity() != null ? a.getQuantity() : 0;
                heldBuckets += q;
                Product p = a.getProductId() != null ? productMapper.getById(a.getProductId()) : null;
                BigDecimal dep = p != null && p.getDeposit() != null ? p.getDeposit() : BigDecimal.ZERO;
                depositTotal = depositTotal.add(dep.multiply(BigDecimal.valueOf(q)));
            }
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
        if (assets != null) {
            for (CustomerBarrelAsset a : assets) {
                int q = a.getQuantity() != null ? a.getQuantity() : 0;
                Long pid = a.getProductId();
                int over = 0;
                if (overs != null) {
                    for (CustomerBarrelOver o : overs) {
                        if (o.getProductId() != null && o.getProductId().equals(pid)) {
                            over = o.getOverQty() == null ? 0 : o.getOverQty();
                            break;
                        }
                    }
                }
                occupiedBuckets += q + over;
            }
        }

        // 配送中桶
        int deliveryBuckets = 0;
        int pendingDeliveryBuckets = 0;
        List<CustomerBarrelInTransit> transits = customerBarrelInTransitMapper.listByCustomerAndStation(customerId, stationId);
        if (transits != null) {
            for (CustomerBarrelInTransit t : transits) {
                if (t.getStatus() != null && "CANCELLED".equals(t.getStatus())) continue;
                deliveryBuckets += t.getQty() != null ? t.getQty() : 0;
                // 只把 PENDING 计入「配送中」：DELIVERED 表示桶已送到顾客手上，
                // 若一并计入会把已收到的桶误判成还在路上。
                if ("PENDING".equals(t.getStatus())) {
                    pendingDeliveryBuckets += t.getQty() != null ? t.getQty() : 0;
                }
            }
        }

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

        summary.put("heldBuckets", heldBuckets);
        summary.put("actualBuckets", heldBuckets);
        summary.put("owedBuckets", owedBuckets);
        // 实际持有（权益 + over，顾客手上真有几个桶）与水站暂存（多还的桶）
        summary.put("occupiedBuckets", occupiedBuckets);
        summary.put("storageBuckets", storageBuckets);
        summary.put("deliveryBuckets", deliveryBuckets);
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
    @Transactional
    public void handleBarrelException(Long customerId, Long stationId, Long productId, Integer type, Integer quantity, Long relatedOrderId, String note, Long operatorId) {
        // #25: 校验quantity必须大于0
        if (quantity == null || quantity <= 0) {
            throw new RuntimeException("数量必须大于0");
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
                    throw new RuntimeException("桶资产不足");
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
