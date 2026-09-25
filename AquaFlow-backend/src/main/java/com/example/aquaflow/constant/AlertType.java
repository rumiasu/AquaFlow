package com.example.aquaflow.constant;

/**
 * 告警归属：决定这条告警**该发给谁**（2026-09-16 按产品口径定稿）。
 *
 * <p>产品原话：「异常产生后通知站长做，但是分系统故障通知我、运营故障通知站长，各司其职」。
 * 也就是说告警必须**先分类再投递**，不能一股脑发给同一个人：</p>
 *
 * <ul>
 *   <li>{@link #SYSTEM} <b>系统故障</b> → 收件人是系统管理员（开发者本人）：
 *       对账不平、补偿执行失败、未预期的 500 异常等。这类问题站长既看不懂也修不了，
 *       发给他只会淹没在运营消息里。</li>
 *   <li>{@link #OPERATION} <b>运营故障</b> → 收件人是**该水站的站长**：
 *       桶异常待处置、补偿已执行、协商缺水等。这类问题必须就地由站长处理，
 *       发给开发者属于越级且无意义。</li>
 * </ul>
 *
 * <p>⚠️ 投递方向由本常量决定，**不要靠字符串比较或调用方自觉**（与本仓库
 * {@code DepositType} / {@code BarrelRecordType} 的同一条规矩一致）。</p>
 */
public class AlertType {

    /** 系统故障：收件人 = 系统管理员（{@code station_id} 必须为空） */
    public static final String SYSTEM = "SYSTEM";

    /** 运营故障：收件人 = 该水站站长（{@code station_id} 必填） */
    public static final String OPERATION = "OPERATION";

    /** 收件人中文说明（落库/排查时一眼看出该谁处理） */
    public static String audienceOf(String alertType) {
        if (SYSTEM.equals(alertType)) return "系统管理员";
        if (OPERATION.equals(alertType)) return "水站站长";
        return "未知";
    }

    private AlertType() {}
}
