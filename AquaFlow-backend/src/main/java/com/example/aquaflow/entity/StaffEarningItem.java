package com.example.aquaflow.entity;

import com.example.aquaflow.constant.EarningItemDirection;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站长自定义工资条目（v44），对应 {@code staff_earning_item} 表。
 *
 * <p><b>它是什么：</b>站点自己定义的「加项 / 扣项」字典（迟到扣款、破损赔偿、高温补贴……）。
 * 录工资时选条目、只填金额，<b>方向由条目决定</b>。为什么不让录的人自己填正负号，
 * 见 {@link EarningItemDirection} 的类注释。</p>
 *
 * <p><b>它不是什么：</b>不是计价规则、不参与任何自动收益计算，也不进对账等式 ——
 * 条目只是人工调整那条流水上的一个**标签**（金额照旧走 E-PAY）。</p>
 *
 * <p>⚠️ 停用（{@code status=0}）只挡**新录入**：历史流水照旧显示自己的
 * {@code staff_earning.item_name} 快照，不受改名与停用影响。</p>
 */
@Data
public class StaffEarningItem {

    private Long id;

    private Long stationId;

    /** 条目名称（同站唯一，最长 20 字） */
    private String name;

    /** 见 {@link EarningItemDirection}：1 加项 / 2 扣项 */
    private Integer direction;

    /** 1 启用 / 0 停用 */
    private Integer status;

    /** 展示顺序（小的在前） */
    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 前端展示用（后端唯一下发来源，前端禁止自带映射表） */
    public String getDirectionText() {
        return EarningItemDirection.textOf(direction);
    }

    /** 是否启用（前端据此决定能否录这一笔） */
    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
