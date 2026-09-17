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

    /** PUT /api/manager/piece-rate：保存计件单价（productId=0 表示该站默认价） */
    @Data
    public static class PieceRate {
        /** 商品ID；0/空 = 该站默认价 */
        private Long productId;
        /** 每送一桶的计件价 */
        private BigDecimal perBucketAmount;
        /** 每回收一个空桶的奖励 */
        private BigDecimal returnBucketAmount;
        /** 无电梯时每超一层的补贴 */
        private BigDecimal floorBonusPerLevel;
        /** 免费楼层 */
        private Integer floorFreeLevel;
        /** 每单基础奖励 */
        private BigDecimal perOrderAmount;
        /** 每少收一个空桶的扣减 */
        private BigDecimal penaltyPerBucket;
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
        /** 金额；**唯一允许自带符号的入口**（正=补，负=扣） */
        private BigDecimal amount;
        private String note;
    }

    private PayrollDTO() {}
}
