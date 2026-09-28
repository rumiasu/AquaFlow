package com.example.aquaflow.constant;

/**
 * 站间结算台账的状态（{@code inter_station_settlement.status}）—— **唯一正本**。
 *
 * <p>三态刻意收得很紧：这张表只回答「**什么时候算办完**」，
 * 而"欠多少"是**实时算**出来的（见 {@code InterStationSettlementService} 的口径说明），
 * 所以不存在"待确认 / 部分结清"这类中间态 —— 有了就会与实时算的结果分叉。</p>
 *
 * <ul>
 *   <li>{@link #PENDING} —— 还欠着。**只有"站长改过价"才会有这一态的行**
 *       （改价要先落一行快照，否则下次实时算就把挂牌价又算回实付了）；
 *       没改过价的单在库里**根本没有行**，由实时算得出，同样属于 {@code PENDING}。</li>
 *   <li>{@link #SETTLED} —— 付款方已登记付清，行里有 {@code settled_time} / {@code settled_by}
 *       / {@code settle_note}（转账流水号或经手人）。⚠️ **系统不假装打款**：
 *       钱是线下走的，这里只留痕（同"配送员工资只记 paid_time"的既有先例）。</li>
 *   <li>{@link #REVERSED} —— 已冲销（订单取消/退款后那笔应付不再成立）。
 *       ⚠️ **不删行**（唯一键建在 {@code order_id} 上，一单一笔），改状态才留得下轨迹。</li>
 * </ul>
 */
public class SettleStatus {

    /** 1 待结清（还欠着）。 */
    public static final int PENDING = 1;

    /** 2 已结清（付款方已登记付清）。 */
    public static final int SETTLED = 2;

    /** 3 已冲销（订单取消/退款）。 */
    public static final int REVERSED = 3;

    /** 展示文案（**后端下发的唯一来源**，前端禁止自带映射表，见 AGENTS §6）。 */
    public static String textOf(Integer status) {
        if (status == null) {
            return "未指定";
        }
        switch (status) {
            case PENDING:   return "待结清";
            case SETTLED:   return "已结清";
            case REVERSED:  return "已冲销";
            // 兜底不许把未知值说成某个已知值（同 PayMethod.textOf 的历史事故）。
            default:        return "未知状态";
        }
    }

    private SettleStatus() {}
}
