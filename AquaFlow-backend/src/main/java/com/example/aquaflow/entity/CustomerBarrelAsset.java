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

    /** 持有桶数 */
    private Integer quantity;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
