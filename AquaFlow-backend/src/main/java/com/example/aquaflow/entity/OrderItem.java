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

    /** 列表只读投影：当前商品分类；缺失时使用中性单位，不按名称或规格猜桶数。 */
    private Integer category;

    /** 列表只读投影：当前商品图键，统一交给 ProductImageResolver 解析。 */
    private String imageObjectName;

    /** 列表只读投影：可显示图片地址；签名失败为 null。 */
    private String imageUrl;

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

    // =========================================================================
    // 以下两个字段**不是数据库列**，只在「完成配送页」下发时填充
    // （写入点：DeliveryTaskController#getOrderDetail，口径来自 BarrelService#returnPlanOfOrder）。
    // order_item 的 insert 是显式列名清单，故加字段不会影响落库。
    // =========================================================================

    /**
     * 该明细是不是**桶装水**（判据 {@code util/BarrelScope}，唯一实现）。
     *
     * <p>为什么完成页必须知道：回桶只对桶装水成立 —— 瓶装水 / 饮水机既没有押金条也没有还桶入口，
     * 报回桶会被桶账的物理上限拒掉（{@code returned > 占用 = 0}），而那句报错配送员看不懂。
     * 完成页据此只对桶装水画回桶步进器。</p>
     */
    private Boolean barrelItem;

    /**
     * 完成配送页的**默认回桶数**（桶装水明细才有值，其余为 {@code null}）。
     *
     * <p>= 客户手上已有的旧桶数，上限「占用 = 权益 + over」，且不超过本明细送出桶数。
     * <b>本合同批新买押金的桶不算</b>（那些桶还在 {@code customer_barrel_in_transit} 的 PENDING 里，
     * 本来就进不了权益/占用）：买桶是买桶、换水是换水，只对"旧桶换新水"那部分默认回收。</p>
     *
     * <p>⚠️ 这是**默认值不是校验**：配送员可以改；服务端唯一的硬判据仍是
     * {@code BarrelLedgerService.applyDelivery} 的 {@code returned ≤ 占用}。</p>
     */
    private Integer suggestedReturnQty;
}
