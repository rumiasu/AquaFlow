package com.example.aquaflow.aspect;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.AuthContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 角色权限校验 AOP 拦截器。
 * <p>拦截 Controller 上带 {@code @RequireRole} 的方法与类，校验当前用户角色是否在允许列表中。</p>
 *
 * <p><b>为什么不用 {@code @annotation(requireRole) || @within(requireRole)} 作为切点：</b>
 * 实测该写法下只有<b>类级</b>注解会被拦截，<b>方法级</b>注解一律不生效 ——
 * 于是所有只在方法上标注的接口（/api/delivery/orders/pending、/api/inventory、
 * /api/dashboard/today、/api/payments/* 等）实际上处于"无鉴权"状态：
 * 顾客 token 请求 /api/delivery/orders/pending 直接返回 code=0 并读到配送端订单数据。</p>
 *
 * <p>现改为对全部 Controller 方法统一织入，再在通知内按「方法注解优先、类注解兜底」自行解析，
 * 结果确定、可验证，不再依赖 AspectJ 的注解参数绑定行为。</p>
 *
 * <p><b>⚠️ 新增端点的强制约定（2026-09-14 补充）：</b>本切点是「无注解即放行」，
 * 所以每个新端点必须二选一，否则等同于裸奔：</p>
 * <ul>
 *   <li><b>员工端点</b>：标注 {@code @RequireRole({"STATION_MANAGER"})} / {@code {"DELIVERY"}} 等。
 *       方法级、类级都生效；本项目习惯把它写在 {@code @XxxMapping} <b>之后</b>，
 *       本切面上下位置都能解析（注意：用文本工具搜索时别只往注解上方看，会漏读）。</li>
 *   <li><b>顾客自助端点</b>：<b>不要</b>依赖注解，必须在方法体内用
 *       {@code AuthContext.requireCustomerId()}（或 {@code requireStationId()}）
 *       <b>强制取当前身份</b>，绝不信任请求参数里的 customerId / stationId。</li>
 * </ul>
 * <p>归类标准很简单：<b>顾客小程序（miniapp-user）会不会调它</b>？会，就是第二类。
 * 顾客端一旦误调员工端点，必然 403，而小程序侧这类失败常被静默吞掉——
 * 详见 {@code StationController#getMyStation} 的注释（那里记录了三次真实事故）。</p>
 */
@Aspect
@Component
public class RequireRoleAspect {

    @Around("execution(public * com.example.aquaflow.controller..*.*(..))")
    public Object checkRole(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 方法级注解优先；未标注则回退到类级注解
        RequireRole requireRole = AnnotationUtils.findAnnotation(method, RequireRole.class);
        if (requireRole == null) {
            requireRole = AnnotationUtils.findAnnotation(signature.getDeclaringType(), RequireRole.class);
        }
        if (requireRole == null) {
            return joinPoint.proceed();
        }

        // 获取 JWT 中的角色（可能是 manager/delivery 或 STATION_MANAGER/DELIVERY）
        String userType = AuthContext.getUserType();
        String rawRole = AuthContext.getRole();

        // 构造允许角色集合（同时映射 数据库名/前端名）
        String[] allowedRoles = requireRole.value();
        Set<String> allowedSet = new HashSet<>();
        for (String r : allowedRoles) {
            allowedSet.add(r);                     // 原始值 STATION_MANAGER 等
            allowedSet.add(mapToFrontendRole(r));  // 映射值 manager 等
        }

        // [AQ-042] 原实现硬编码要求 userType=staff，导致标注了 "customer" 的接口把顾客全部拒掉
        // （订单图片上传/查看等）。改为按 userType 分派：顾客匹配 "customer"，员工匹配其角色。
        if ("customer".equals(userType)) {
            if (!containsIgnoreCase(allowedSet, "customer")) {
                throw new BusinessException("权限不足，当前角色：customer");
            }
            return joinPoint.proceed();
        }

        // 必须是员工类型
        if (!"staff".equals(userType)) {
            throw new BusinessException("仅员工或客户可访问此接口");
        }

        // 将前端角色名映射回数据库角色名（兼容两套命名）
        String role = mapToFrontendRole(rawRole);

        if (!allowedSet.contains(role)) {
            throw new BusinessException("权限不足，当前角色：" + rawRole);
        }

        // 权限通过，执行目标方法
        return joinPoint.proceed();
    }

    /** 集合中是否含某值（忽略大小写） */
    private boolean containsIgnoreCase(Set<String> set, String v) {
        for (String s : set) {
            if (s != null && s.equalsIgnoreCase(v)) return true;
        }
        return false;
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
