package com.example.aquaflow.util;

/**
 * 基于 ThreadLocal 的当前用户上下文。
 * AuthInterceptor 解析 JWT 后存入，Controller/Service 层通过静态方法读取。
 * 请求结束后由拦截器 afterCompletion 清除，防止内存泄漏。
 */
public class AuthContext {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    public static void set(AuthUser user) {
        HOLDER.set(user);
    }

    public static AuthUser get() {
        return HOLDER.get();
    }

    public static Integer getUserId() {
        AuthUser user = HOLDER.get();
        return user != null ? user.getUserId() : null;
    }

    public static String getUserType() {
        AuthUser user = HOLDER.get();
        return user != null ? user.getUserType() : null;
    }

    public static String getRole() {
        AuthUser user = HOLDER.get();
        return user != null ? user.getRole() : null;
    }

    public static Integer getStationId() {
        AuthUser user = HOLDER.get();
        return user != null ? user.getStationId() : null;
    }

    public static Integer getFactoryId() {
        AuthUser user = HOLDER.get();
        return user != null ? user.getFactoryId() : null;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 获取当前登录客户的 ID。
     * 仅 userType 为 "customer" 时有效，否则抛异常。
     * 用于客户 API 的数据隔离，防止越权访问他人数据。
     */
    public static Integer requireCustomerId() {
        AuthUser user = HOLDER.get();
        if (user == null) {
            throw new com.example.aquaflow.exception.BusinessException("未登录");
        }
        if (!"customer".equals(user.getUserType())) {
            throw new com.example.aquaflow.exception.BusinessException("仅客户可访问此接口");
        }
        return user.getUserId();
    }

    /**
     * 获取当前登录员工的角色。
     * 仅 userType 为 "staff" 时有效。
     */
    public static String requireStaffRole() {
        AuthUser user = HOLDER.get();
        if (user == null) {
            throw new com.example.aquaflow.exception.BusinessException("未登录");
        }
        if (!"staff".equals(user.getUserType())) {
            throw new com.example.aquaflow.exception.BusinessException("仅员工可访问此接口");
        }
        return user.getRole();
    }

    /**
     * 当前登录用户信息（从 JWT claims 解析）
     */
    public static class AuthUser {
        private final Integer userId;
        private final String userType;
        private final String role;
        private final Integer stationId;
        private final Integer factoryId;

        public AuthUser(Integer userId, String userType, String role, Integer stationId, Integer factoryId) {
            this.userId = userId;
            this.userType = userType;
            this.role = role;
            this.stationId = stationId;
            this.factoryId = factoryId;
        }

        public Integer getUserId() { return userId; }
        public String getUserType() { return userType; }
        public String getRole() { return role; }
        public Integer getStationId() { return stationId; }
        public Integer getFactoryId() { return factoryId; }
    }
}
