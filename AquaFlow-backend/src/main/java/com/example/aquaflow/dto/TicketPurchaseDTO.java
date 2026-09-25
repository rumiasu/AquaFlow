package com.example.aquaflow.dto;

import lombok.Data;

/**
 * 客户线上购买水票的请求体（{@code POST /api/tickets/purchase}）。
 *
 * <p>与 {@link TicketConsumeDTO} 分开而不是复用：那个 DTO 服务于站长的
 * {@code /api/tickets/consume}（按客户扣票），既不需要幂等键，也不需要支付方式。
 * 混用一个 DTO 会让「这个字段在哪个端点上有效」变成靠记忆判断的事——本仓已经因为
 * 「请求体从裸 Map 收敛成强类型 DTO 时漏抄字段」出过一次真事故（配送端 collected/note
 * 被 Jackson 静默丢弃，见 AGENTS.md §8 第 15 条）。</p>
 *
 * <p>customerId <b>不在此 DTO 中</b>：一律取自 {@code AuthContext}，禁止信任请求体。</p>
 */
@Data
public class TicketPurchaseDTO {

    /** 要购买的水票对应商品ID */
    private Long productId;

    /** 购买张数 */
    private Integer quantity;

    /** 支付方式：1=微信 2=现金(货到付款) 3=水票，以 constant/PayMethod 为准 */
    private Integer paymentMethod;

    /** 服务水站ID（水票按 (customer, product, station) 三维隔离，必填） */
    private Long stationId;

    /**
     * 水票档位ID（v36，可空）。
     *
     * <p>传了就是"按**定制**档位套餐买"：张数与总价一律以**服务端档位配置**为准，
     * 请求体里的 quantity 必须与档位张数一致，否则拒绝 —— 只信客户端传的套餐价
     * 等于让客户端自己定价。</p>
     *
     * <p>不传且不传 {@link #unifiedQty} = 按散买单张价（{@code inventory.ticket_price}）。</p>
     */
    private Long packageId;

    /**
     * 站级**统一折扣**档的张数（v58，可空）—— 与 {@link #packageId} 互斥（定制优先）。
     *
     * <p>产品口径：「统一水票…执行上也不是统一定价，而是**对应水怎么统一打折、统一打几折**的区别」。
     * 所以这里**只传张数**（客户端用它表示"我选了哪一档"），价格由服务端按
     * <b>该商品自己的水票价 × 该档折扣</b>现算 —— 客户端传不了价，也不该传。</p>
     *
     * <p>只有该商品**没开定制票**（{@code inventory.ticket_enabled != 1}）时才走这条路；
     * 开了定制票却传这个字段会被拒。</p>
     */
    private Integer unifiedQty;

    /**
     * 客户端幂等键，<b>必填</b>。
     *
     * <p>客户端在用户点「购买」时生成一次，<b>失败重试必须复用同一个值</b>；
     * 直到一次购买成功（拿到 paymentId）后才重新生成。服务端据此保证同一笔购买意图
     * 只落一条支付流水 —— 无订单支付没有 order_id，数据库唯一键对它零保护（见 v33 迁移头注释）。</p>
     */
    private String idempotencyKey;
}
