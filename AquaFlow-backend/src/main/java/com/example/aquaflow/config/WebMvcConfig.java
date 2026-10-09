package com.example.aquaflow.config;

import com.example.aquaflow.interceptor.AuthInterceptor;
import com.example.aquaflow.interceptor.RateLimitInterceptor;
import com.example.aquaflow.interceptor.PublicRateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 JWT 认证拦截器，排除公开接口；并在其之前挂一层登录类端点限流。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Autowired
    private PublicRateLimitInterceptor publicRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序有意义：限流先于认证 —— 否则未认证的暴力请求会先走一遍 JWT 解析才被拒绝，
        // 而且登录/刷新这些"本来就不带 token"的端点在认证拦截器里是白名单，限流得自己覆盖它们。
        // 名单口径 = 「一切**会签发或续期会话**的认证族端点」，不是"名字里带 login"。
        // [2026-09-30 F-03] /api/auth/bind-staff 必须在这里：它**免认证**（见下方
        // excludePathPatterns，顾客端/未登录也能打），却会直接签发员工
        // accessToken/refreshToken ⇒ 后果与登录端点同级，必须和它们同一层防护。
        // [2026-09-30 F-03③] 该端点的凭据已由「姓名 + 手机号」换成**站长签发的一次性绑定码**
        // （决策正本 docs/design/16 §9.3），所以原句「它自带的失败锁定（AuthTokenService.bindStaff
        // 的 "bind:"+name+":"+phone）」**已作废** —— 那个按凭据计数的锁定随凭据一起去掉了。
        // ⚠️ 但**这一层按来源 IP 的限流必须留着**：它是唯一"攻击者换不掉 key"的那层
        // （同 AGENTS §1.1「登录类端点必须按 IP 限流」），也是猜 6 位码唯一的成本。
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/auth/login",
                        "/api/auth/wx-login",
                        "/api/auth/wx-login-staff",
                        "/api/auth/dev-login",
                        "/api/auth/refresh",
                        "/api/auth/change-password",
                        "/api/auth/bind-staff"
                );

        // 独立公开配额不耗登录预算；探针及其它需认证的业务路径不在此列表。
        registry.addInterceptor(publicRateLimitInterceptor).addPathPatterns(
                "/api/stations/public", "/api/station/public",
                "/api/stations/search", "/api/station/search",
                "/api/stations/{id}/public-phone", "/api/station/{id}/public-phone",
                "/api/stations/{id}/status", "/api/station/{id}/status");

        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/wx-login",
                        "/api/auth/wx-login-staff",
                        "/api/auth/bind-staff",
                        "/api/auth/dev-login",
                        "/api/auth/refresh",
                        // Only packaged public text. Evidence/request routes remain authenticated; no UNSELECTED expansion.
                        "/api/agreements/current",
                        "/api/agreements/documents/{versionId}",
                        "/api/stations/public",
                        "/api/station/public",
                        "/api/stations/search",
                        "/api/station/search",
                        "/api/stations/{id}/public-phone",
                        "/api/station/{id}/public-phone",
                        // 水站营业状态（软状态）+ 站长留言：顾客端商城/下单页横幅要在**未登录**时也能看到，
                        // 所以必须是公开端点（只返回 id/名称/状态文案，不含任何站长私有字段）。
                        "/api/stations/{id}/status",
                        "/api/station/{id}/status",
                        // [2026-09-30 F-46] 存活探针：网关/容器探针不可能带 token，且它不查库、
                        // 只回固定状态串（见 SystemController javadoc）。加进这个名单 = 允许匿名访问，
                        // 请勿顺手把它挪去需要鉴权的一侧 —— 那会让 liveness 恒 401。
                        "/api/system/health"
                );
    }
}
