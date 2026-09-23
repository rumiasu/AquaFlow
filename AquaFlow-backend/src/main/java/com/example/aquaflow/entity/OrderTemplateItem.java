package com.example.aquaflow.entity;

import lombok.Data;

/**
 * 常用订单明细实体类，对应数据库 order_template_item 表。
 * <p>记录模板中每个商品的数量。</p>
 */
@Data
public class OrderTemplateItem {

    /** ID，主键自增 */
    private Long id;

    /** 模板ID */
    private Long templateId;

    /** 商品ID */
    private Long productId;

    /** 商品名称(关联查询字段) */
    private String productName;

    /** 规格(关联查询字段) */
    private String spec;

    /** 数量 */
    private Integer quantity;
}
