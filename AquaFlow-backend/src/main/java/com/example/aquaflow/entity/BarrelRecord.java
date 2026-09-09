package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 桶资产异常记录实体类，对应数据库 barrel_record 表。
 * <p>桶资产发生真正变化时留下的业务凭证。</p>
 */
@Data
public class BarrelRecord {

    /** 记录ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 商品ID(桶装水) */
    private Long productId;

    /** 类型: 1 新增押金桶 2 退桶 3 丢失 4 损坏 5 赔偿 6 人工调整 */
    private Integer type;

    /** 数量 */
    private Integer quantity;

    /** 关联订单ID */
    private Long relatedOrderId;

    /** 备注 */
    private String note;

    /** 操作员ID */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 退桶申请状态: 1 待处理 2 已确认收到空桶 3 已退押金 4 已驳回（仅type=2退桶有意义） */
    private Integer status;

    /**
     * 状态中文文案（全系统唯一来源）。
     * <p>前端曾同时在 JS 的 getStatusText() 与 WXML 的内联三元表达式里各写一份映射，
     * 新增状态时两处都要改、极易漏改。统一由后端下发，前端直接渲染 statusText。</p>
     */
    public String getStatusText() {
        if (status == null) return "使用中";
        switch (status) {
            case 1: return "待处理";
            case 2: return "已确认";
            case 3: return "已退押金";
            case 4: return "已驳回";
            default: return "使用中";
        }
    }

    /** 类型中文文案（全系统唯一来源） */
    public String getTypeText() {
        if (type == null) return "其他";
        switch (type) {
            case 1: return "新增押金桶";
            case 2: return "退桶";
            case 3: return "丢失";
            case 4: return "损坏";
            case 5: return "赔偿";
            case 6: return "人工调整";
            default: return "其他";
        }
    }

    /** 处理备注（站长驳回原因等） */
    private String handleNote;

    /** 退桶申请的退押金金额（仅type=2退桶有意义） */
    private java.math.BigDecimal depositRefund;

    /** 客户当前欠桶数（瞬时字段，不映射数据库，仅用于站长审批页提醒） */
    private transient Integer owedBuckets;
}
