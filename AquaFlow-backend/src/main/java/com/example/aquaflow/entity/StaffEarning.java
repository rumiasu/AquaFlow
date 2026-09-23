package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 配送员收益明细，对应 {@code staff_earning} 表（v37）。一行 = 一个动作。
 *
 * <p>规格见 {@code docs/design/18}。三条关键约束：</p>
 * <ol>
 *   <li><b>方向由 {@code kind} 决定，调用方一律传正数</b>（唯一的例外是
 *       {@link com.example.aquaflow.constant.EarningKind#ADJUST}）——
 *       落库时由 {@code StaffEarningService} 统一转符号，不允许各调用点自己 negate。</li>
 *   <li><b>{@code stationId} 取履约站</b>（{@code orders.delivery_station_id}）而不是归属站：
 *       工钱是履约成本，跟出车的人走。</li>
 *   <li>自动收益靠 {@code uk_earning_auto}（生成列）幂等；人工调整的 {@code orderId} 为 NULL
 *       且允许无限多条 —— 这个 NULL 是显式设计，不是"忘了 NULL 不冲突"。</li>
 * </ol>
 */
@Data
public class StaffEarning {

    private Long id;

    /** 结算站 = 履约站 */
    private Long stationId;

    /** 收益归属人（实际完成配送的人） */
    private Long staffId;

    /** 关联订单；NULL = 人工调整 */
    private Long orderId;

    /** 见 {@link com.example.aquaflow.constant.EarningKind} */
    private String kind;

    /**
     * 商品ID（v37）：送桶/回桶按商品分行；{@code 0} = 与商品无关（楼层/单奖/扣减/人工调整）。
     *
     * <p>为什么计价粒度是商品：18.9L 与 5L 的搬运成本不同，站长为不同品类配不同单价是常见做法。
     * 它同时是幂等键的一部分（{@code auto_uk}）—— 一单里有多个商品时要各记一行。</p>
     */
    private Long productId;

    /** 数量（桶数 / 层数） */
    private Integer qty;

    /** 单价快照 */
    private BigDecimal unitAmount;

    /** 金额；<b>扣减类为负数</b> */
    private BigDecimal amount;

    /** 已结算时写入所属结算单；NULL = 未结算 */
    private Long payrollId;

    /** 来源资产调整单（人工调整场景的幂等键） */
    private Long adjustmentId;

    /**
     * 自定义工资条目（v44）：NULL = 不是按条目录的（老数据，以及自由文本的人工调整）。
     */
    private Long itemId;

    /**
     * 条目名称快照（v44）：写入时的名字。
     *
     * <p>为什么要快照：条目改名（「迟到扣款」→「迟到罚款」）不该改写**已经发生**的工资历史，
     * 与 {@code order_item.product_name} 同一口径。汇总时优先用条目当前名，
     * 条目已不存在时才回落到这里。</p>
     */
    private String itemName;

    private String note;

    private LocalDateTime createTime;

    /** 前端展示用（后端唯一下发来源，前端禁止自带映射表） */
    public String getKindText() {
        return com.example.aquaflow.constant.EarningKind.textOf(kind);
    }

    /**
     * 明细的展示名：按条目录入的显示条目名，其余回落 {@link #getKindText()}。
     *
     * <p>前端只认这一个字段，不要在页面里写"有 itemName 就用它"的判空 ——
     * 两处各写一次回落逻辑，迟早有一处写错（本仓在支付方式的 1/2/3 映射上踩过）。</p>
     */
    public String getDisplayText() {
        return itemName != null && !itemName.isEmpty() ? itemName : getKindText();
    }

    /** 是否还未结算（前端据此决定是否允许站长手工调整） */
    public boolean isSettled() {
        return payrollId != null;
    }
}
