package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点异常处理配置（站长可改的桶异常补偿规则），一站一行。
 *
 * <p>三个 {@code json} 列在这里以<b>原始 JSON 文本</b>承载，序列化 / 反序列化统一放在
 * {@link com.example.aquaflow.service.impl.StationExceptionConfigServiceImpl} 内完成：
 * 本仓库没有为 JSON 列注册 TypeHandler，注解 SQL 直接读写字符串即可，MySQL 会自己做
 * JSON ↔ 字符串的转换（因此写库时传进来的字符串<b>必须是合法 JSON</b>，否则报 3140）。</p>
 */
@Data
public class StationExceptionConfig {

    /** 主键即站点 ID（无独立自增列，一站一行） */
    private Long stationId;

    /** 补偿优先级，如 {@code ["REFUND_TICKET","REFUND_CASH","WAIVE_DEPOSIT"]} */
    private String compensationPriority;

    /** 自动建议规则，如 {@code {"RETURN_SHORT":{"perBarrel":{"ticket":1,"cash":0}}} } */
    private String autoSuggestRules;

    /** 通知模板，如 {@code {"exception_created":"订单 #{orderId} 发生桶异常…"}} */
    private String notifyTemplates;

    private LocalDateTime updatedAt;
}
