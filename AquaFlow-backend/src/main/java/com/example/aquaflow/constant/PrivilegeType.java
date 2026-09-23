package com.example.aquaflow.constant;

import java.util.List;

/**
 * 客户特权类型（v40）。规格见 {@code docs/design/20} §4。
 *
 * <p>产品决定（{@code docs/design/16} §D6）：不做个人/企业客户的显式区分，
 * 差异化一律落到「站长在客户画像里给特权」。已有先例是
 * {@code customer_station_config.offline_payment_enabled}（站长逐个客户开通货到付款）。</p>
 *
 * <p><b>为什么是有限枚举而不是一个 JSON 字段</b>：任意维度的"特权"会退化成没有校验、
 * 没有展示口径、没有对账的裸状态。语义由类型决定 —— 与 {@code DepositType} /
 * {@code AdjustType} / {@code EarningKind} 同一套设计语言。</p>
 *
 * <p>⚠️ <b>{@link #isMoneyAffecting} 与 {@link #isImplemented} 是两个必须分开的概念</b>：</p>
 * <ul>
 *   <li><b>动钱</b>的类型（折扣率、免配送次数、允许退票）本质是**客户资产**，
 *       必须做成"账户 + 流水"（与水票/押金同类），不能是画像上的一个开关 ——
 *       否则会出现"站长说还有 3 次、客户说还有 5 次"的扯皮；</li>
 *   <li><b>已实现</b>的类型才允许被授予。本版只实现了 {@link #NO_MIN_ORDER}。
 *       对未实现的类型，接口层直接**拒绝授予**而不是收下 —— 收下就是一个"配了也不生效"
 *       的悬空配置，而站长会以为已经生效了。这是本仓"悬空字段/空壳功能"教训的直接应用。</li>
 * </ul>
 */
public final class PrivilegeType {

    /**
     * 免起送门槛：该客户在本站下单不受起送量限制（<b>不动钱</b>，已实现）。
     *
     * <p>现实场景：老客户就买 1 桶，站长愿意送 —— 这正是"起送量默认 WARN 而不是 REJECT"
     * 想解决的问题的**逐客户版**。</p>
     */
    public static final String NO_MIN_ORDER = "NO_MIN_ORDER";

    // ==================== 以下为「动钱」类型，本版**未实现**，授予会被拒绝 ====================

    /**
     * 折扣率（动钱，<b>未实现</b>）。
     * <p>要做必须：走 {@code PriceUtil} 口径改计价 + 在下单时把折扣**快照进订单**
     * （站长改折扣不能改到历史订单的金额，与押金/售价快照同一条原则）。</p>
     */
    public static final String DISCOUNT_RATE = "DISCOUNT_RATE";

    /**
     * 免配送次数（动钱，<b>未实现</b>）。
     * <p>是一种**次数账户**，需要 账户 + 流水 + 幂等 + 有效期 + 对账，见
     * {@code docs/design/20} §4.3。做成开关必然扯皮。</p>
     */
    public static final String FREE_DELIVERY_TIMES = "FREE_DELIVERY_TIMES";

    /**
     * 允许退票（动钱，<b>未实现</b>）。
     * <p>现实中大量水站"票不退不换"，所以需要一个开关；但它直接决定钱能不能退出去，
     * 属于"动钱"类，必须与水票批次单价（{@code ticket_lot.unit_price}）一起考虑。</p>
     */
    public static final String TICKET_RETURNABLE = "TICKET_RETURNABLE";

    /** 本版**已实现**、允许被授予的类型。不在这个列表里的一律拒绝。 */
    private static final List<String> IMPLEMENTED = List.of(NO_MIN_ORDER);

    /**
     * 已知的全部类型（含未实现的）。用于校验"这是个合法的类型名"，
     * 与"能不能授予"是两件事。
     */
    private static final List<String> KNOWN =
            List.of(NO_MIN_ORDER, DISCOUNT_RATE, FREE_DELIVERY_TIMES, TICKET_RETURNABLE);

    /** 是否为本系统已知的类型名（拼错的类型名应被明确拒绝，而不是静默存下） */
    public static boolean isKnown(String type) {
        return type != null && KNOWN.contains(type);
    }

    /**
     * 本版是否已实现（= 允许授予）。
     *
     * <p>⚠️ 授予未实现的类型必须**报错**：收下就等于给站长一个"看着开了、其实没用"的开关。</p>
     */
    public static boolean isImplemented(String type) {
        return type != null && IMPLEMENTED.contains(type);
    }

    /**
     * 是否影响钱。
     *
     * <p>为真的类型**不允许**出现在 {@code customer_privilege} 表里 ——
     * 它们需要账户 + 流水，落在本表就表达不了语义。</p>
     */
    public static boolean isMoneyAffecting(String type) {
        return DISCOUNT_RATE.equals(type) || FREE_DELIVERY_TIMES.equals(type)
                || TICKET_RETURNABLE.equals(type);
    }

    /** 文案（后端唯一下发来源，前端禁止自带映射表） */
    public static String textOf(String type) {
        if (type == null) return "未知特权";
        switch (type) {
            case NO_MIN_ORDER:         return "免起送门槛";
            case DISCOUNT_RATE:        return "折扣率（未开放）";
            case FREE_DELIVERY_TIMES:  return "免配送次数（未开放）";
            case TICKET_RETURNABLE:    return "允许退票（未开放）";
            default:                   return "未知特权";
        }
    }

    /** 给站长看的拒绝原因 */
    public static String unimplementedReason(String type) {
        if (isMoneyAffecting(type)) {
            return textOf(type) + "属于「影响金额」的特权，需要做成账户+流水（与水票/押金同类），"
                    + "本版尚未实现，暂不能授予";
        }
        return textOf(type) + "本版尚未实现，暂不能授予";
    }

    private PrivilegeType() {}
}
