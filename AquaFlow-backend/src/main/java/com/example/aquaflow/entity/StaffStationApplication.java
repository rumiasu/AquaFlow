package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配送员-水站绑定/解绑申请审批记录，对应 staff_station_application 表。
 * <p><b>注意:</b> 这张表只是"申请历史"。真正的当前绑定关系<b>永远</b>由
 * <pre>
 *   staff.station_id
 * </pre>
 * 表达。不要使用 application.status 代替 staff.station_id。
 */
@Data
public class StaffStationApplication {

    // ----- type 常量 -----
    /** 绑定申请 */
    public static final int TYPE_BIND   = 1;
    /** 解绑申请 */
    public static final int TYPE_UNBIND = 2;

    // ----- status 常量 -----
    /** 待审批 */
    public static final int STATUS_PENDING   = 1;
    /** 已同意 */
    public static final int STATUS_APPROVED  = 2;
    /** 已拒绝 */
    public static final int STATUS_REJECTED  = 3;
    /** 已取消 */
    public static final int STATUS_CANCELLED = 4;

    /** 主键 */
    private Long id;

    /** 申请人 staff.id，必须是 DELIVERY 角色 */
    private Long staffId;

    /** 申请绑定/解绑的水站ID */
    private Long stationId;

    /** 1=绑定申请, 2=解绑申请 */
    private Integer type;

    /** 1=待审批, 2=已同意, 3=已拒绝, 4=已取消 */
    private Integer status;

    /** 申请说明 */
    private String applyNote;

    /** 审批人 (该水站 STATION_MANAGER 的 staff.id) */
    private Long handleStaffId;

    /** 审批说明 */
    private String handleNote;

    /** 申请时间 */
    private LocalDateTime createTime;

    /** 审批时间 (未审批=NULL) */
    private LocalDateTime handleTime;
}