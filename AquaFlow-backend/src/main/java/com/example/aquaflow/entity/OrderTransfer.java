package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单转单 / 审批申请记录实体，对应数据库 order_transfer 表 [AQ-015]。
 * <p>把「退回站长 / 转让 / 重分配 / 站间指定外派退回 / 取消申请」这些<b>需要他人决策的订单请求</b>
 * 从 {@code orders.special_note} 的文本标记中抽离出来，结构化存储，供列表筛选与状态判定使用；
 * special_note 仅保留展示文案。</p>
 *
 * <p><b>两类维度</b>（2026-09-14 扩展）：</p>
 * <ul>
 *   <li>{@code kind} = <b>发起通道</b>：{@code STAFF} 站内（配送员）发起 / {@code CUSTOMER} 客户发起 /
 *       {@code DIRECTED} 站间指定外派。站长端「审批」页即按此维度分「站内」「客户」两个子页签。</li>
 *   <li>{@code subKind} = <b>请求事项</b>：退回站长 / 转让 / 重分配 / 指定退回 / 取消申请。</li>
 * </ul>
 *
 * <p>{@code status} 是审批结论的唯一真值：{@code PENDING} 待决策 → {@code APPROVED}/{@code REJECTED}
 * （或发起方主动 {@code CANCELLED} 撤回）。索引 {@code idx_ot_pending(status, kind)} 即为按来源分页查询而建。</p>
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

    // ===== 发起通道（kind）=====

    /** 站内发起：配送员（退回站长 / 转让 / 重分配 / 取消申请） */
    public static final String KIND_STAFF = "STAFF";

    /** 客户发起：顾客在小程序提交的申请（目前仅取消申请） */
    public static final String KIND_CUSTOMER = "CUSTOMER";

    /** 站间发起：指定水站外派退回 */
    public static final String KIND_DIRECTED = "DIRECTED";

    // ===== 请求事项（subKind）=====

    public static final String SUB_RETURN_STATION = "RETURN_STATION";
    public static final String SUB_TRANSFER = "TRANSFER";
    public static final String SUB_REDISPATCH = "REDISPATCH";
    public static final String SUB_DIRECTED_RETURN = "DIRECTED_RETURN";

    /**
     * 取消申请：客户或配送员请求取消订单，必须经站长同意。
     * <p>与「退回站长」的关键区别：退回 = 订单继续（变回待分配，可再派）；
     * 取消申请 = 订单终止（同意后走 {@code PaymentService.refundOrder} 完整退款链：
     * 退水票 → 退支付流水 → 退押金 → 回补库存 → 状态置已取消）。</p>
     */
    public static final String SUB_CANCEL_REQUEST = "CANCEL_REQUEST";

    // ===== 审批结论（status）=====

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
