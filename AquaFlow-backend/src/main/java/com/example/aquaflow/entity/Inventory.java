package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水站商品库存实体类，对应数据库 inventory 表。
 * <p>某水站有没有这个商品、库存多少、是否在商城销售。</p>
 */
@Data
public class Inventory {

    /** 库存记录ID，主键自增 */
    private Long id;

    /** 水站ID */
    private Long stationId;

    /** 商品ID */
    private Long productId;

    /** 商品名称(关联查询字段) */
    private String productName;

    /** 规格(关联查询字段) */
    private String spec;

    /** 库存数量 */
    private Integer quantity;

    /** 是否在商城销售: 0 不在商城销售 1 在商城销售 */
    private Integer enabled;

    /** 是否支持水票: 0 不支持 1 支持 */
    private Integer ticketEnabled;

    /** 水票价格 */
    private BigDecimal ticketPrice;

    /** 优先展示: 0 否 1 是 */
    private Integer priorityDisplay;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 所属水站名称(关联查询字段) */
    private String stationName;
}