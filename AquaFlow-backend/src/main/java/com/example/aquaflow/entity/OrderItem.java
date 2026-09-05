package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单商品明细实体类，对应数据库 order_item 表。
 * <p>记录订单中每个商品的详细信息，支持一单多商品。</p>
 */
@Data
public class OrderItem {

    /** 明细ID，主键自增 */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 商品ID */
    private Long productId;

    /** 商品名称快照(历史订单不变) */
    private String productNameSnapshot;

    /** 品牌快照 */
    private String brandSnapshot;

    /** 规格快照 */
    private String specSnapshot;

    /** 单价 */
    private BigDecimal price;

    /** 数量 */
    private Integer quantity;

    /** 押金 */
    private BigDecimal deposit;

    /** 小计 */
    private BigDecimal subtotal;

    /** 创建时间 */
    private LocalDateTime createTime;
}
