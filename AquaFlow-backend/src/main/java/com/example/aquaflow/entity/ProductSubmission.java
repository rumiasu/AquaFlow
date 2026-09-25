package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站长自定义商品"上报给开发者补充进通用库"的登记记录，对应 {@code product_submission} 表。
 *
 * <p>产品口径（2026-09-16）：自定义商品<b>不入通用库</b>，站长只能"上报"；
 * 由开发者人工判断后把它转为通用库行（{@code product.owner_station_id = NULL}）或驳回。
 * 本仓没有平台管理端，所以处置动作只有 SQL/运维操作，站长的界面只负责上报与看结果。</p>
 */
@Data
public class ProductSubmission {

    private Long id;

    /** 上报水站 */
    private Long stationId;

    /** 被上报的商品（{@code product.id}，必为本站自定义商品） */
    private Long productId;

    /** 上报人（站长）；取不到则 NULL，只影响追溯 */
    private Long submitterStaffId;

    /** 站长补充说明（规格/品牌/进货渠道等） */
    private String note;

    /** 0 待处理 / 1 已纳入通用库 / 2 已驳回 */
    private Integer status;

    /** 开发者处置说明 */
    private String handleNote;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 待处理 */
    public static final int STATUS_PENDING = 0;
    /** 已纳入通用库 */
    public static final int STATUS_ACCEPTED = 1;
    /** 已驳回 */
    public static final int STATUS_REJECTED = 2;
}
