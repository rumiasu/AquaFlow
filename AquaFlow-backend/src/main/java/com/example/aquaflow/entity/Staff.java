package com.example.aquaflow.entity;

import lombok.Data;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * 员工实体类，对应数据库 staff 表。
 * <p>站长(STATION_MANAGER) 和配送员(DELIVERY) 统一在这里。</p>
 * <p><b>V1 Binding 模型:</b></p>
 * <ul>
 *   <li>当前归属关系唯一字段: {@code stationId} (NULL 表示未绑定水站)</li>
 *   <li>申请历史记录请使用 {@link StaffStationApplication}</li>
 *   <li>禁止使用 applyStationId / bindingStatus 等废弃字段</li>
 * </ul>
 */
@Data
public class Staff {

    /** 员工ID，主键自增 */
    private Long id;

    /** 员工姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 微信openid (唯一约束: 微信账号与staff账号稳定映射) */
    private String openid;

    /** 密码哈希 (开发账号登录用)，禁止随接口序列化返回 */
    @JsonIgnore
    private String passwordHash;

    /**
     * 角色: 仅允许
     * <ul>
     *   <li>{@code STATION_MANAGER} 站长</li>
     *   <li>{@code DELIVERY}       配送员</li>
     * </ul>
     */
    private String role;

    /**
     * 所属水站ID:
     * <ul>
     *   <li>STATION_MANAGER + non-null = 已创建并管理水站的站长</li>
     *   <li>DELIVERY + non-null       = 已被站长审批绑定的配送员</li>
     *   <li>DELIVERY + NULL           = 选好配送员身份, 但尚未绑定/已解绑</li>
     * </ul>
     * V1 禁止任何默认值, 禁止自动绑定。
     */
    private Long stationId;

    /** 状态: 1 在职 2 离职 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 角色文本（后端派生，前端直接渲染） */
    public String getRoleText() {
        if ("STATION_MANAGER".equals(role)) return "站长";
        if ("DELIVERY".equals(role)) return "配送员";
        if ("ADMIN".equals(role)) return "管理员";
        return role == null ? "" : role;
    }

    /** 状态文本（后端派生，前端直接渲染） */
    public String getStatusText() {
        return status != null && status == 1 ? "在职" : "离职";
    }
}
