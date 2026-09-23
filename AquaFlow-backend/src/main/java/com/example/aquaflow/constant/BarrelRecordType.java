package com.example.aquaflow.constant;

/**
 * 桶流水类型常量（barrel_record.type）。
 *
 * <p>历史上该列是散落的魔法数字（`setType(2)` / `setType(7)` / `setType(8)`），
 * 取值语义只写在 {@code entity/BarrelRecord.java} 的注释里。本类把它收敛成常量，
 * 新增调用方一律引用常量而不是字面量。</p>
 *
 * <p><b>守恒对账的口径</b>：{@code ReconciliationService} 的 E5「物理桶守恒」
 * 用流水重算占用，再与账面（权益 + over）比对。因此每种类型在该式里的符号是固定的：
 * 增加记正、减少记负。新增类型时必须同步扩展 E5 的 UNION 项，否则补录之后对账会误报。</p>
 */
public class BarrelRecordType {

    /** 1 新增押金桶（形成权益，占用 +） */
    public static final int PURCHASE = 1;

    /** 2 退桶（站长确认后退押金；仅 status=3 时计入守恒） */
    public static final int RETURN = 2;

    /** 3 丢失 */
    public static final int LOST = 3;

    /** 4 损坏 */
    public static final int DAMAGED = 4;

    /** 5 赔偿 */
    public static final int COMPENSATE = 5;

    /**
     * 6 人工调整 — <b>增加</b>方向。
     * <p>站长资产调整单（{@code station_adjustment}）中所有"使占用变大"的桶类调整
     * （补桶权益、补记欠桶）都落这个类型，{@code quantity} 为绝对值，
     * 且必须带 {@code adjustment_id}。</p>
     */
    public static final int ADJUST_INCREASE = 6;

    /** 7 纯还桶（只冲 over，不扣权益、不退款） */
    public static final int RETURN_EMPTY = 7;

    /** 8 配送收发明细（delivered_qty / returned_qty 仅本类型使用） */
    public static final int DELIVERY_DETAIL = 8;

    /**
     * 9 人工调整 — <b>减少</b>方向。
     * <p>[2026-09-13 新增] 与 {@link #ADJUST_INCREASE} 成对：
     * 撤销桶权益、核销欠桶落本类型。拆成两个类型而不是用负数 quantity，
     * 是为了让 E5 的守恒式无需判断符号即可求和。</p>
     */
    public static final int ADJUST_DECREASE = 9;

    /** 存入「变更前 over」的语义说明：仅人工调整类需要，便于事后追查 */
    public static boolean isAdjustment(int type) {
        return type == ADJUST_INCREASE || type == ADJUST_DECREASE;
    }

    public static String textOf(int type) {
        switch (type) {
            case PURCHASE:        return "新增押金桶";
            case RETURN:          return "退桶";
            case LOST:            return "丢失";
            case DAMAGED:         return "损坏";
            case COMPENSATE:      return "赔偿";
            case ADJUST_INCREASE: return "人工调整（增加）";
            case RETURN_EMPTY:    return "纯还桶";
            case DELIVERY_DETAIL: return "配送收发明细";
            case ADJUST_DECREASE: return "人工调整（减少）";
            default:              return "桶变动";
        }
    }

    private BarrelRecordType() {}
}
