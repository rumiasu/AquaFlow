package com.example.aquaflow.constant;

/**
 * 水站<b>营业状态</b>（软状态）—— 2026-09-17 新增，配合站长留言一起展示给顾客。
 *
 * <p><b>与 {@code station.status}（硬状态）的区别，别混：</b></p>
 * <ul>
 *   <li>{@code station.status}：1 营业 / 2 停业。**停业会真的拒绝下单**
 *       （{@code OrderServiceImpl} 校验 + {@code /api/stations/public} 只列 status=1），
 *       是"这家站没了/关张"的开关。</li>
 *   <li>本枚举：**软状态，一律不阻断下单** —— 顾客照常下单，只是会看到提示
 *       （商城/下单页横幅 + 下单响应 {@code warnings}）。站长用来表达"现在什么情况"。</li>
 * </ul>
 *
 * <p>产品口径（2026-09-17 与站长确认）：「正常运营、休息等」，**不做强制拦截**，
 * 所以这里不做任何下单校验，只负责文案与提示。文案由后端下发，前端禁止自带映射表。</p>
 */
public final class StationOperatingStatus {

    /** 正常运营（默认） */
    public static final int NORMAL = 1;

    /** 休息中（打烊/午休等，稍后恢复；留言里写恢复时间） */
    public static final int RESTING = 2;

    /** 配送延迟（照常接单，但送达会比平时晚：爆单/天气/人手不足） */
    public static final int DELAYED = 3;

    /** 暂停接单、可预约（今天不送了，订单统一明天处理） */
    public static final int APPOINTMENT_ONLY = 4;

    private StationOperatingStatus() {}

    public static boolean isValid(Integer status) {
        return status != null && status >= NORMAL && status <= APPOINTMENT_ONLY;
    }

    /** 状态中文文案（全系统唯一来源：后端下发，前端不要自己写 1..4 映射表） */
    public static String textOf(Integer status) {
        if (status == null) return "正常运营";
        switch (status) {
            case NORMAL: return "正常运营";
            case RESTING: return "休息中";
            case DELAYED: return "配送延迟";
            case APPOINTMENT_ONLY: return "暂停配送，可预约";
            default: return "正常运营";
        }
    }

    /**
     * 给顾客看的一句话提示（下单响应 warnings 与页面横幅共用）。
     * <p>正常运营返回 {@code null} —— 调用方据此决定"不提示"。</p>
     */
    public static String customerHint(Integer status, String note) {
        if (status == null || status == NORMAL) return null;
        String base = "水站当前：" + textOf(status);
        if (note != null && !note.trim().isEmpty()) {
            base += " · " + note.trim();
        }
        // 明确的"不阻断"口径：让客户知道还能下单，避免误以为下不了单
        return base + "（仍可下单，站长会按上面的说明安排配送）";
    }
}
