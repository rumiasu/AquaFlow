package com.example.aquaflow.constant;

/**
 * 结算周期 —— 决定「账期从哪一天起算」（**枚举值正本**）。
 *
 * <p>站级配置落在 {@code customer_station_config}：
 * {@code settlement_cycle}（本类）+ {@code due_days}（天数）。两者合起来决定
 * {@code orders.due_date}（下单时快照一次，之后只读）：</p>
 * <pre>
 *   IMMEDIATE（现结）→ 不写 due_date（NULL = 即时结清，与 2026-09-21 升级前的行为一致）
 *   MONTHLY  （月结）→ due_date = 当月最后一天 + due_days
 * </pre>
 *
 * <p><b>为什么"月结"从月底起算、而不是从下单日 +N 天</b>：企业主流是"本月消费、下月结账"
 * —— 9 月 5 日送的水和 9 月 28 日送的水走的是同一次付款。从下单日起算会让月初的单
 * 比月末的单早到期，与企业的实际付款节奏对不上。</p>
 *
 * <p>⚠️ <b>只做这两种是有意收窄的</b>（2026-09-21 产品裁定：「账期先依赖平台默认吧，
 * 按照企业主流流程来」）。季结 / 双月结 / 每月固定某日付款等形态，等真有客户提再加 ——
 * 每多一个选项，站长就多一份误判风险，而站长主要是力工、不该被系统变成负担。</p>
 */
public final class SettlementCycle {

    /** 现结：不产生应付日期（{@code due_date = NULL}，即时结清） */
    public static final String IMMEDIATE = "IMMEDIATE";

    /** 月结：从**当月最后一天**起算，再加 {@code due_days} 天 */
    public static final String MONTHLY = "MONTHLY";

    /**
     * 平台默认结算周期 = 月结。
     *
     * <p>用途：站长点「开启企业账户」时**一键套用**（见 {@code EnterpriseIdentityService.review}），
     * 不需要他理解什么是结算周期。</p>
     */
    public static final String PLATFORM_DEFAULT = MONTHLY;

    /**
     * 平台默认账期天数（与 {@link #PLATFORM_DEFAULT} 成对使用）。
     *
     * <p>30 天 = 月底起算 + 30 天 ≈「次月底前付」，是企业桶装水采购最常见的账期。</p>
     */
    public static final int PLATFORM_DEFAULT_DUE_DAYS = 30;

    /** 中文文案（全系统唯一来源，前端禁止自带映射表）。 */
    public static String textOf(String cycle) {
        return MONTHLY.equals(cycle) ? "月结" : "现结";
    }

    private SettlementCycle() {
    }
}
