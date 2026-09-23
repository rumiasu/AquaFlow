package com.example.aquaflow.aspect;

import com.example.aquaflow.annotation.RequireStation;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.AuthContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 水站归属校验 AOP 拦截器。
 * 拦截所有带 @RequireStation 注解的方法，校验当前登录员工请求中携带的 stationId
 * 必须与其登录水站一致，否则拒绝访问，从系统层面消除水平越权。
 */
@Aspect
@Component
public class RequireStationAspect {

    @Around("@annotation(com.example.aquaflow.annotation.RequireStation) || @within(com.example.aquaflow.annotation.RequireStation)")
    public Object checkStation(ProceedingJoinPoint joinPoint) throws Throwable {
        // 类级注解时，参数注入的注解可能为 null，需回退到类上查找
        RequireStation ann = getAnnotation(joinPoint);
        if (ann == null) {
            return joinPoint.proceed();
        }

        // 仅对员工（站长/配送员）生效；客户接口不应标注本注解
        if (!"staff".equals(AuthContext.getUserType())) {
            return joinPoint.proceed();
        }

        Long myStationId = AuthContext.getStationId();
        if (myStationId == null) {
            throw new BusinessException("当前账号未绑定水站，无法校验数据归属");
        }

        // 校验请求参数中的 stationId（查询参数 / 表单字段）是否与登录站一致
        HttpServletRequest request = currentRequest();
        if (request != null) {
            String param = request.getParameter("stationId");
            if (param != null && !param.trim().isEmpty()) {
                Long requested;
                try {
                    requested = Long.parseLong(param.trim());
                } catch (NumberFormatException e) {
                    throw new BusinessException("非法的 stationId 参数");
                }
                if (!requested.equals(myStationId)) {
                    throw new BusinessException("无权访问其它水站数据");
                }
            }
        }

        // [AQ-041] 补充：方法参数（@PathVariable / @RequestBody DTO 里的 stationId）此前完全不校验，
        // 只要不走 query/form 就绕过本注解。此处按参数名匹配 stationId 做一致性校验。
        checkStationIdInArgs(joinPoint, myStationId);

        return joinPoint.proceed();
    }

    /** [AQ-041] 校验方法参数中的 stationId（直接参数、或 @RequestBody DTO 的 stationId 字段） */
    private void checkStationIdInArgs(ProceedingJoinPoint joinPoint, Long myStationId) {
        org.aspectj.lang.reflect.MethodSignature sig =
                (org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (names == null || args == null) return;
        for (int i = 0; i < names.length && i < args.length; i++) {
            String name = names[i];
            Object val = args[i];
            if (val == null) continue;
            if ("stationId".equals(name)) {
                Long requested = toLong(val);
                if (requested != null && !requested.equals(myStationId)) {
                    throw new BusinessException("无权访问其它水站数据");
                }
            } else if (isBodyObject(val)) {
                // @RequestBody DTO：只校验名为 stationId 的字段（不碰 targetStationId 等目标站语义字段）
                Long requested = readStationIdField(val);
                if (requested != null && !requested.equals(myStationId)) {
                    throw new BusinessException("无权访问其它水站数据");
                }
            }
        }
    }

    private Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 非基础类型且非 Servlet/框架对象 → 视为 @RequestBody 载体 */
    private boolean isBodyObject(Object v) {
        Package p = v.getClass().getPackage();
        String pk = p != null ? p.getName() : "";
        return !(v instanceof Number || v instanceof CharSequence || v instanceof Boolean
                || v instanceof java.util.Collection || v instanceof java.util.Map)
                && !pk.startsWith("jakarta.") && !pk.startsWith("org.springframework.")
                && !pk.startsWith("java.");
    }

    /** 反射读取对象的 stationId 字段（兼容 getter） */
    private Long readStationIdField(Object obj) {
        Class<?> c = obj.getClass();
        for (String accessor : new String[]{"getStationId", "getStationID"}) {
            try {
                java.lang.reflect.Method m = c.getMethod(accessor);
                Object r = m.invoke(obj);
                if (r instanceof Number n) return n.longValue();
                if (r != null) return Long.parseLong(r.toString());
                return null;
            } catch (NoSuchMethodException ignored) {
                // 继续尝试字段
            } catch (Exception e) {
                return null;
            }
        }
        try {
            java.lang.reflect.Field f = c.getDeclaredField("stationId");
            f.setAccessible(true);
            Object r = f.get(obj);
            if (r instanceof Number n) return n.longValue();
            if (r != null) return Long.parseLong(r.toString());
        } catch (Exception ignored) {
        }
        return null;
    }

    private RequireStation getAnnotation(ProceedingJoinPoint joinPoint) {
        try {
            RequireStation direct = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature())
                    .getMethod().getAnnotation(RequireStation.class);
            if (direct != null) return direct;
            return AnnotationUtils.findAnnotation(
                    joinPoint.getSignature().getDeclaringType(), RequireStation.class);
        } catch (Exception e) {
            return AnnotationUtils.findAnnotation(
                    joinPoint.getSignature().getDeclaringType(), RequireStation.class);
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
