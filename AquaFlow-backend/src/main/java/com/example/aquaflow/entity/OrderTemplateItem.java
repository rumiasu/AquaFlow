package com.example.aquaflow.entity;

import lombok.Data;

/**
 * 常用订单模板明细实体类，对应数据库 order_template_item 表。
 * <p>记录模板中每个水类型的数量。</p>
 */
@Data
public class OrderTemplateItem {

    /** ID，主键自增 */
    private Integer id;

    /** 模板ID，关联 order_template 表 */
    private Integer templateId;

    /** 水类型ID，关联 water_type 表 */
    private Integer waterTypeId;

    /** 水类型名称（关联查询字段） */
    private String waterTypeName;

    /** 水类型规格（关联查询字段） */
    private String waterTypeSpec;

    /** 水类型单价（关联查询字段） */
    private java.math.BigDecimal waterTypePrice;

    /** 数量（桶数） */
    private Integer quantity;
}
