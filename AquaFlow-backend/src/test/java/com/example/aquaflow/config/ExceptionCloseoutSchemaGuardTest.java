package com.example.aquaflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 从本次DDL构造只读元数据，反向验证不完整/错误幂等结构必须拒启；不执行SQL或连接数据库。 */
class ExceptionCloseoutSchemaGuardTest {
    private final Map<String,List<Map<String,Object>>> columns=new HashMap<>(),indexes=new HashMap<>();
    private String engine="InnoDB";
    private ExceptionCloseoutSchemaGuard guard() throws Exception {
        String ddl=Files.readString(Path.of("sql/migration_v77_exception_closeout.sql"));
        for(String block:ddl.split("CREATE TABLE IF NOT EXISTS ")) {
            if(!block.startsWith("customer_") && !block.startsWith("refund_"))continue;
            String table=block.substring(0,block.indexOf(' ')); var list=new ArrayList<Map<String,Object>>();
            var fields=Pattern.compile("(?m)^  ([a-z_]+) (BIGINT|TINYINT|VARCHAR\\((\\d+)\\)|DATETIME)([^\\n]+)").matcher(block);
            while(fields.find()) {
                var row=new HashMap<String,Object>();row.put("column_name",fields.group(1));row.put("data_type",fields.group(2).split("\\(")[0].toLowerCase(Locale.ROOT));
                row.put("is_nullable",fields.group(4).contains("NOT NULL")?"NO":"YES");row.put("character_maximum_length",fields.group(3)==null?null:Integer.valueOf(fields.group(3)));
                row.put("collation_name",fields.group(4).contains("utf8mb4_bin")?"utf8mb4_bin":"utf8mb4_general_ci");list.add(row);
                row.put("column_default",fields.group(4).contains("DEFAULT 0")?"0":null);row.put("extra",fields.group(4).contains("AUTO_INCREMENT")?"auto_increment":"");
                if(fields.group(4).contains("PRIMARY KEY"))indexes.put(table+":PRIMARY",index(List.of(fields.group(1))));
            }
            columns.put(table,list);
            var keys=Pattern.compile("(?m)^  (PRIMARY KEY|UNIQUE KEY ([a-z_]+)) \\(([^)]+)\\)").matcher(block);
            while(keys.find())indexes.put(table+":"+(keys.group(2)==null?"PRIMARY":keys.group(2)),index(Arrays.asList(keys.group(3).split(","))));
        }
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);Object[] args=(Object[])call.getRawArguments()[1];String table=String.valueOf(args[0]);
            if(sql.contains("information_schema.columns"))return columns.getOrDefault(table,List.of());
            if(sql.contains("information_schema.statistics"))return indexes.getOrDefault(table+":"+args[1],List.of());
            return List.of(Map.of("engine",engine));
        });
        return new ExceptionCloseoutSchemaGuard(jdbc);
    }
    private static List<Map<String,Object>> index(List<String> names) {
        List<Map<String,Object>> rows=new ArrayList<>();for(String name:names){var row=new HashMap<String,Object>();row.put("column_name",name);row.put("sub_part",null);rows.add(row);}return rows;
    }
    @Test void completeMigrationMetadataIsAccepted() throws Exception {assertDoesNotThrow(guard()::verifySchema);}
    @Test void missingAuditColumnIsRejected() throws Exception {
        var guard=guard();columns.get("refund_dispute_action").removeIf(c->"reason".equals(c.get("column_name")));assertThrows(IllegalStateException.class,guard::verifySchema);
    }
    @Test void caseInsensitiveRequestKeysAreRejected() throws Exception {
        var guard=guard();columns.get("customer_refusal_action").stream().filter(c->"idempotency_key".equals(c.get("column_name"))).forEach(c->c.put("collation_name","utf8mb4_general_ci"));
        assertThrows(IllegalStateException.class,guard::verifySchema);
    }
    @Test void prefixUniqueIndexAndNonTransactionalStorageAreRejected() throws Exception {
        var guard=guard();indexes.get("refund_dispute_action:uk_refund_dispute_action").get(0).put("sub_part",8);assertThrows(IllegalStateException.class,guard::verifySchema);
        guard=guard();engine="MyISAM";assertThrows(IllegalStateException.class,guard::verifySchema);
    }
    @Test void wrongResolutionDefaultCannotRevokeHistoricalJudgmentsImplicitly() throws Exception {
        var guard=guard();columns.get("customer_refusal_resolution").stream().filter(c->"judgment_revoked".equals(c.get("column_name"))).forEach(c->c.put("column_default","1"));
        assertThrows(IllegalStateException.class,guard::verifySchema);
    }
}
