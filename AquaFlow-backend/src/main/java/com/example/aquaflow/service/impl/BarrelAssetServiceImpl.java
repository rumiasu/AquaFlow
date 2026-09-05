package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.mapper.CustomerBarrelAssetMapper;
import com.example.aquaflow.mapper.CustomerBarrelInTransitMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.DepositRecordMapper;
import com.example.aquaflow.service.BarrelAssetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 桶资产核心服务实现。
 * <p>桶资产/押金账户的【唯一】变动入口。
 */
@Service
@Slf4j
public class BarrelAssetServiceImpl implements BarrelAssetService {

    @Autowired
    private CustomerBarrelAssetMapper assetMapper;

    @Autowired
    private CustomerBarrelInTransitMapper inTransitMapper;

    @Autowired
    private CustomerDepositAccountMapper depositAccountMapper;

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Override
    @Transactional
    public void purchaseBarrels(Long customerId, Long stationId,
                                List<BarrelPurchaseItem> items, Long operatorId) {
        if (items == null || items.isEmpty()) {
            return;
        }

        for (BarrelPurchaseItem item : items) {
            if (item.getQty() == null || item.getQty() <= 0) {
                continue;
            }

            Long productId = item.getProductId();
            Integer qty = item.getQty();
            BigDecimal depositAmount = item.getDepositAmount() != null ? item.getDepositAmount() : BigDecimal.ZERO;

            // 1. 增加桶资产
            CustomerBarrelAsset existing = assetMapper.getByCustomerAndProduct(customerId, productId, stationId);
            if (existing == null) {
                CustomerBarrelAsset asset = new CustomerBarrelAsset();
                asset.setCustomerId(customerId);
                asset.setProductId(productId);
                asset.setStationId(stationId);
                asset.setQuantity(qty);
                asset.setUpdateTime(LocalDateTime.now());
                assetMapper.insert(asset);
            } else {
                assetMapper.increaseQuantity(existing.getId(), qty);
            }

            // 2. 增加押金账户余额
            if (depositAmount.compareTo(BigDecimal.ZERO) > 0) {
                depositAccountMapper.increaseBalance(customerId, stationId, depositAmount);

                // 记录押金流水：类型 1=新增押金桶
                DepositRecord dr = new DepositRecord();
                dr.setCustomerId(customerId);
                dr.setStationId(stationId);
                dr.setType(DepositType.PURCHASE); // 1
                dr.setAmount(depositAmount);
                dr.setNote("购桶押金入账");
                dr.setOperatorId(operatorId);
                dr.setCreateTime(LocalDateTime.now());
                depositRecordMapper.insert(dr);
            }

            // 3. 清理在途记录（如果有关联订单，由上层传入 relatedOrderId 处理）
            // 这里不处理在途清理，由上层业务在调用前处理 in_transit 表
        }

        log.info("[BarrelAsset] 购桶入账完成: customerId={}, stationId={}, items={}", customerId, stationId, items.size());
    }

    @Override
    @Transactional
    public void returnBarrels(Long customerId, Long stationId,
                              List<BarrelReturnItem> items, Long operatorId) {
        if (items == null || items.isEmpty()) {
            return;
        }

        for (BarrelReturnItem item : items) {
            if (item.getQty() == null || item.getQty() <= 0) {
                continue;
            }

            Long productId = item.getProductId();
            Integer qty = item.getQty();
            BigDecimal refundAmount = item.getRefundAmount() != null ? item.getRefundAmount() : BigDecimal.ZERO;

            // 1. 减少桶资产
            CustomerBarrelAsset existing = assetMapper.getByCustomerAndProduct(customerId, productId, stationId);
            if (existing == null || existing.getQuantity() == null || existing.getQuantity() < qty) {
                throw new RuntimeException("桶资产不足，无法退桶: customerId=" + customerId + ", productId=" + productId);
            }
            assetMapper.decreaseQuantity(existing.getId(), qty);

            // 2. 减少押金账户余额（退押金给客户）
            if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                depositAccountMapper.decreaseBalance(customerId, stationId, refundAmount);

                // 记录押金流水：类型 8=退桶退押金
                DepositRecord dr = new DepositRecord();
                dr.setCustomerId(customerId);
                dr.setStationId(stationId);
                dr.setType(DepositType.RETURN_BARREL); // 8
                dr.setAmount(refundAmount.negate()); // 负金额表示退出
                dr.setNote("退桶退押金");
                dr.setOperatorId(operatorId);
                dr.setCreateTime(LocalDateTime.now());
                depositRecordMapper.insert(dr);
            }
        }

        log.info("[BarrelAsset] 退桶出账完成: customerId={}, stationId={}, items={}", customerId, stationId, items.size());
    }

    @Override
    public List<CustomerBarrelAsset> getHeldAssets(Long customerId, Long stationId) {
        return assetMapper.listByCustomerAndStation(customerId, stationId);
    }

    @Override
    public List<BarrelInTransitDTO> getInTransitAssets(Long customerId, Long stationId) {
        List<CustomerBarrelInTransit> list = inTransitMapper.listByCustomerAndStation(customerId, stationId);
        return list.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private BarrelInTransitDTO toDTO(CustomerBarrelInTransit t) {
        BarrelInTransitDTO dto = new BarrelInTransitDTO();
        dto.setProductId(t.getProductId());
        dto.setQty(t.getQty());
        dto.setRelatedOrderId(t.getRelatedOrderId());
        dto.setStatus(t.getStatus());
        return dto;
    }
}