package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业身份申请（v50）：客户提交 → 站长审核 → 通过后转 {@code customer.customer_type = 2}
 * 并把企业资料写进 {@code company_info}。
 *
 * <p>产品口径：「在订水时检测到大额订单，弹出确认是否是企业，可申请企业身份这种」
 * —— 刻意**不做独立的企业端入口**，这张表只承载"申请"这一件事。</p>
 */
@Data
public class CustomerEnterpriseApply {

    /** 待审核 */
    public static final String PENDING = "PENDING";
    /** 已通过 */
    public static final String APPROVED = "APPROVED";
    /** 已驳回 */
    public static final String REJECTED = "REJECTED";

    private Long id;
    private Long customerId;
    private Long stationId;
    private String companyName;
    private String contactPerson;
    private String contactPhone;
    private String taxNo;
    private String status;
    private String reviewNote;
    private Long reviewerId;
    private LocalDateTime applyTime;
    private LocalDateTime reviewTime;

    /** 状态文案由后端下发（前端禁止自带映射表）。 */
    public String getStatusText() {
        if (APPROVED.equals(status)) {
            return "已通过";
        }
        if (REJECTED.equals(status)) {
            return "已驳回";
        }
        return "待审核";
    }
}
