package com.example.aquaflow.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 认证/登录入口请求体（Phase F-1：LoginController + DevLoginController 由 Map 强类型化）。
 * <p>嵌套类按端点划分；校验注解将原 Controller 内的手工判空前移到 @Valid 边界，
 * 非法入参由 GlobalExceptionHandler 统一转 Result.error（code=1）。</p>
 */
public class AuthRequestDTO {

    /** POST /api/auth/wx-login：顾客微信登录 */
    @Data
    public static class WxLogin {
        @NotBlank(message = "登录code不能为空")
        private String code;
    }

    /** POST /api/auth/wx-login-staff：配送端微信登录（同 WxLogin 形状） */
    @Data
    public static class WxLoginStaff {
        @NotBlank(message = "登录code不能为空")
        private String code;
    }

    /** POST /api/auth/select-role：首次进入配送端选择角色 */
    @Data
    public static class SelectRole {
        @NotBlank(message = "角色参数非法")
        @Pattern(regexp = "STATION_MANAGER|DELIVERY", message = "角色参数非法")
        private String role;

        private String nickname;

        private String phone;

        /** 小程序发送的键为 _pendingOpenid（来自 wx-login-staff 的 UNSELECTED 会话） */
        @JsonProperty("_pendingOpenid")
        private String pendingOpenid;
    }

    /** POST /api/auth/create-station：站长创建水站并自绑 */
    @Data
    public static class CreateStation {
        @NotBlank(message = "水站名称不能为空")
        private String name;

        private String phone;

        private String province;

        private String city;

        private String district;

        private String address;

        /**
         * 地图选点的纬度 / 经度（2026-09-17 补，v34）。
         *
         * <p>⚠️ 这两个字段此前**根本不存在**：`miniapp-delivery` 的建站页从 2026-09-16 起
         * 就一直在发 {@code latitude}/{@code longitude}，而本 DTO 没有对应字段 ——
         * Jackson 对未知字段静默忽略，不报错也不进日志，于是站长选的点**凭空消失**，
         * 而 {@code station} 表当时也没有坐标列，等于三层都缺。
         * 这与 AGENTS.md §8 第 15 条（配送端 collected/note 被静默丢弃）是同一个形状。</p>
         *
         * <p>可空：站长可以不定位（V1 极简原则）。为空时配送范围校验会跳过而不是拒单。</p>
         */
        private java.math.BigDecimal latitude;

        /** 经度，语义同 {@link #latitude} */
        private java.math.BigDecimal longitude;
    }

    /** POST /api/auth/bind-staff：员工绑定微信（姓名+手机号+wx code） */
    @Data
    public static class BindStaff {
        @NotBlank(message = "登录code不能为空")
        private String code;

        @NotBlank(message = "姓名不能为空")
        private String name;

        @NotBlank(message = "手机号不能为空")
        private String phone;
    }

    /** POST /api/auth/update-profile：更新资料（两字段均可选，仅强类型化） */
    @Data
    public static class UpdateProfile {
        private String nickname;

        private String phone;
    }

    /** POST /api/auth/login：管理后台账号密码登录 */
    @Data
    public static class Login {
        @NotBlank(message = "用户名和密码不能为空")
        private String username;

        @NotBlank(message = "用户名和密码不能为空")
        private String password;
    }

    /** POST /api/auth/refresh：刷新 token */
    @Data
    public static class Refresh {
        @NotBlank(message = "refreshToken不能为空")
        private String refreshToken;
    }

    /** POST /api/auth/change-password：员工修改密码 */
    @Data
    public static class ChangePassword {
        @NotBlank(message = "旧密码和新密码不能为空")
        private String oldPassword;

        @NotBlank(message = "旧密码和新密码不能为空")
        @Size(min = 6, message = "新密码长度不能少于6位")
        private String newPassword;
    }

    /** POST /api/auth/dev-login：开发模式登录（仅非 prod + 显式开关；字段全部可选，保持默认值语义） */
    @Data
    public static class DevLogin {
        private String role;

        private String openid;

        private String nickname;
    }
}
