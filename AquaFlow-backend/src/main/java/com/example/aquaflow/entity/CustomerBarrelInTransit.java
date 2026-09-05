package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户在途桶资产实体类，对应数据库 customer_barrel_in_transit 表。
 * <p>记录下单已收押金但未送达确认的桶数量。
 * 状态流转：PENDING(下单创建) -> DELIVERED(实收确认转正) / CANCELLED(取消释放)
 */
@Data
public class CustomerBarrelInTransit {

    /** ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 产品ID(桶装水) */
    private Long productId;

    /** 在途桶数 */
    private Integer qty;

    /** 关联订单ID */
    private Long relatedOrderId;

    /** 状态: PENDING/DELIVERED/CANCELLED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}