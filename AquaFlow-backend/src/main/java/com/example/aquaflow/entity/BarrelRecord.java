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

    /** 类型: 1 新增押金桶 2 退桶 3 丢失 4 损坏 5 赔偿 6 人工调整 7 纯还桶 8 配送收发明细 */
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
            case 7: return "纯还桶";
            case 8: return "配送收发";
            default: return "其他";
        }
    }

    /** 处理备注（站长驳回原因等） */
    private String handleNote;

    /** 退桶申请的退押金金额（仅type=2退桶有意义） */
    private java.math.BigDecimal depositRefund;

    /** 客户端幂等 token：纯还桶 / 退桶防重复提交（唯一索引 uk_record_client_token，NULL 不参与） */
    private String clientToken;

    /** 确认收到空桶的操作人（DEF-7：退桶不得从 1 直接跳到 3） */
    private Long confirmedBy;

    /** 确认收到空桶的时间 */
    private LocalDateTime confirmedTime;

    /** 变更前 over（<b>可为负</b>：负数=顾客多还桶/水站暂存，合法状态） */
    private Integer overBefore;

    /** 变更后 over（<b>可为负</b>） */
    private Integer overAfter;

    /**
     * 本单送出满桶数（仅 type=8 配送收发明细有值，其余类型恒 0）。
     * <p>没有这两个数字，就无法从流水重算「顾客手上实际有几个桶」，
     * 物理桶守恒（对账 V2 的 E5）也就无从校验——只能选择"相信代码没写错"。</p>
     */
    private Integer deliveredQty;

    /** 本单收回空桶数（仅 type=8 有值） */
    private Integer returnedQty;

    /**
     * 站长资产调整单 ID（station_adjustment.id），NULL=非调整产生。
     * <p>type=6/9（人工调整）必须带本字段：既是「这条流水属于哪张单」的唯一凭据，
     * 也是 uk_record_adjustment 的幂等依据（一张单最多一条桶流水）。</p>
     */
    private Long adjustmentId;

    /** 客户当前欠桶数（瞬时字段，不映射数据库，仅用于站长审批页提醒） */
    private transient Integer owedBuckets;
}
