package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 常用订单模板实体类，对应数据库 order_template 表。
 * <p>记录用户设置的默认下单参数，支持多商品模板。</p>
 */
@Data
public class OrderTemplate {

    /** ID，主键自增 */
    private Integer id;

    /** 客户ID，关联 customer 表 */
    private Integer customerId;

    /** 模板名称（如：家里、公司、父母家） */
    private String name;

    /** 默认配送地址ID */
    private Integer addressId;

    /** 默认备注 */
    private String specialNote;

    /** 是否默认模板 0=否 1=是 */
    private Integer isDefault;

    /** 是否启用：0=关闭 1=开启 */
    private Integer enabled;

    /** 模板明细（多商品） */
    private List<OrderTemplateItem> items;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
