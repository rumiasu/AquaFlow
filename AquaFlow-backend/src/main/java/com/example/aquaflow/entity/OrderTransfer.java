package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单转单记录实体，对应数据库 order_transfer 表 [AQ-015]。
 * <p>把转单（退回站长 / 转让 / 重分配 / 站间指定外派退回）状态从 {@code orders.special_note}
 * 的文本标记中抽离出来，结构化存储，供列表筛选与状态判定使用；special_note 仅保留展示文案。</p>
 */
@Data
public class OrderTransfer {

    /** 转单ID，主键自增 */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 转单类型：STAFF 配送员转单 / DIRECTED 站间指定外派退回 */
    private String kind;

    /** 子类型：RETURN_STATION 退回站长 / TRANSFER 转让 / REDISPATCH 重分配 / DIRECTED_RETURN 指定退回 */
    private String subKind;

    /** 发起方配送员ID */
    private Long fromStaffId;

    /** 目标配送员ID */
    private Long toStaffId;

    /** 发起方水站ID */
    private Long fromStationId;

    /** 目标水站ID */
    private Long toStationId;

    /** 状态：PENDING 待决策 / APPROVED 已同意 / REJECTED 已拒绝 / CANCELLED 已取消 */
    private String status;

    /** 原因 / 备注 */
    private String reason;

    /** 操作人员工ID */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    // ===== 类型/子类型/状态常量 =====

    public static final String KIND_STAFF = "STAFF";
    public static final String KIND_DIRECTED = "DIRECTED";

    public static final String SUB_RETURN_STATION = "RETURN_STATION";
    public static final String SUB_TRANSFER = "TRANSFER";
    public static final String SUB_REDISPATCH = "REDISPATCH";
    public static final String SUB_DIRECTED_RETURN = "DIRECTED_RETURN";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
