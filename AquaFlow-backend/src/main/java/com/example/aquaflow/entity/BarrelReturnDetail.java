package com.example.aquaflow.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 审批、实物交接和实际退款分别留痕；收桶后不能通过驳回抹去事实。 */
@Data
public class BarrelReturnDetail {
    public String getStatusText() {
        if (status==null) return "未知状态";
        return switch(status) {
            case "APPLIED" -> "待批准";
            case "APPROVED" -> customerConfirmedTime==null?"已批准，待客户确认":"已确认安排，待收桶";
            case "RECEIVED" -> "已交接，待退押金";
            case "REFUNDED" -> "押金已交付";
            case "REJECTED" -> "已驳回";
            case "WITHDRAWN" -> "客户已撤回";
            default -> "未知状态";
        };
    }
    public String getPickupModeText() { return "PICKUP".equals(pickupMode)?"单独上门":"COMBINED".equals(pickupMode)?"随送水订单收桶":"到店退桶"; }
    private Long recordId;
    private Long customerId;
    private Long stationId;
    private String idempotencyKey;
    private String pickupMode;
    private Long companionOrderId;
    private Integer requiredBarrels;
    private Integer receivedBarrels;
    private BigDecimal pickupFee;
    private Long feePaymentId;
    private Integer feePaymentStatus;
    private String status;
    private LocalDateTime approvedTime;
    private LocalDateTime customerConfirmedTime;
    private LocalDateTime receivedTime;
    private LocalDateTime refundDueTime;
    private String note;
}
