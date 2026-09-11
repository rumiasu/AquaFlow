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

    /**
     * 下单时实际扣减的库存数量。
     * <p>库存不足时只会扣掉现有库存（toDecrease = min(stock, quantity)），因此它可能小于 quantity。
     * 取消/退款回补库存必须以此为准 —— 按 quantity 回补会凭空多出库存，反复下单-取消即可刷库存。</p>
     * 历史数据为 null 时，调用方按 quantity 兜底（与修复前行为一致）。
     */
    private Integer deductedQty;

    /** 押金 */
    private BigDecimal deposit;

    /** 小计 */
    private BigDecimal subtotal;

    /** 创建时间 */
    private LocalDateTime createTime;
}
