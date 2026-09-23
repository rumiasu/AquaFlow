package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 配送员计件工资的请求体（v37），按端点分组。
 *
 * <p>放在一个文件里而不是各开一个类：它们都是"站长在工资页上填的东西"，
 * 语义上是一组。但<b>不复用同一个类</b> —— 混用一个 DTO 会让
 * 「这个字段在哪个端点上有效」变成靠记忆判断的事，本仓在
 * {@code DeliveryOrderActionDTO} 上已经因为"请求体收敛时漏抄字段"出过真事故
 * （AGENTS §8.15）。</p>
 */
public final class PayrollDTO {

    /**
     * PUT /api/manager/piece-rate：保存计件单价（productId=0 表示该站默认价）。
     *
     * <p>⚠️ 2026-09-18（v42）起工资口径只有两项：**每桶计件价** + **楼层补贴**。
     * 原来的 {@code returnBucketAmount} / {@code perOrderAmount} / {@code penaltyPerBucket}
     * 三个字段已随库列一起删除；客户端若还传它们，Jackson 会静默忽略（不报错）——
     * 这正是本仓 §8.15 那个"强类型 DTO 静默丢字段"的形状，所以**前端也要同步删掉这三项**。</p>
     */
    @Data
    public static class PieceRate {
        /** 商品ID；0/空 = 该站默认价 */
        private Long productId;
        /** 每送一桶的计件价 */
        private BigDecimal perBucketAmount;
        /** 无电梯时每超一层的补贴 */
        private BigDecimal floorBonusPerLevel;
        /** 免费楼层 */
        private Integer floorFreeLevel;
    }

    /** POST /api/manager/payroll：生成结算单 */
    @Data
    public static class Generate {
        private Long staffId;
        /** 期间起（含） */
        private LocalDate periodStart;
        /** 期间止（含） */
        private LocalDate periodEnd;
        private String note;
    }

    /** POST /api/manager/payroll/adjust：人工调整（可正可负） */
    @Data
    public static class Adjust {
        private Long staffId;

        /**
         * 自定义工资条目ID（v44，选填）。
         *
         * <p>⚠️ <b>传了 itemId 就只能传正数金额</b>：方向（加/扣）由条目决定。
         * 不传 itemId 时才是老的自由文本调整（金额自带符号）。
         * 这样"这次是补还是扣"不再依赖每次录入手感，也才汇总得出来。</p>
         */
        private Long itemId;

        /** 金额；不传 itemId 时**唯一允许自带符号的入口**（正=补，负=扣）；传了 itemId 必须为正 */
        private BigDecimal amount;
        private String note;
    }

    /** POST/PUT /api/manager/earning-items：站长自定义工资条目（v44） */
    @Data
    public static class EarningItem {
        /** 条目名称（去空格后 1~20 字，同站不得重名） */
        private String name;
        /** 方向：1 加项 / 2 扣项，见 {@code constant/EarningItemDirection} */
        private Integer direction;
        /** 展示顺序（可空，默认 0，小的在前） */
        private Integer sort;
    }

    /** POST /api/manager/earning-items/{id}/status：启用 / 停用 */
    @Data
    public static class EarningItemStatus {
        /** 1 启用 0 停用；停用只挡新录入，历史流水照旧 */
        private Integer status;
    }

    private PayrollDTO() {}
}
