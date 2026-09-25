package com.example.aquaflow.constant;

import java.util.List;

/**
 * 配送员收益类型（v37）。规格见 {@code docs/design/18}。
 *
 * <p><b>方向由类型决定，调用方一律传正数</b> —— 与 {@code DepositType.isIncrease/isDecrease}
 * 同一口径。唯一的例外是 {@link #ADJUST}：它由站长在结算单上手工录入，本来就是正负都可的两向调整，
 * 所以它的金额<b>由调用方给出符号</b>。除此之外任何 kind 都不允许调用方传负数，
 * 否则就会出现"两处各写一次 negate、其中一处写反"的经典事故（本仓在押金上已经踩过）。</p>
 *
 * <p>⚠️ <b>2026-09-18（v42）起只自动产生两类</b>：{@link #DELIVERY_BUCKET} 与 {@link #FLOOR_BONUS}
 * （外加站长手工的 {@link #ADJUST}）。{@link #RETURN_BUCKET} / {@link #ORDER_BONUS} /
 * {@link #PENALTY} 三个类型<b>保留但已停用</b>：不再有任何代码产生它们，
 * 常量与 {@link #textOf} 留下来只为**显示历史流水**（删掉会让老账的收益明细显示成"其他"）。</p>
 */
public final class EarningKind {

    /** 送水计件：送桶数 × 每桶单价 */
    public static final String DELIVERY_BUCKET = "DELIVERY_BUCKET";
    /** 回收空桶奖励：回桶数 × 每桶奖励。<b>已停用（v42）</b>：不再产生，仅用于显示历史流水 */
    public static final String RETURN_BUCKET = "RETURN_BUCKET";
    /** 楼层补贴：无电梯时 (楼层 − 免费层) × 每层补贴 */
    public static final String FLOOR_BONUS = "FLOOR_BONUS";
    /** 每单基础奖励。<b>已停用（v42）</b>：不再产生，仅用于显示历史流水 */
    public static final String ORDER_BONUS = "ORDER_BONUS";
    /** 扣减：少收空桶等（<b>落库为负数</b>）。<b>已停用（v42）</b>：不再产生，仅用于显示历史流水 */
    public static final String PENALTY = "PENALTY";
    /** 人工调整：**唯一允许调用方给符号的 kind**（站长在结算单上手工加减） */
    public static final String ADJUST = "ADJUST";

    /** 完成配送时自动产生的收益类型（也是 {@code auto_uk} 去重键覆盖的集合） */
    public static final List<String> AUTO_KINDS =
            List.of(DELIVERY_BUCKET, RETURN_BUCKET, FLOOR_BONUS, ORDER_BONUS, PENALTY);

    /** 该类型的方向是否为"减少"（落库为负数） */
    public static boolean isDecrease(String kind) {
        return PENALTY.equals(kind);
    }

    /**
     * 该类型是否允许调用方自带符号。
     * <p>只有 {@link #ADJUST} 允许；其余一律"调用方传正数、落库时由本类决定符号"。</p>
     */
    public static boolean allowsSignedAmount(String kind) {
        return ADJUST.equals(kind);
    }

    /** 文案（后端唯一下发来源，前端禁止自带映射表） */
    public static String textOf(String kind) {
        if (kind == null) return "其他";
        switch (kind) {
            case DELIVERY_BUCKET: return "送水计件";
            case RETURN_BUCKET:   return "回桶奖励";
            case FLOOR_BONUS:     return "楼层补贴";
            case ORDER_BONUS:     return "单量奖励";
            case PENALTY:         return "扣减";
            case ADJUST:          return "人工调整";
            default:              return "其他";
        }
    }

    private EarningKind() {}
}
