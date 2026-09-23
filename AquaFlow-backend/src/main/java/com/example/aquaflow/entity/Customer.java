package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户实体类，对应数据库 customer 表。
 * <p>这是客户"身份档案"，不要把订单统计、押金余额、复杂业务状态全部塞进来。</p>
 * <p>V13: 移除永久绑定水站字段，客户为全局身份，资产按 (customer_id, station_id) 隔离。</p>
 */
@Data
public class Customer {

    /** 客户ID，主键自增 */
    private Long id;

    /** 微信openid */
    private String openid;

    /** 客户名 */
    private String name;

    /** 联系电话 */
    private String phone;

    /** 客户类型: 1 个人 2 企业 */
    private Integer customerType;

    /** 备注 */
    private String note;

    /** 首次下单时间 */
    private LocalDateTime firstOrderTime;

    /** 最近配送时间 */
    private LocalDateTime lastDeliveryTime;

    /** 平均下单周期（天），来自 customer.avg_cycle_days，用于客户画像 */
    private Integer avgCycleDays;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
