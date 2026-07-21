package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退桶记录实体类，对应数据库 barrel_record 表。
 * <p>记录客户每次退桶的申请和处理情况，作为退桶凭证。</p>
 */
@Data
public class BarrelRecord {

    /** 记录ID，主键自增 */
    private Integer id;

    /** 客户ID，关联 customer 表 */
    private Integer customerId;

    /** 退桶数量 */
    private Integer quantity;

    /**
     * 状态：
     * <ul>
     *   <li>1 - 待处理</li>
     *   <li>2 - 已确认（站内确认收到空桶）</li>
     *   <li>3 - 已退还押金</li>
     *   <li>4 - 已驳回</li>
     * </ul>
     */
    private Integer status;

    /** 抵扣押金金额（可选，退桶时可同时退押金） */
    private java.math.BigDecimal depositRefund;

    /** 备注 */
    private String note;

    /** 处理备注（站内人员填写） */
    private String handleNote;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 处理时间 */
    private LocalDateTime handleTime;
}
