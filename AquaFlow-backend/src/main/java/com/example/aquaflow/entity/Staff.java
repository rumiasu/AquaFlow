package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工实体类，对应数据库 staff 表。
 * 支持角色：FACTORY_ADMIN / STATION_MANAGER / DELIVERY 等。
 */
@Data
public class Staff {

    private Integer id;

    private String name;

    private String phone;

    /** BCrypt 加密后的密码 */
    private String password;

    private Integer factoryId;

    private Integer stationId;

    /** 角色：FACTORY_ADMIN/STATION_MANAGER/DELIVERY */
    private String role;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
