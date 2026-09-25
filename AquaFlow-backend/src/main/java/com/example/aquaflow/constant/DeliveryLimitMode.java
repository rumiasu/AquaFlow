package com.example.aquaflow.constant;

/**
 * 经营门槛的三种处理方式（起送量、配送范围共用），2026-09-17 新增（v35）。
 *
 * <p>为什么不是一个 boolean「是否硬拦」：现实中站长对这两种门槛的处理是**三选一**——
 * 老客户只买 1 桶也得送（{@link #WARN}）、写明了 2 桶起送就真的不接（{@link #REJECT}）、
 * 或者"远一点加 5 块"（{@link #FEE}）。压成 boolean 就只剩"拦/不拦"，
 * 而"加钱放行"恰恰是最常用的那一种。</p>
 *
 * <p><b>默认必须是 {@link #WARN}</b>：硬拦一个都没配过的门槛，等于站长一建站就把客户挡在门外。
 * 本仓已有先例 —— 欠桶的硬拦（原 {@code MAX_OWED_BUCKETS = 5}）就是按产品决定移除、
 * 改成"只提醒不阻断"的（见 AGENTS.md §1）。</p>
 */
public final class DeliveryLimitMode {

    /** 只提示，放行（默认） */
    public static final String WARN = "WARN";
    /** 拒绝下单，返回可读的业务错误 */
    public static final String REJECT = "REJECT";
    /** 加收费用后放行 */
    public static final String FEE = "FEE";

    /**
     * 归一化：未知/空值一律回落到 {@link #WARN}。
     *
     * <p>⚠️ 不要在这个兜底里返回 {@code REJECT} —— 配置写错或历史脏数据不应该表现为"客户下不了单"。</p>
     */
    public static String normalize(String mode) {
        if (REJECT.equals(mode)) return REJECT;
        if (FEE.equals(mode)) return FEE;
        return WARN;
    }

    /** 文案（后端唯一下发来源，前端禁止自带映射表） */
    public static String textOf(String mode) {
        switch (normalize(mode)) {
            case REJECT: return "不接单";
            case FEE:    return "加收费用";
            default:     return "仅提示";
        }
    }

    private DeliveryLimitMode() {}
}
