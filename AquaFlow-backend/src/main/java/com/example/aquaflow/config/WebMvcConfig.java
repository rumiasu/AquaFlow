package com.example.aquaflow.config;

import com.example.aquaflow.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 JWT 认证拦截器，排除公开接口。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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
                        "/api/station/{id}/public-phone"
                );
    }
}
