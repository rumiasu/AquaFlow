package com.example.aquaflow.aspect;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.AuthContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 角色权限校验 AOP 拦截器。
 * 拦截所有带 @RequireRole 注解的方法，校验当前用户角色是否在允许列表中。
 */
@Aspect
@Component
public class RequireRoleAspect {

    @Around("@annotation(requireRole) || @within(requireRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        // 类级注解时，参数注入的 requireRole 为 null，需回退到类上查找
        if (requireRole == null) {
            requireRole = AnnotationUtils.findAnnotation(
                    joinPoint.getSignature().getDeclaringType(), RequireRole.class);
        }
        if (requireRole == null) {
            return joinPoint.proceed();
        }
        
        // 获取 JWT 中的角色（可能是 manager/delivery 或 STATION_MANAGER/DELIVERY）
        String userType = AuthContext.getUserType();
        String rawRole = AuthContext.getRole();
        
        // 必须是员工类型
        if (!"staff".equals(userType)) {
            throw new BusinessException("仅员工可访问此接口");
        }
        
        // 将前端角色名映射回数据库角色名（兼容两套命名）
        String role = mapToFrontendRole(rawRole);
        
        // 检查角色是否在允许列表中
        String[] allowedRoles = requireRole.value();
        // 同时映射允许列表中的角色
        java.util.Set<String> allowedSet = new java.util.HashSet<>();
        for (String r : allowedRoles) {
            allowedSet.add(r);                    // 原始值 STATION_MANAGER 等
            allowedSet.add(mapToFrontendRole(r)); // 映射值 manager 等
        }
        if (!allowedSet.contains(role)) {
            throw new BusinessException("权限不足，当前角色：" + rawRole);
        }
        
        // 权限通过，执行目标方法
        return joinPoint.proceed();
    }

    /**
     * 将数据库角色名映射为前端角色名，如果已经是前端角色名则原样返回。
     * STATION_MANAGER → manager, DELIVERY → delivery
     */
    private String mapToFrontendRole(String role) {
        if (role == null) return null;
        switch (role) {
            case "STATION_MANAGER": return "manager";
            case "DELIVERY": return "delivery";
            default: return role; // 已经是 manager/delivery
        }
    }
}
