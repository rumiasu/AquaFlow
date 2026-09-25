package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体，对应 audit_log 表
 */
@Data
public class AuditLog {
    private Integer id;
    private Integer userId;
    private String username;
    private String role;
    private String module;
    private String action;
    private String target;
    private String detail;
    private String ip;
    private LocalDateTime createTime;
}
