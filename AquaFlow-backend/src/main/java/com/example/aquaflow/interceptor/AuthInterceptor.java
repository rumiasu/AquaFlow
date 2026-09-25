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
import java.util.List;
import java.util.Map;

/**
 * JWT 认证拦截器。
 * 从 Authorization 头提取 Bearer token，校验后将用户信息存入 AuthContext。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * <b>未完成身份选择的会话（role=UNSELECTED）能访问的全部路径</b>。
     *
     * <p>[2026-09-25 架构评审问题 1] 这种会话是 {@code wx-login-staff} 对"微信登录了员工端、
     * 但还没有 staff 记录"的 openid 签发的：{@code userId} 为负数占位、{@code role=UNSELECTED}、
     * {@code stationId=null}。它<b>既没有员工行也没有站别</b>，所以任何"按参数过滤"的业务端点
     * 都只能退化成"参数为空 ⇒ 不加归属限制"。实测：{@code GET /api/orders} 会返回<b>全部水站</b>
     * 的订单，还带客户姓名 / 电话 / 地址快照。</p>
     *
     * <p>判据：<b>身份未定 ⇒ 默认拒绝</b>，只放引导流程。这样新增业务端点不必再"记得"补注解
     * ——{@code RequireRoleAspect} 是"无注解即放行"，靠人记性是这条链上最薄的一环。</p>
     *
     * <p>⚠️ 往这里加路径前先回答一句：<b>这个端点在"没有员工行、没有站别"时真能用吗？</b>
     * {@code /api/auth/me} 与 {@code /api/delivery/bind/status} 保留在名单里，只是因为它们会
     * 明确回一句业务错误（"用户不存在" / "员工不存在"），而客户端有几条既有分支靠这个错误
     * 决定跳转到哪一页；把它们删掉会把"新用户引导"堵死。</p>
     */
    private static final List<String> UNSELECTED_ALLOWED_PATHS = List.of(
            "/api/auth/select-role",       // 它的全部意义：选定身份、建员工记录
            "/api/auth/me",                // 客户端启动时探活（对 UNSELECTED 会回"用户不存在"）
            "/api/auth/logout",            // 退出登录，不该被身份状态挡住
            "/api/delivery/bind/status"    // 绑定状态探活（对 UNSELECTED 会回"员工不存在"）
    );

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

        // [2026-09-25 架构评审问题 1] 未选身份的会话只许走引导流程。
        // 放在 AuthContext.set 之后：拒绝响应统一由下面这个私有方法写，格式与 401 一致
        // （HTTP 200 + code=1 —— 本仓"业务错误仍是 200"的约定，见 AGENTS §2；
        //   客户端一律判 body code，不判 HTTP 状态）。
        if (isUnselectedSession && !UNSELECTED_ALLOWED_PATHS.contains(request.getRequestURI())) {
            sendBusinessError(response, "请先完成身份选择（站长 / 配送员）后再使用该功能");
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    /**
     * 业务错误响应：HTTP 200 + {@code {code:1, message}}。
     * <p>与 {@code RequireRoleAspect} 抛 {@code BusinessException} 后的形态一致 —— 本仓的
     * 权限类拒绝<b>不是</b>真 403，两端小程序都按 body {@code code} 处理（AGENTS §2、§8.14）。</p>
     */
    private void sendBusinessError(HttpServletResponse response, String message) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
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
