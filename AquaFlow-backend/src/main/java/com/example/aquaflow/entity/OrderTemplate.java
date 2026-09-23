package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 常用订单实体类，对应数据库 order_template 表。
 * <p>记录客户常用的订单模板。</p>
 */
@Data
public class OrderTemplate {

    /** ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 模板名称 */
    private String name;

    /** 特殊说明 */
    private String specialNote;

    /** 是否启用: 0 禁用 1 启用 */
    private Integer enabled;

    /** 是否默认模板: 0 非默认 1 默认 */
    private Integer isDefault;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 模板明细(关联查询字段) */
    private List<OrderTemplateItem> items;
}
