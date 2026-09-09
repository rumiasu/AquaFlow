package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.CustomerBarrelOwed;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.CustomerBarrelAssetMapper;
import com.example.aquaflow.mapper.CustomerBarrelInTransitMapper;
import com.example.aquaflow.mapper.CustomerBarrelOwedMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.DepositRecordMapper;
import com.example.aquaflow.mapper.ProductMapper;
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
public class BarrelServiceImpl implements BarrelService {

    @Autowired
    private BarrelRecordMapper barrelRecordMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerBarrelOwedMapper customerBarrelOwedMapper;

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

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

        // 在途桶按产品聚合（排除已取消）
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

        // 欠桶（站点级，不区分产品）
        int owedBuckets = 0;
        CustomerBarrelOwed owed = customerBarrelOwedMapper.get(customerId, stationId);
        if (owed != null && owed.getOwedQty() != null) owedBuckets = owed.getOwedQty();

        // 在途桶
        int deliveryBuckets = 0;
        int pendingDeliveryBuckets = 0;
        List<CustomerBarrelInTransit> transits = customerBarrelInTransitMapper.listByCustomerAndStation(customerId, stationId);
        if (transits != null) {
            for (CustomerBarrelInTransit t : transits) {
                if (t.getStatus() != null && "CANCELLED".equals(t.getStatus())) continue;
                deliveryBuckets += t.getQty() != null ? t.getQty() : 0;
                // 仅统计尚未送达确认(PENDING)的在途桶，避免把已收到的桶误判为"配送中"
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
     * 站长审批退桶申请
     * @param id          退桶记录ID
     * @param status      2=确认收到空桶 3=已退押金 4=驳回
     * @param handleNote  处理备注
     * @param operatorId  站长ID
     */
    @Override
    @Transactional
    public void handleBarrelReturn(Long id, Integer status, String handleNote, Long operatorId) {
        BarrelRecord record = barrelRecordMapper.getById(id);
        if (record == null) {
            throw new RuntimeException("退桶记录不存在");
        }
        if (!Integer.valueOf(2).equals(record.getType())) {
            throw new RuntimeException("仅退桶记录可审批");
        }
        if (record.getStatus() != null && record.getStatus() != 1) {
            throw new RuntimeException("该退桶申请已处理，不可重复操作");
        }

        if (Integer.valueOf(4).equals(status)) {
            // 驳回：仅更新状态
            barrelRecordMapper.updateStatus(id, 4, handleNote);
            return;
        }

        if (Integer.valueOf(2).equals(status)) {
            // 确认收到空桶：更新状态，暂不扣资产/退押金
            barrelRecordMapper.updateStatus(id, 2, handleNote);
            return;
        }

        if (Integer.valueOf(3).equals(status)) {
            // 退押金：先确认收到空桶，再扣减桶资产并退押金
            // 校验：客户有欠桶时不允许退桶
            CustomerBarrelOwed owed = customerBarrelOwedMapper.get(record.getCustomerId(), record.getStationId());
            int owedQty = owed != null && owed.getOwedQty() != null ? owed.getOwedQty() : 0;
            if (owedQty > 0) {
                throw new RuntimeException("客户当前欠桶 " + owedQty + " 个，需先归还欠桶后方可退桶");
            }

            // 扣减桶资产
            CustomerBarrelAsset asset = customerBarrelAssetMapper.getByCustomerAndProduct(
                    record.getCustomerId(), record.getProductId(), record.getStationId());
            if (asset == null || asset.getQuantity() == null || asset.getQuantity() < record.getQuantity()) {
                throw new RuntimeException("桶资产不足，无法退桶");
            }
            customerBarrelAssetMapper.decreaseQuantity(asset.getId(), record.getQuantity());

            // 退押金（如有）
            java.math.BigDecimal refund = record.getDepositRefund();
            if (refund != null && refund.compareTo(java.math.BigDecimal.ZERO) > 0) {
                int affected = customerDepositAccountMapper.decreaseBalance(
                        record.getCustomerId(), record.getStationId(), refund);
                if (affected > 0) {
                    com.example.aquaflow.entity.DepositRecord dr = new com.example.aquaflow.entity.DepositRecord();
                    dr.setCustomerId(record.getCustomerId());
                    dr.setStationId(record.getStationId());
                    dr.setType(com.example.aquaflow.constant.DepositType.RETURN_BARREL);
                    dr.setAmount(refund.negate());
                    dr.setNote("退桶退押金: recordId=" + id);
                    dr.setOperatorId(operatorId);
                    dr.setCreateTime(LocalDateTime.now());
                    depositRecordMapper.insert(dr);
                }
            }

            barrelRecordMapper.updateStatus(id, 3, handleNote);
        }
    }
}
