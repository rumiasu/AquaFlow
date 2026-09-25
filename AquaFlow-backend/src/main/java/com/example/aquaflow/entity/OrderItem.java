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
     * `order_item.deducted_qty` —— **当前活跃预留凭据的预留量镜像**（2026-09-25 库存预留模型收口）。
     *
     * <p>唯一语义（返工 R5）：等于 `inventory_reservation` 里该明细那条**活跃**凭据
     * （`status = 1`）的 `reserved_qty`；没有活跃凭据（已出库 / 已释放 / 从未建）时为 **0**。
     * 写入点只有一处：{@code InventoryReservationServiceImpl.syncDeductedQty}
     * （下单预留、补位、换站重建、释放、出库都会调它）——结算/对账读它即等价于读凭据。</p>
     *
     * <p>⚠️ 旧口径（**已废，别再按它写代码**）：它曾表示"下单那一刻实际扣减的实物量"，
     * 于是"取消/退款按它 increaseStock 回补库存"——那套逻辑随 v63 一起删除：
     * 取消只释放预留，实物从来没减过（见 {@code docs/design/28-库存预留与履约凭据.md}）。</p>
     */
    private Integer deductedQty;

    /** 押金 */
    private BigDecimal deposit;

    /** 小计 */
    private BigDecimal subtotal;

    /** 创建时间 */
    private LocalDateTime createTime;
}
