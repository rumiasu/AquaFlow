package com.example.aquaflow.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 执行真实护栏并提供 metadata 行；不执行 information_schema SQL 或迁移。 */
class PaymentSchemaGuardShapeTest {
    private static Map<String, Object> column(String table, String name, String type, String nullable, Integer len) {
        var row = new HashMap<String, Object>();
        row.put("table_name", table); row.put("column_name", name); row.put("data_type", type);
        row.put("is_nullable", nullable); row.put("character_maximum_length", len);
        row.put("collation_name", "utf8mb4_0900_ai_ci"); return row;
    }
    private static List<Map<String, Object>> columns() {
        return new ArrayList<>(List.of(column("payment_record", "purchase_request_digest", "varchar", "YES", 64),
                column("payment_record", "idempotency_key", "varchar", "YES", 64),
                column("ticket_purchase_fence", "customer_id", "bigint", "NO", null),
                column("ticket_purchase_fence", "idempotency_key", "varchar", "NO", 64),
                column("ticket_purchase_fence", "closed_time", "datetime", "YES", null)));
    }
    private static PaymentSchemaGuard guard(List<Map<String, Object>> columns, List<Map<String, Object>> keys, String engine) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("information_schema.columns")) return columns;
            if (sql.contains("information_schema.statistics")) return keys;
            return List.of(Map.of("table_name", "payment_record", "engine", "InnoDB"),
                    Map.of("table_name", "ticket_purchase_fence", "engine", engine));
        });
        return new PaymentSchemaGuard(jdbc);
    }
    private static List<Map<String, Object>> keys() {
        return new ArrayList<>(List.of(new HashMap<>(Map.of("column_name", "customer_id", "seq_in_index", 1)),
                new HashMap<>(Map.of("column_name", "idempotency_key", "seq_in_index", 2))));
    }
    @Test void correctNullableDigestAndFencePass() { assertDoesNotThrow(() -> guard(columns(), keys(), "InnoDB").verifySchema()); }
    @ParameterizedTest @ValueSource(strings = {"missing", "short", "type", "not-null"})
    void incompatibleDigestMustReject(String broken) {
        var rows = columns();
        switch (broken) {
            case "missing" -> rows.remove(0);
            case "short" -> rows.get(0).put("character_maximum_length", 63);
            case "type" -> rows.get(0).put("data_type", "char");
            case "not-null" -> rows.get(0).put("is_nullable", "NO");
        }
        assertThrows(IllegalStateException.class, () -> guard(rows, keys(), "InnoDB").verifySchema());
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "nullable-customer", "nullable-key", "short-key", "collation", "closed-not-null", "closed-type"})
    void incompatibleFenceMustReject(String broken) {
        var rows = columns();
        switch (broken) {
            case "missing" -> rows.removeIf(r -> r.get("table_name").equals("ticket_purchase_fence"));
            case "nullable-customer" -> rows.get(2).put("is_nullable", "YES");
            case "nullable-key" -> rows.get(3).put("is_nullable", "YES");
            case "short-key" -> rows.get(3).put("character_maximum_length", 32);
            case "collation" -> rows.get(3).put("collation_name", "utf8mb4_bin");
            case "closed-not-null" -> rows.get(4).put("is_nullable", "NO");
            case "closed-type" -> rows.get(4).put("data_type", "varchar");
        }
        assertThrows(IllegalStateException.class, () -> guard(rows, keys(), "InnoDB").verifySchema());
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "single", "extra", "prefix", "reversed"})
    void incompleteOrNonUniqueFencePrimaryRejects(String broken) {
        var rows = keys();
        switch (broken) {
            case "missing" -> rows.clear();
            case "single" -> rows.remove(1);
            case "extra" -> rows.add(Map.of("column_name", "extra", "seq_in_index", 3));
            case "prefix" -> rows.get(1).put("sub_part", 8);
            case "reversed" -> Collections.reverse(rows);
        }
        assertThrows(IllegalStateException.class, () -> guard(columns(), rows, "InnoDB").verifySchema());
    }
    @Test void nonTransactionalFenceEngineRejects() {
        assertThrows(IllegalStateException.class, () -> guard(columns(), keys(), "MyISAM").verifySchema());
    }
}
