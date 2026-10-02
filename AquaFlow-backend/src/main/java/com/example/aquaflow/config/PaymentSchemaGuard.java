package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** v72/v73 必须先安装；缺编号封锁结构时不得开放购票写入。 */
@Component
public class PaymentSchemaGuard {
    private final JdbcTemplate jdbc;
    public PaymentSchemaGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void verifySchema() {
        var columns = jdbc.queryForList("select table_name,column_name,data_type,character_maximum_length,is_nullable,collation_name "
                + "from information_schema.columns where table_schema=database() "
                + "and table_name in ('payment_record','ticket_purchase_fence')");
        var digest = column(columns, "payment_record", "purchase_request_digest");
        if (!shape(digest, "varchar", "YES") || length(digest) < 64) {
            throw new IllegalStateException("购票请求结构未就绪；备份并核实目标后先安装 migration_v72_ticket_purchase_intent.sql");
        }
        var customer = column(columns, "ticket_purchase_fence", "customer_id");
        var key = column(columns, "ticket_purchase_fence", "idempotency_key");
        var closed = column(columns, "ticket_purchase_fence", "closed_time");
        var paymentKey = column(columns, "payment_record", "idempotency_key");
        var keys = jdbc.queryForList("select column_name,seq_in_index,sub_part from information_schema.statistics "
                + "where table_schema=database() and table_name='ticket_purchase_fence' and index_name='PRIMARY' and non_unique=0 "
                + "order by seq_in_index");
        var tables = jdbc.queryForList("select table_name,engine from information_schema.tables "
                + "where table_schema=database() and table_name in ('payment_record','ticket_purchase_fence')");
        boolean engines = tables.size() == 2 && tables.stream().allMatch(t -> "InnoDB".equalsIgnoreCase(text(t, "engine")));
        boolean primary = keys.size() == 2 && "customer_id".equals(text(keys.get(0), "column_name"))
                && "idempotency_key".equals(text(keys.get(1), "column_name"))
                && keys.stream().allMatch(k -> k.get("sub_part") == null);
        // 2026-10-02：同名表、前缀唯一键或不同排序规则会放行迟到编号；不静默修补已有结构。
        if (!shape(customer, "bigint", "NO") || !shape(key, "varchar", "NO") || length(key) != 64
                || !shape(closed, "datetime", "YES") || text(key, "collation_name").isEmpty()
                || !text(key, "collation_name").equals(text(paymentKey, "collation_name")) || !primary || !engines) {
            throw new IllegalStateException("购票编号保护未就绪；备份并核实目标后先安装 migration_v73_ticket_purchase_fence.sql");
        }
    }

    private static java.util.Map<String, Object> column(java.util.List<java.util.Map<String, Object>> columns,
                                                       String table, String name) {
        return columns.stream().filter(c -> table.equals(text(c, "table_name")) && name.equals(text(c, "column_name")))
                .findFirst().orElse(java.util.Map.of());
    }

    private static String text(java.util.Map<String, Object> row, String name) {
        Object value = row.get(name);
        return value == null ? "" : value.toString();
    }

    private static boolean shape(java.util.Map<String, Object> row, String type, String nullable) {
        return type.equals(text(row, "data_type")) && nullable.equals(text(row, "is_nullable"));
    }

    private static long length(java.util.Map<String, Object> row) {
        Object value = row.get("character_maximum_length");
        return value instanceof Number n ? n.longValue() : -1;
    }
}
