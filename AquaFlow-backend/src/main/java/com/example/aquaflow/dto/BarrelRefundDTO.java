package com.example.aquaflow.dto;

import lombok.Data;

/**
 * 站长审批退桶申请的请求体（{@code PUT /api/barrels/records/{id}/status}），
 * 同时复用于交付确认（{@code PUT /api/barrels/records/{id}/refund-paid}）的可选入参。
 *
 * <p>字段用途按 status 区分：</p>
 * <ul>
 *   <li>{@code status} / {@code handleNote}：审批本身（2 确认收到空桶 / 3 退押金并交付 / 4 驳回）；</li>
 *   <li>{@code refundChannel}：**仅 status=3 有意义** —— 押金按哪条通道退回（见 {@link #CHANNEL_CASH}）；</li>
 *   <li>{@code refundPaidBy}：**谁把押金交到顾客手上**（{@code staff.id}），
 *       不传 = 点这个按钮的人自己交的。配送员代交时必须传他，否则"核销的人"与"交钱的人"又混回一个。</li>
 * </ul>
 *
 * <p>⚠️ 这里<b>不写</b> Bean Validation 注解：本 DTO 被两个端点复用，
 * 而交付确认端点的请求体里不会有 {@code status}（它只补一个事实，不改状态）。
 * 必填性由 {@code BarrelController} 显式判（status 为空即拒），
 * 与原先 {@code BarrelRecordStatusDTO} 的行为一致。</p>
 */
@Data
public class BarrelRefundDTO {

    /**
     * 现金当面交付：核销与"钱交到顾客手上"在<b>同一次点击</b>里完成（{@code docs/design/35} §7.2）。
     * <p>这是唯一可用的通道，也是<b>未传 refundChannel 时的兜底</b>。</p>
     */
    public static final String CHANNEL_CASH = "CASH";

    /**
     * 线上原路退回（微信）。
     * <p>⚠️ 现阶段<b>必然被拒</b>：微信退款通道未接入，接受它等于"假装已退"
     * （AGENTS §1.1）。保留这个取值是为了把拒绝原因说清楚，而不是留一个能用的选项。</p>
     */
    public static final String CHANNEL_ONLINE = "ONLINE";

    /** 审批动作：2=确认收到空桶 3=退押金并当面交付 4=驳回 */
    private Integer status;

    /** 处理备注（站长驳回原因等） */
    private String handleNote;

    /** 退款通道：{@link #CHANNEL_CASH} / {@link #CHANNEL_ONLINE}；不传 = CASH */
    private String refundChannel;

    /** 把押金交到顾客手上的人（staff.id）；不传 = 操作人自己 */
    private Long refundPaidBy;

    /**
     * 通道取值 <b>白名单</b>（AGENTS §6：请求体的枚举入参必须白名单校验，注解边界会被新调用路径绕过）。
     *
     * <p>返回 {@code null} 表示非法值，调用方必须拒绝 —— <b>不许兜底成某个已知值</b>：
     * 把未知值静默当 CASH，等于让"通道写错了"表现为"钱按现金退了"。</p>
     */
    public static String normalizeChannel(String raw) {
        if (raw == null) {
            // 未传 = 现金当面交付。**不默认 ONLINE**：微信退款通道未接入，默认它等于每笔退押金都失败一次
            //（docs/design/35 §7.3 明确否掉"默认线上"）。
            return CHANNEL_CASH;
        }
        String v = raw.trim();
        if (CHANNEL_CASH.equalsIgnoreCase(v)) return CHANNEL_CASH;
        if (CHANNEL_ONLINE.equalsIgnoreCase(v)) return CHANNEL_ONLINE;
        return null;
    }

    /** 通道中文文案（后端下发，前端禁止自带映射表）。 */
    public static String textOf(String channel) {
        if (CHANNEL_ONLINE.equals(channel)) return "线上原路退回";
        if (CHANNEL_CASH.equals(channel)) return "现金当面交付";
        return "未知方式";
    }
}
