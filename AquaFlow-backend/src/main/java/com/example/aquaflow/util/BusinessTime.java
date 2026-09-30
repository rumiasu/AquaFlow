package com.example.aquaflow.util;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 业务时间的唯一取用口（F-16）。
 *
 * <p>把 {@link Clock} 收在这一个 util Bean 里，service / controller 只依赖它，
 * 业务代码不再直连 {@code LocalDate.now()} / {@code LocalDateTime.now()}。
 * 直连的两种代价：跨零点时"今天/本月"前后不一致（23:59 算出的端点 + 00:00 写入的数据），
 * 以及口径在测试里无法固定。为什么放在 util 而不是各 Controller 自己注入 {@code Clock}：
 * 分层门禁要求 Controller 保持"认证 + DTO 校验 + 调服务"的单薄形态，
 * 时间源这类基础设施依赖统一从这里取（见 {@code config/ClockConfig}）。</p>
 *
 * <p>⚠️ <b>本类只负责"取现在"，不参与任何口径</b>：算法（例如账期"当月最后一天 + N 天"、
 * 时间区间的"结束日 + 1 天"排他上界）一律留在原处照搬，<b>不要</b>顺手在这里做归一化 ——
 * 那是改业务口径，不是可测性改造。</p>
 *
 * <p>⚠️ 也<b>不要</b>拿它去替换 SQL 侧的 {@code NOW()} / {@code CURDATE()}
 * （实体 {@code create_time} 由 Java 补全的地方本就属于"时间戳"，照旧即可）。</p>
 */
@Component
public class BusinessTime {

    private final Clock clock;

    public BusinessTime(Clock clock) {
        this.clock = clock;
    }

    /**
     * 业务"今天"。
     *
     * <p>⚠️ 它是 {@code LocalDate}，写进 SQL 比较 datetime 列时等价于"当天 00:00:00"——
     * 按"某天（含）"筛的区间上界必须再 +1 天（AGENTS §8.19 的坑）。本类不代劳这件事。</p>
     */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 业务"此刻"。用于确实需要时间点的口径（如对账结果的 checkedAt）。 */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
