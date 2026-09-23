package com.example.aquaflow.config;

import com.example.aquaflow.interceptor.AuthInterceptor;
import com.example.aquaflow.interceptor.RateLimitInterceptor;
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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序有意义：限流先于认证 —— 否则未认证的暴力请求会先走一遍 JWT 解析才被拒绝，
        // 而且登录/刷新这些"本来就不带 token"的端点在认证拦截器里是白名单，限流得自己覆盖它们。
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/auth/login",
                        "/api/auth/wx-login",
                        "/api/auth/wx-login-staff",
                        "/api/auth/dev-login",
                        "/api/auth/refresh",
                        "/api/auth/change-password"
                );

        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/wx-login",
                        "/api/auth/wx-login-staff",
                        "/api/auth/bind-staff",
                        "/api/auth/dev-login",
                        "/api/auth/refresh",
                        "/api/stations/public",
                        "/api/station/public",
                        "/api/stations/search",
                        "/api/station/search",
                        "/api/stations/{id}/public-phone",
                        "/api/station/{id}/public-phone",
                        // 水站营业状态（软状态）+ 站长留言：顾客端商城/下单页横幅要在**未登录**时也能看到，
                        // 所以必须是公开端点（只返回 id/名称/状态文案，不含任何站长私有字段）。
                        "/api/stations/{id}/status",
                        "/api/station/{id}/status"
                );
    }
}
