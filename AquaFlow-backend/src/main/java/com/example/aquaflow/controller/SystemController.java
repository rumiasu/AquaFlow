package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统存活探针 —— {@code GET /api/system/health}（F-46，2026-09-30 第六批）。
 *
 * <p><b>归属与调用方</b>：不是业务端点，顾客端 / 员工端小程序都不调它（也调不了 ——
 * 它没有业务数据）。调用方是网关/容器 liveness 探针与 {@code scripts/smoke-check.js}
 * 的存活判据。
 *
 * <p><b>为什么不许拿别的端点凑合</b>：本仓业务错与系统错都包成 HTTP 200
 * （{@code GlobalExceptionHandler}，业务契约、全仓小程序按 code 判错 —— <b>不许改</b>）
 * ⇒ 任何"非 5xx 即健康"的外部探针在别处<b>恒绿</b>；而此前唯一能反推故障的
 * {@code /api/stations/public} 又依赖数据库。于是三态要这样分层：
 * <ul>
 *   <li>本端点（不查库）：进程活着 → HTTP 200 + code=0；</li>
 *   <li>{@code /api/stations/public} 的 {@code code=0}：连得上库；</li>
 *   <li>其余业务 code：业务语义，与探针无关。</li>
 * </ul>
 *
 * <p><b>免认证</b>：见 {@code WebMvcConfig} 的 {@code excludePathPatterns}（探针不可能带 token）。
 * 无 {@code @RequireRole} 注解 = 切面按"无注解即放行"处理，此处<b>有意</b>公开 ——
 * 返回值只有固定状态串，无任何租户数据。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /**
     * 存活探针。固定结构、不查库、无 I/O —— 进程活着就必定应答。
     * 若将来要加"就绪"语义（查库），请另开 {@code /api/system/ready}，别把本端点变重：
     * 它变重的那天，liveness 探针会因为数据库抖动重启整个进程。
     */
    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of("status", "UP"));
    }
}
