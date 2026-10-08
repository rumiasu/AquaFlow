package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Fail before exposing feedback queries on an incompatible v75 table. Never repairs a business DB. */
@Component
public class RefundFeedbackSchemaGuard {
    private final JdbcTemplate jdbc;
    public RefundFeedbackSchemaGuard(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @PostConstruct public void verifySchema() {
        var cols=jdbc.queryForList("select column_name,data_type,character_maximum_length,is_nullable,collation_name from information_schema.columns where table_schema=database() and table_name='feedback'");
        var unique=jdbc.queryForList("select column_name,sub_part from information_schema.statistics where table_schema=database() and table_name='feedback' and index_name='uk_feedback_refund_note' and non_unique=0 order by seq_in_index");
        var tables=jdbc.queryForList("select engine from information_schema.tables where table_schema=database() and table_name='feedback'");
        var types=Map.of("refund_type","varchar","refund_id","bigint","responsible_station_id","bigint","actor_key","varchar","idempotency_key","varchar","request_digest","varchar");
        boolean compatible=types.entrySet().stream().allMatch(e -> cols.stream().anyMatch(c ->
                e.getKey().equals(c.get("column_name")) && e.getValue().equals(c.get("data_type")) && "YES".equals(c.get("is_nullable"))));
        for (var e:Map.of("refund_type",24,"actor_key",40,"idempotency_key",64,"request_digest",64).entrySet()) {
            compatible &= cols.stream().anyMatch(c -> e.getKey().equals(c.get("column_name"))
                    && c.get("character_maximum_length") instanceof Number n && n.intValue()>=e.getValue()
                    && (!List.of("actor_key","idempotency_key").contains(e.getKey()) || "utf8mb4_bin".equals(c.get("collation_name"))));
        }
        var expected=List.of("actor_key","refund_type","refund_id","idempotency_key");
        compatible &= unique.size()==4 && unique.stream().map(c->c.get("column_name")).toList().equals(expected)
                && unique.stream().allMatch(c->c.get("sub_part")==null);
        compatible &= tables.size()==1 && "InnoDB".equalsIgnoreCase(String.valueOf(tables.get(0).get("engine")));
        if (!compatible) throw new IllegalStateException("退款说明结构未就绪；核实目标并备份后先安装 migration_v75_refund_feedback.sql，勿关闭保护继续运行");
    }
}
