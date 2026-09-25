package com.example.aquaflow.constant;

/**
 * 自定义工资条目的方向（v44）。正本在这里，前端禁止自带 1/2 映射表。
 *
 * <p><b>方向由条目决定，调用方一律传正数金额</b> —— 与 {@link EarningKind} 同一条口径：
 * 让录钱的人自己决定符号，等于把"这次是补还是扣"变成每次都要重新判断的事，
 * 而它恰恰是最容易选错、且错了直接从人家工资里体现的东西。</p>
 */
public final class EarningItemDirection {

    /** 加项：给配送员补钱 */
    public static final int ADD = 1;
    /** 扣项：从配送员工资里扣钱 */
    public static final int DEDUCT = 2;

    /** 是否是合法的方向值 */
    public static boolean isValid(Integer direction) {
        return direction != null && (direction == ADD || direction == DEDUCT);
    }

    /** 方向对应的金额符号（+1 / −1）；未知方向按加项处理（不静默扣钱） */
    public static int signOf(Integer direction) {
        return direction != null && direction == DEDUCT ? -1 : 1;
    }

    /** 文案（后端唯一下发来源） */
    public static String textOf(Integer direction) {
        if (direction == null) return "未知";
        return direction == DEDUCT ? "扣项" : "加项";
    }

    private EarningItemDirection() {}
}
