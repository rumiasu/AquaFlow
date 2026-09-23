package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户桶资产实体类，对应数据库 customer_barrel_asset 表。
 * <p>只表示客户真正持有的桶。</p>
 */
@Data
public class CustomerBarrelAsset {

    /** ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 商品ID(桶装水) */
    private Long productId;

    /** 所属水站ID */
    private Long stationId;

    /**
     * 持有桶数 = 桶权益数（不是"手里现在有几个桶"）。
     * 手里实际有几个桶 = quantity + over，见 CustomerBarrelOver。
     */
    private Integer quantity;

    /**
     * 可退桶款（派生值）= Σ lot.remain_qty × lot.unit_price。
     * <p>唯一真相源是 customer_barrel_lot，本字段只做冗余展示/对账，
     * 不得用它反推单价（跨批次单价不同，反推必错）。</p>
     */
    private java.math.BigDecimal rightAmount;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
