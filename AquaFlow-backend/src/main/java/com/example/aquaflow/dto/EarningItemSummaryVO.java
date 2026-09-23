package com.example.aquaflow.dto;

import com.example.aquaflow.constant.EarningItemDirection;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 「按条目汇总」（v44）：某期间 / 某张结算单里，每个自定义条目一共加/扣了多少钱。
 *
 * <p>存在的理由就是站长月底最常问的那句话：「这个月迟到一共扣了多少」——
 * 没有它，条目只解决了"录入时的措辞统一"，汇总还得一页页数。</p>
 *
 * <p>口径：只统计 {@code item_id} 非空的流水（自由文本的人工调整不参与汇总，
 * 它根本没有条目）。{@code total} 是**带符号**的合计（扣项为负），
 * 免得前端自己再判方向。</p>
 */
@Data
public class EarningItemSummaryVO {

    private Long itemId;

    /** 条目当前名；条目已不存在时回落写入时的快照名 */
    private String name;

    /** 见 {@link EarningItemDirection}（条目已不存在时为 null） */
    private Integer direction;

    /** 带符号合计：加项为正、扣项为负 */
    private BigDecimal total;

    public String getDirectionText() {
        return EarningItemDirection.textOf(direction);
    }
}
