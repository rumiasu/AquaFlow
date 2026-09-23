package com.example.aquaflow.constant;

/**
 * 楼层费的计费口径，2026-09-17 新增（v35）。
 *
 * <p>两种都是真实存在的做法：</p>
 * <ul>
 *   <li>{@link #PER_ORDER} 每单加一次 —— 默认。客户一次买 3 桶，不会因为"多买了"被多收上楼费，
 *       结算页的数字更好接受；</li>
 *   <li>{@link #PER_BUCKET} 每桶加一次 —— "无电梯 2 元/桶"是更常见的口头报价，
 *       但 3 桶 × 2 元 = 6 元会让一部分客户在结算页放弃下单。</li>
 * </ul>
 *
 * <p>这是产品取舍不是技术限制，所以做成站长可配，默认取摩擦更小的那个。</p>
 */
public final class FloorFeeMode {

    /** 每单加收一次（默认） */
    public static final String PER_ORDER = "PER_ORDER";
    /** 每桶加收一次 */
    public static final String PER_BUCKET = "PER_BUCKET";

    /** 归一化：未知/空值回落到 {@link #PER_ORDER}（收费更少的那个，避免配置错误多收客户钱） */
    public static String normalize(String mode) {
        return PER_BUCKET.equals(mode) ? PER_BUCKET : PER_ORDER;
    }

    public static String textOf(String mode) {
        return PER_BUCKET.equals(normalize(mode)) ? "按桶" : "按单";
    }

    private FloorFeeMode() {}
}
