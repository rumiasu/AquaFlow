package com.example.aquaflow.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 站长端「欠桶台账」一行：某客户在某桶型上的欠桶。
 *
 * <p>口径（重要）：{@code overQty} 是 {@code customer_barrel_over.over_qty} 的<b>当前净额</b>
 * （已被回收/核销过的部分不在这里），只取 {@code > 0} 的行。
 * 「欠了几天」来自 {@code owed_since}（欠桶起始时间，v29 新增）。</p>
 *
 * <p>明细（哪一单欠的、差几个、处理到哪一步）不在这里重复存：直接查现成的
 * {@code order_barrel_exception}（{@code discrepancy > 0} 的记录），
 * 两者是「当前净额」与「历史事件」的关系，<b>不可相加</b>。</p>
 */
@Data
public class OwedBarrelVO {

    /** 超过这个天数在列表里标记为"需催收"（仅展示提示，不阻断任何操作） */
    private static final int URGENT_DAYS = 7;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Long customerId;
    private String customerName;
    private String phone;

    private Long productId;
    private String productName;
    private String productSpec;

    /** 当前欠桶数（净额，恒 &gt; 0，否则不进列表） */
    private Integer overQty;

    /** 本次欠桶起始时间；为 null 表示历史存量行未回填（展示为"天数未知"） */
    private LocalDateTime owedSince;

    /** 欠桶天数（自然日；欠桶当天为 0 天）。owedSince 为空时返回 null。 */
    public Integer getOwedDays() {
        Long d = owedDays(owedSince);
        return d == null ? null : d.intValue();
    }

    /** 天数文案：未知时明确写"天数未知"，不要让前端各自兜底 */
    public String getOwedDaysText() {
        Integer d = getOwedDays();
        return d == null ? "天数未知" : d + " 天";
    }

    public String getOwedSinceText() {
        return owedSince == null ? "" : owedSince.format(FMT);
    }

    /** 是否已达催收提醒线（仅前端标红用，不产生任何拦截） */
    public Boolean getUrgent() {
        Integer d = getOwedDays();
        return d != null && d >= URGENT_DAYS;
    }

    /** 一行摘要，供站长端列表与下单提醒复用同一措辞 */
    public String getSummaryText() {
        String name = productName == null ? "未知商品" : productName;
        return name + " 欠 " + (overQty == null ? 0 : overQty) + " 个（已 " + getOwedDaysText() + "）";
    }

    /**
     * 从欠桶起始时间算天数（自然日，当天为 0）。
     * 供 {@code OrderServiceImpl} 的下单提醒与本站 VO 复用，避免两处口径不一致。
     */
    public static Long owedDays(LocalDateTime owedSince) {
        if (owedSince == null) return null;
        return ChronoUnit.DAYS.between(owedSince.toLocalDate(), LocalDate.now());
    }
}
