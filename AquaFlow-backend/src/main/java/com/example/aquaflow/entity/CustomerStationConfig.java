package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户×水站权限配置（v60 起承载**该客户在该站的经营授权**）。
 *
 * <p>三个字段回答三个不同的问题，别混：</p>
 * <ul>
 *   <li>{@code offlinePaymentEnabled} = <b>能不能不预付就下单</b>（站长逐个客户开通，本身就是一道审核）；</li>
 *   <li>{@code settlementCycle} = <b>账期从哪天起算</b>（现结 / 月结，见 {@code constant/SettlementCycle}）；</li>
 *   <li>{@code dueDays} = <b>起算点之后还给多少天</b>。{@code dueDays} 为空 = 即时结清、不挂账。</li>
 * </ul>
 *
 * <p>⚠️ 这三个都是**站级**的：同一家公司在 A 站可以月结、在 B 站只能现结。
 * 这正是本表存在的意义（见 {@code customer_privilege} 的注释：「A 站给的不在 B 站生效
 * （否则等于跨站送钱）」）。v60 之前账期错放在客户级的 {@code company_info.due_days}，
 * 导致 A 站设的账期在 B 站生效、B 站还能改掉 —— 那是租户边界漏洞，不是洁癖。</p>
 */
@Data
public class CustomerStationConfig {

    /** 主键 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 水站ID */
    private Long stationId;

    /** 是否允许线下支付（货到付款） */
    private Integer offlinePaymentEnabled;

    /** 该客户在该站的账期天数（NULL = 即时结清、不挂账） */
    private Integer dueDays;

    /** 结算周期：IMMEDIATE=现结 / MONTHLY=月结，见 constant/SettlementCycle */
    private String settlementCycle;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}