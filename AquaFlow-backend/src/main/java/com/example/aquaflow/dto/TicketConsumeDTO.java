package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 站长手工扣票的请求体（{@code POST /api/tickets/consume}）。
 *
 * <p><b>这是「无订单扣票」</b>：{@link #orderId} 可空，站长手工扣票时它就是 NULL。
 * 于是数据库唯一键 {@code uk_ticket_consume(order_id, product_id, source)} 对这条路径
 * <b>零保护</b>（MySQL 唯一键中 NULL 互不冲突）—— 连点两次会把余额扣两次。
 * 因此自 v70 起 {@link #idempotencyKey} <b>必传</b>，兜底唯一键是
 * {@code uk_ticket_consume_idem(customer_id, idempotency_key)}。
 * 形状与判据照抄 v33 的 {@link TicketPurchaseDTO}（在线购票同形的坑）。</p>
 *
 * <p>与 {@link TicketPurchaseDTO} 分开而不是复用：那个服务于顾客自助购票，
 * customerId 取自登录态、且需要档位与支付方式；本 DTO 的 customerId 由站长指定
 * （扣谁的票）。混用一个 DTO 会让「这个字段在哪个端点上有效」变成靠记忆判断的事
 * （本仓因「请求体收敛成强类型 DTO 时漏抄字段」出过真事故，见 AGENTS.md §8 第 15 条）。</p>
 */
@Data
public class TicketConsumeDTO {

    /** 被扣票的客户ID（由站长指定，必须属于本站） */
    private Long customerId;

    /** 商品ID（水票账户恒为「该商品」，不存在从别的账户扣） */
    private Long productId;

    /** 扣减张数 */
    private Integer quantity;

    /**
     * 关联订单ID，<b>可空</b>。
     *
     * <p>传了 = 订单内扣票（幂等由 {@code uk_ticket_consume(order_id, product_id, source)} 承担，
     * 此时 {@link #idempotencyKey} 不参与）；
     * 不传 = <b>站长手工扣票</b> —— 这正是本 DTO 需要幂等键的那种调用。</p>
     */
    private Long orderId;

    /**
     * 客户端幂等键，<b>必填</b>（v70）。
     *
     * <p>调用方在站长点「扣票」时生成一次，<b>失败重试必须复用同一个值</b>，
     * 直到一次扣票成功后才重新生成。服务端据此保证同一笔扣票意图只扣一次。</p>
     *
     * <p>⚠️ 它与 {@code orderId} 无关，是两条并存的防线：<b>不是</b>"有订单就不用传"。
     * 缺它时手工扣票在数据库层没有任何兜底（见类注释）。</p>
     */
    @NotBlank(message = "缺少幂等键 idempotencyKey")
    private String idempotencyKey;
}
