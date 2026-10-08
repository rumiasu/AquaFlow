package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Feedback {
    private Long id;
    private Long staffId;
    private Long customerId;
    private String category;
    private String content;
    private String contact;

    /**
     * 是否匿名提交（v46 起；{@code 0 实名 / 1 匿名}，默认实名）。
     *
     * <p>⚠️ <b>不要把本字段当成"脱敏开关"用</b>：匿名是「<b>站长不知道是谁</b>」，
     * 不是「前端不显示」。真正的脱敏钉在 {@code FeedbackMapper.listCustomerFeedbackByStation}
     * 的查询 SQL 上（{@code CASE WHEN ... THEN NULL}），本站长列表里
     * {@code customerId} / {@code customerName} 直接就是 null，前端无从显示。</p>
     *
     * <p>谁该看到它：<b>只有客户自己</b>（{@code GET /api/feedback/my}，走 {@code select *}）。
     * 站长端列表<b>刻意不 select 这一列</b>（见该 mapper 的注释）。</p>
     */
    private Boolean anonymous;

    private String customerName;
    private LocalDateTime createTime;
    private String refundType;
    private Long refundId;
    private Long responsibleStationId;
    @com.fasterxml.jackson.annotation.JsonIgnore private String actorKey;
    @com.fasterxml.jackson.annotation.JsonIgnore private String idempotencyKey;
    @com.fasterxml.jackson.annotation.JsonIgnore private String requestDigest;

    public String getRefundObjectText() {
        if (refundType == null) return null;
        return switch (refundType) {
            case "BARREL_RETURN" -> "退押金申请 #"+refundId;
            case "ORDER_PAYMENT" -> "订单退款原款 #"+refundId;
            case "TICKET_PAYMENT" -> "水票退款原款 #"+refundId;
            default -> null;
        };
    }
    public String getAuthorText() {return refundType == null ? null : staffId == null ? "客户说明" : "水站补充说明";}
}
