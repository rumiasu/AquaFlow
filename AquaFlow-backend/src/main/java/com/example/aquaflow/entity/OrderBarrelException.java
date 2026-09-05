package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单桶异常记录实体类，对应数据库 order_barrel_exception 表。
 * <p>统一记录订单层面的桶相关异常：少回、多回、拒收、损坏、缺水等。
 * 资产/押金变动不在此表，仅记录异常事实与调解决策。
 */
@Data
public class OrderBarrelException {

    /** 异常ID，主键自增 */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 配送员ID */
    private Long deliveryStaffId;

    /** 应送桶数 */
    private Integer deliveryQty;

    /** 实收桶数 */
    private Integer returnQty;

    /** 差异 = deliveryQty - returnQty (正=少回，负=多回) */
    private Integer discrepancy;

    /** 异常大类: RETURN_SHORT/RETURN_OVER/RETURN_REFUSE/RETURN_DAMAGE/STATION_SHORTAGE/CUSTOMER_REFUSE/OTHER */
    private String category;

    /** 具体类型细分 */
    private String type;

    /** 配送员现场动作: FULL/PARTIAL/REFUSE/OWE */
    private String staffAction;

    /** 配送员备注 */
    private String staffNote;

    /** 实际给水桶数 */
    private Integer waterGiven;

    /** 欠水桶数 */
    private Integer waterOwed;

    /** 站长调解动作: REFUND_TICKET/REFUND_CASH/WAIVE_DEPOSIT/ADJUST_ASSET/RESCHEDULE/IGNORE */
    private String managerAction;

    /** 退水票数量 */
    private Integer refundTicketQty;

    /** 退现金金额 */
    private BigDecimal refundCashAmount;

    /** 调整桶资产数量(正增/负减) */
    private Integer adjustAssetQty;

    /** 调整的产品ID */
    private Long adjustProductId;

    /** 站长处理备注 */
    private String managerNote;

    /** 系统建议退水票数 */
    private Integer suggestedTicketQty;

    /** 系统建议退现金金额 */
    private BigDecimal suggestedCashAmount;

    /** 状态: STAFF_RECORDED/MANAGER_PENDING/MANAGER_APPROVED/EXECUTING/EXECUTED/IGNORED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 站长决策时间 */
    private LocalDateTime decidedAt;

    /** 执行完成时间 */
    private LocalDateTime executedAt;
}