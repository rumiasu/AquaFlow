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

    public static Long getUserId() {
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

    public static Long getStationId() {
        AuthUser user = HOLDER.get();
        return user != null ? user.getStationId() : null;
    }

    /** 是否为站长（兼容 STATION_MANAGER / manager 双命名） */
    public static boolean isManager() {
        String role = getRole();
        return "STATION_MANAGER".equals(role) || "manager".equals(role);
    }

    /** 是否为配送员（兼容 DELIVERY / delivery 双命名） */
    public static boolean isDelivery() {
        String role = getRole();
        return "DELIVERY".equals(role) || "delivery".equals(role);
    }

    /** 获取当前员工所属水站，缺站时抛异常（站长/配送员必须绑定水站） */
    public static Long requireStationId() {
        Long stationId = getStationId();
        if (stationId == null) {
            throw new com.example.aquaflow.exception.BusinessException("当前账号未绑定水站");
        }
        return stationId;
    }

    /** 管理员才允许访问 */
    public static void requireManager() {
        if (!isManager()) {
            throw new com.example.aquaflow.exception.BusinessException("权限不足，仅站长可访问此接口");
        }
    }

    /**
     * 校验指定客户是否属于当前站长。
     */
    public static com.example.aquaflow.common.Result<Void> checkCustomerOwnership(Long customerOwnerId) {
        if (customerOwnerId == null) {
            return com.example.aquaflow.common.Result.error("客户未绑定水站");
        }
        if (!isManager()) {
            return com.example.aquaflow.common.Result.error("权限不足");
        }
        Long myStationId = getStationId();
        if (myStationId == null || !myStationId.equals(customerOwnerId)) {
            return com.example.aquaflow.common.Result.error("无权操作他站客户");
        }
        return null;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 获取当前登录客户的 ID。
     * 仅 userType 为 "customer" 时有效，否则抛异常。
     */
    public static Long requireCustomerId() {
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
        private final Long userId;
        private final String userType;
        private final String role;
        private final Long stationId;

        public AuthUser(Long userId, String userType, String role, Long stationId) {
            this.userId = userId;
            this.userType = userType;
            this.role = role;
            this.stationId = stationId;
        }

        public Long getUserId() { return userId; }
        public String getUserType() { return userType; }
        public String getRole() { return role; }
        public Long getStationId() { return stationId; }
    }
}
