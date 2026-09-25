package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存预留凭据（`inventory_reservation`）—— 「这份货是为哪张单、留在哪个站」的唯一真相源。
 *
 * <p>背景（2026-09-25 架构评审问题 4）：库存原来是「下单扣、取消补」，而"扣在哪一站"根本没被记下来，
 * 于是跨站外派后取消会把货补到**履约站**（扣的却是归属站）→ A 站凭空少、B 站凭空多，对账抓不到；
 * 缺货下单只扣了 min(库存, 下单量)，剩下的部分**永远不落账**。</p>
 *
 * <p>现在拆成两个量：<b>预留</b>（下单时占可用量、不动实物）与<b>出库</b>（完成配送时动实物）。
 * 本表就是这条链的凭据，规格见 {@code docs/design/28-库存预留与履约凭据.md}。</p>
 *
 * <p>⚠️ 唯一键是建在生成列上的 `uk_reservation_active_item`（status=1 时才取 order_item_id）——
 * 语义是「<b>一条订单明细至多一份活跃凭据</b>」，历史行（已出库 / 已释放）可以有任意多条：
 * 换站就是"旧站那条置为已释放 + 新站插一条新的"，两条都要留着当审计轨迹。
 * 这与 `uk_payment_active_order` / `uk_earning_auto` 是同一形状。</p>
 */
@Data
public class InventoryReservation {

    private Long id;

    private Long orderId;

    /** 订单明细 id（出库量与需求量的比对都按它算） */
    private Long orderItemId;

    private Long productId;

    /** ★ 这份凭据当前挂在哪个站：换站时改的是它，不是订单的归属站 */
    private Long stationId;

    /** 已预留在库量（≤ 订单量；差额 = 缺货待补，由入库按 FIFO 补齐） */
    private Integer reservedQty;

    /** 已出库量（完成配送时一次性写满） */
    private Integer shippedQty;

    /** 已释放量（取消 / 换站时旧凭据作废的量） */
    private Integer releasedQty;

    /** 见 {@link com.example.aquaflow.constant.ReservationStatus}：1 预留中 / 2 已出库 / 3 已释放 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
