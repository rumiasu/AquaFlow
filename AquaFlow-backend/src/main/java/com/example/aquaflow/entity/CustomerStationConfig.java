package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户×水站权限配置
 * V1 仅用于线下支付授权
 */
@Data
public class CustomerStationConfig {

    /** 主键 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 水站ID */
    private Long stationId;

    /** 是否允许线下支付 */
    private Integer offlinePaymentEnabled;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}