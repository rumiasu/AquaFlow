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

    /**
     * <b>[2026-09-12 已移除]</b> 原"水站线下支付总开关"。
     * <p>货到付款的唯一控制点已收敛为客户级授权 {@code customer_station_config.offline_payment_enabled}
     * （站长在「用户画像 → 权限设置 → 货到付款」逐个开通），见 {@code PaymentServiceImpl#canUseOfflinePayment}。
     * 本字段与配套接口/列均已停止读写，DROP 脚本见 {@code sql/migration_v22_drop_station_offline_payment.sql}。
     */

    /** 创建者站长ID */
    private Long creatorStaffId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
