package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水站「统一折扣」档位，对应 {@code station_ticket_discount} 表（v58，2026-09-20）。
 *
 * <p><b>它只存折扣，不存价格</b> —— 这是本表存在的全部理由。产品口径：
 * 「统一水票在站长端是特殊化的，但在用户端看起来没区别，执行上也不是统一定价，
 * 而是<b>对应水怎么统一打折、统一打几折</b>的区别，不是专门卖统一水票」。
 * ⇒ 「统一」统一的是**折扣率**（站级一处配），价格按**各款水自己的水票价**折算：
 * 农夫山泉按农夫山泉的价打 9.5 折、娃哈哈按娃哈哈的价打 9.5 折。</p>
 *
 * <p>⚠️ 一旦把价格也存进来，它就会与"各款水的价"分叉 —— v54 把统一票做成
 * "站级一个价（8.55/张、全站通用）"就是这么错的，已由 v58/v59 收口。</p>
 *
 * <p>生效判据（不另设开关列）：本站**有上架的档位** = 统一折扣生效。
 * 而"该商品走定制还是走统一"由 {@code inventory.ticket_enabled} 决定 —— 定制优先。</p>
 */
@Data
public class StationTicketDiscount {

    private Long id;

    /** 水站ID（折扣是站级设置） */
    private Long stationId;

    /** 本档张数（如 10 / 30 / 100）；与 station_id 组成唯一键 */
    private Integer qty;

    /**
     * 折扣千分比：{@code 950} = 9.5 折、{@code 900} = 9 折。
     *
     * <p>用整数千分比而不是小数：它是**乘进价格**的因子，浮点会带来"界面 8.55、批次 8.54"
     * 这类分叉（本仓把"展示与快照同源"当硬规则）。</p>
     */
    private Integer discountPerMille;

    /** 展示名（可空；为空时一律按「N 张 X 折」生成，不允许前后端各写一套） */
    private String title;

    /** 1 上架 0 下架 */
    private Integer status;

    /** 排序，小的在前 */
    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 是否上架（前端展示用，避免前端自己写 1/0 判断） */
    public boolean isOnShelf() {
        return status != null && status == 1;
    }
}
