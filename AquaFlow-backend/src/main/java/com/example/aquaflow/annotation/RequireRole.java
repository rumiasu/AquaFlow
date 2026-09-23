package com.example.aquaflow.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色权限校验注解。
 * 用于 Controller 方法上，限制只有指定角色的员工才能访问。
 * 
 * 使用示例：
 * @RequireRole("STATION_MANAGER")  // 仅站长可访问
 * @RequireRole({"STATION_MANAGER", "DELIVERY"})  // 站长或配送员可访问
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    /**
     * 允许访问的角色列表
     */
    String[] value();
}
