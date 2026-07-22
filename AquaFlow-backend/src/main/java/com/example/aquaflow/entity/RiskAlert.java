package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风险预警记录
 */
@Data
public class RiskAlert {

    private Integer id;

    /** 水站ID */
    private Integer stationId;

    /** 预警类型: ORDER_DECLINE/INVENTORY_BACKLOG/LOW_STOCK/CUSTOMER_LOSS/NO_ACTIVITY */
    private String alertType;

    /** 预警等级: 1=提示 2=警告 3=紧急 */
    private Integer alertLevel;

    /** 预警标题 */
    private String title;

    /** 预警详情 */
    private String content;

    /** 建议措施 */
    private String suggestion;

    /** 状态: 1=未读 2=已读 3=已处理 */
    private Integer status;

    /** 处理备注 */
    private String handleNote;

    /** 水站名称（关联查询） */
    private String stationName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
