package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 随单退还只引用明确的实收凭据；启动仅核结构，不补写历史交接或执行迁移。 */
@Component
public class BarrelDeliveryReceiptSchemaGuard {
    private final JdbcTemplate jdbc;

    public BarrelDeliveryReceiptSchemaGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void verifySchema() {
        var tables = Map.of(
                "barrel_delivery_receipt", Map.of("record_id", "bigint", "input_kind", "varchar", "create_time", "datetime"),
                "barrel_return_delivery_allocation", Map.of("return_record_id", "bigint", "delivery_record_id", "bigint",
                        "quantity", "int", "operator_id", "bigint", "create_time", "datetime"));
        boolean valid = true;
        for (var table : tables.entrySet()) {
            var engines = jdbc.queryForList("select engine from information_schema.tables where table_schema=database() and table_name=?", table.getKey());
            valid &= engines.size() == 1 && "InnoDB".equalsIgnoreCase(String.valueOf(engines.get(0).get("engine")));
            var columns = jdbc.queryForList("select column_name,data_type,is_nullable,character_maximum_length,column_default from information_schema.columns where table_schema=database() and table_name=?", table.getKey());
            for (var field : table.getValue().entrySet()) {
                valid &= columns.stream().anyMatch(c -> field.getKey().equals(c.get("column_name"))
                        && field.getValue().equals(c.get("data_type")) && "NO".equals(c.get("is_nullable"))
                        && requiredWriteShape(field.getKey(), c));
            }
        }
        for (var index : List.of(List.of("barrel_delivery_receipt", "record_id"),
                List.of("barrel_return_delivery_allocation", "return_record_id"))) {
            var columns = jdbc.queryForList("select column_name,sub_part from information_schema.statistics where table_schema=database() and table_name=? and index_name='PRIMARY' and non_unique=0 order by seq_in_index", index.get(0));
            valid &= columns.size() == 1 && index.get(1).equals(columns.get(0).get("column_name"))
                    && columns.get(0).get("sub_part") == null;
        }
        if (!valid) throw new IllegalStateException("随单实收关联结构未就绪，请核实并安装 migration_v92_barrel_delivery_allocation.sql；不得回填历史交接或关闭校验继续运行");
    }

    /** 类型相同仍可能无法保存来源，或把凭据写成固定旧时间；只核 v92 实际写入依赖。 */
    private static boolean requiredWriteShape(String column, Map<String, Object> metadata) {
        if ("input_kind".equals(column)) {
            return metadata.get("character_maximum_length") instanceof Number length && length.longValue() == 32;
        }
        if ("create_time".equals(column)) {
            String defaultValue = String.valueOf(metadata.get("column_default"));
            return "CURRENT_TIMESTAMP".equalsIgnoreCase(defaultValue)
                    || "CURRENT_TIMESTAMP()".equalsIgnoreCase(defaultValue)
                    || "CURRENT_TIMESTAMP(0)".equalsIgnoreCase(defaultValue);
        }
        return true;
    }
}
