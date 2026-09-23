package com.example.aquaflow.service;

import com.example.aquaflow.entity.CustomerBarrelAsset;

import java.math.BigDecimal;
import java.util.List;

/**
 * 桶资产核心服务。
 * <p>桶资产/押金账户的【唯一】变动入口。
 * 仅两个场景触发变动：
 * 1. 购桶入账：配送实收确认、站长手动购入
 * 2. 退桶出账：站长确认退桶、异常调解执行退押金
 * <p>
 * 严禁在其他地方（下单、配送完成等）直接操作 CustomerBarrelAsset / CustomerDepositAccount。
 */
public interface BarrelAssetService {

    /**
     * 购桶入账：收押金 + 增桶资产
     * 场景：配送实收确认时、站长手动为客户购桶
     *
     * @param customerId  客户ID
     * @param stationId   水站ID
     * @param items       购桶明细，每项含 productId, qty, depositAmount
     * @param operatorId  操作员ID
     */
    void purchaseBarrels(Long customerId, Long stationId,
                         List<BarrelPurchaseItem> items, Long operatorId);

    /**
     * 退桶出账：退押金 + 减桶资产
     * 场景：站长确认退桶、异常调解执行退押金
     *
     * @param customerId  客户ID
     * @param stationId   水站ID
     * @param items       退桶明细，每项含 productId, qty, refundAmount
     * @param operatorId  操作员ID
     */
    void returnBarrels(Long customerId, Long stationId,
                       List<BarrelReturnItem> items, Long operatorId);

    /**
     * 查询当前持有桶资产（按产品分组）
     * 用途：下单上限校验、前端展示
     */
    List<CustomerBarrelAsset> getHeldAssets(Long customerId, Long stationId);

    /**
     * 查询配送中桶资产（下单已收押金但未送达确认）
     * 用途：下单上限校验时计入"已承诺资产"
     */
    List<BarrelInTransitDTO> getInTransitAssets(Long customerId, Long stationId);

    /**
     * 购桶入账明细项
     */
    class BarrelPurchaseItem {
        private Long productId;
        private Integer qty;
        private java.math.BigDecimal depositAmount;

        public BarrelPurchaseItem() {}
        public BarrelPurchaseItem(Long productId, Integer qty, java.math.BigDecimal depositAmount) {
            this.productId = productId;
            this.qty = qty;
            this.depositAmount = depositAmount;
        }
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQty() { return qty; }
        public void setQty(Integer qty) { this.qty = qty; }
        public java.math.BigDecimal getDepositAmount() { return depositAmount; }
        public void setDepositAmount(java.math.BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    }

    /**
     * 退桶出账明细项
     */
    class BarrelReturnItem {
        private Long productId;
        private Integer qty;
        private java.math.BigDecimal refundAmount;

        public BarrelReturnItem() {}
        public BarrelReturnItem(Long productId, Integer qty, java.math.BigDecimal refundAmount) {
            this.productId = productId;
            this.qty = qty;
            this.refundAmount = refundAmount;
        }
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQty() { return qty; }
        public void setQty(Integer qty) { this.qty = qty; }
        public java.math.BigDecimal getRefundAmount() { return refundAmount; }
        public void setRefundAmount(java.math.BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    }

    /**
     * 配送中桶资产DTO
     */
    class BarrelInTransitDTO {
        private Long productId;
        private Integer qty;
        private Long relatedOrderId;
        private String status; // PENDING/DELIVERED/CANCELLED

        public BarrelInTransitDTO() {}
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQty() { return qty; }
        public void setQty(Integer qty) { this.qty = qty; }
        public Long getRelatedOrderId() { return relatedOrderId; }
        public void setRelatedOrderId(Long relatedOrderId) { this.relatedOrderId = relatedOrderId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}