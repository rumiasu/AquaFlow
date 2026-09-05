package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水站实体类，对应数据库 station 表。
 * <p><b>V1 Binding 模型:</b> 站长关系<b>不在 station 表里存 manager 字段</b>，真正的站长关系由:
 * <pre>
 *   staff.role       = STATION_MANAGER
 *   staff.station_id = station.id
 * </pre>
 * 表达。
 */
@Data
public class Station {

    /** 水站ID，主键自增 */
    private Long id;

    /** 水站名称 */
    private String name;

    /** 水站电话 */
    private String phone;

    /** 水站地址 */
    private String address;

    /** 状态: 1 营业 2 停业 */
    private Integer status;

    /** 是否允许线下支付总开关 */
    private Integer offlinePaymentEnabled;

    /** 创建者站长ID */
    private Long creatorStaffId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
