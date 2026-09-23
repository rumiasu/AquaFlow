package com.example.aquaflow.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 水站归属校验注解。
 * <p>标注在 Controller 类或方法上，由 {@code RequireStationAspect} 在运行时校验：
 * 当前登录员工（站长/配送员）请求中携带的 {@code stationId} 必须与其登录水站一致，
 * 否则一律拒绝。这从系统层面杜绝「改一行请求参数即可遍历其它水站数据」的水平越权。</p>
 * <p>仅对员工（userType=staff）生效；客户（customer）接口不应标注本注解，
 * 因为客户视角按自身客户身份归属，与水站归属是两套维度。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireStation {
}
