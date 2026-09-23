package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户 Token 实体，对应 user_token 表。
 * 存储 refresh_token，用于 access_token 过期后无感续期。
 */
@Data
public class UserToken {

    private Long id;

    /** 用户ID（staff.id 或 customer.id） */
    private Long userId;

    /** 用户类型：staff / customer */
    private String userType;

    /** refresh_token 字符串 */
    private String refreshToken;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 设备标识（可选，用于区分多端登录） */
    private String deviceInfo;

    private LocalDateTime createTime;
}
