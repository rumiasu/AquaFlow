package com.example.aquaflow.interceptor;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * JWT 认证拦截器。
 * 从 Authorization 头提取 Bearer token，校验后将用户信息存入 AuthContext。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StaffMapper staffMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            send401(response, "未提供认证令牌");
            return false;
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            send401(response, "令牌无效或已过期");
            return false;
        }

        Claims claims = jwtUtil.parseToken(token);

        String tokenType = claims.get("tokenType", String.class);
        if (!"access".equals(tokenType)) {
            send401(response, "请使用 access_token 访问接口");
            return false;
        }

        Number userIdNum = claims.get("userId", Number.class);
        String userType = claims.get("userType", String.class);
        String role = claims.get("role", String.class);
        Number stationIdNum = claims.get("stationId", Number.class);
        // 仅 UNSELECTED 会话携带：wx-login-staff 签发时就签进来的待绑定 openid。
        // select-role 只认这个值，不再读客户端请求体里的 _pendingOpenid（防抢绑他人微信）。
        String pendingOpenid = claims.get("pendingOpenid", String.class);

        Long userId = userIdNum != null ? userIdNum.longValue() : null;
        Long stationId = stationIdNum != null ? stationIdNum.longValue() : null;

        // [AQ-024] 员工 token 回查：JWT 只验签不查库，一旦员工被停用/调站，旧 token 在有效期内（默认2h）
        // 仍可继续访问，属越权窗口。此处对 staff 类型 token 每次请求回查员工实际状态：
        //   1) 员工必须仍存在且在职（status=1）—— 停用/删除即时失效；
        //   2) role/stationId 以库内为准 —— 调站后旧 token 无法继续操作原站数据。
        //
        // [2026-09-12] 例外：UNSELECTED 会话的 userId 是**负数占位**（wx-login-staff 用
        // `-|openid.hashCode()|` 生成，因为此时还没有 staff 记录）。对负数 ID 查库必然查不到，
        // 于是这条会话的任何请求都被判成"账号已被停用"——/api/auth/select-role 因此在
        // 首次选身份这一步恒定 401。这类会话本就没有员工行，不是"停用"，跳过回查；
        // 它拿不到 stationId（仍为 null），所有需要水站的接口都会 fail-closed 拒绝。
        boolean isUnselectedSession = "UNSELECTED".equals(role);
        if ("staff".equals(userType) && userId != null && (userId > 0 || !isUnselectedSession)) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null || staff.getStatus() == null || staff.getStatus() != 1) {
                send401(response, "账号已被停用或不存在，请重新登录");
                return false;
            }
            // 以库内真实角色/归属为准，覆盖 token 中的旧值
            role = staff.getRole();
            stationId = staff.getStationId();
        }

        AuthContext.set(new AuthContext.AuthUser(userId, userType, role, stationId, pendingOpenid));

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private void send401(HttpServletResponse response, String message) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 401);
        result.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
