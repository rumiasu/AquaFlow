package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.mapper.BarrelMapper;
import com.example.aquaflow.service.BarrelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BarrelServiceImpl implements BarrelService {

    @Autowired
    private BarrelMapper barrelMapper;

    @Override
    public Map<String, Object> getSummary(Integer customerId) {
        int deliveryBuckets = barrelMapper.sumDeliveryBuckets(customerId);
        int returnBuckets = barrelMapper.sumReturnBuckets(customerId);
        int pendingReturns = barrelMapper.sumPendingReturns(customerId);
        int confirmedReturns = barrelMapper.sumConfirmedReturns(customerId);
        BigDecimal depositBalance = barrelMapper.getDepositBalance(customerId);

        // 当前持有桶数 = 已送出 - 已回收（订单维度）
        int holdingBuckets = deliveryBuckets - returnBuckets;
        // 实际在手桶数 = 持有 - 已确认退回
        int actualBuckets = Math.max(0, holdingBuckets - confirmedReturns);

        Map<String, Object> summary = new HashMap<>();
        summary.put("deliveryBuckets", deliveryBuckets);
        summary.put("returnBuckets", returnBuckets);
        summary.put("holdingBuckets", holdingBuckets);
        summary.put("actualBuckets", actualBuckets);
        summary.put("pendingReturns", pendingReturns);
        summary.put("confirmedReturns", confirmedReturns);
        summary.put("depositBalance", depositBalance);
        summary.put("depositPerBucket", new BigDecimal("30"));

        // 上次订单信息（用于默认下单参数）
        Map<String, Object> lastOrder = barrelMapper.getLastOrder(customerId);
        summary.put("lastOrder", lastOrder);

        return summary;
    }

    @Override
    public List<BarrelRecord> listRecords(Integer customerId) {
        return barrelMapper.listByCustomerId(customerId);
    }

    @Override
    public List<BarrelRecord> listAllRecords() {
        return barrelMapper.listAll();
    }

    @Override
    public List<Map<String, Object>> getBarrelSummaryByType(Integer customerId) {
        return barrelMapper.getBarrelSummaryByType(customerId);
    }

    @Override
    @Transactional
    public BarrelRecord requestReturn(Integer customerId, Integer quantity, BigDecimal depositRefund, String note) {
        // 校验持有桶数
        int deliveryBuckets = barrelMapper.sumDeliveryBuckets(customerId);
        int returnBuckets = barrelMapper.sumReturnBuckets(customerId);
        int confirmedReturns = barrelMapper.sumConfirmedReturns(customerId);
        int pendingReturns = barrelMapper.sumPendingReturns(customerId);

        int actualBuckets = Math.max(0, (deliveryBuckets - returnBuckets) - confirmedReturns);
        int available = actualBuckets - pendingReturns;

        if (quantity > available) {
            throw new RuntimeException("可退桶数不足，当前可退 " + available + " 桶");
        }

        // 校验押金退还金额
        if (depositRefund != null && depositRefund.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal balance = barrelMapper.getDepositBalance(customerId);
            if (depositRefund.compareTo(balance) > 0) {
                throw new RuntimeException("押金余额不足，当前余额 " + balance + " 元");
            }
        }

        // 创建退桶记录
        BarrelRecord record = new BarrelRecord();
        record.setCustomerId(customerId);
        record.setQuantity(quantity);
        record.setStatus(1); // 待处理
        record.setDepositRefund(depositRefund != null ? depositRefund : BigDecimal.ZERO);
        record.setNote(note);
        record.setCreateTime(LocalDateTime.now());
        barrelMapper.insert(record);

        // 如果有退押金，先扣除客户押金余额（待处理后真正退还）
        if (depositRefund != null && depositRefund.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal currentBalance = barrelMapper.getDepositBalance(customerId);
            barrelMapper.updateDepositBalance(customerId, currentBalance.subtract(depositRefund));
        }

        return record;
    }

    @Override
    @Transactional
    public void handleReturn(Integer id, Integer status, String handleNote) {
        BarrelRecord record = barrelMapper.getById(id);
        if (record == null) {
            throw new RuntimeException("退桶记录不存在");
        }
        if (record.getStatus() != 1) {
            throw new RuntimeException("该记录已处理");
        }
        barrelMapper.updateStatus(id, status, handleNote);

        // 如果驳回且有押金扣除，退还押金
        if (status == 4 && record.getDepositRefund() != null
                && record.getDepositRefund().compareTo(java.math.BigDecimal.ZERO) > 0) {
            java.math.BigDecimal balance = barrelMapper.getDepositBalance(record.getCustomerId());
            barrelMapper.updateDepositBalance(record.getCustomerId(), balance.add(record.getDepositRefund()));
        }
    }
}
