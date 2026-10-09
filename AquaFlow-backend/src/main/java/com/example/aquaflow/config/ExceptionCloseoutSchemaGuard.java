package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** 异常读写发布前核v77结构；不自动修库，也不能关闭保护冒称无异常。 */
@Component
public class ExceptionCloseoutSchemaGuard {
    private final JdbcTemplate jdbc;
    public ExceptionCloseoutSchemaGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct public void verifySchema() {
        var required=Map.of(
            "customer_refusal_resolution",Map.of("order_id","bigint","judgment_revoked","tinyint","asset_freeze_released","tinyint","version","bigint"),
            "customer_refusal_action",Map.ofEntries(Map.entry("id","bigint"),Map.entry("order_id","bigint"),Map.entry("station_id","bigint"),Map.entry("operator_id","bigint"),Map.entry("actor_key","varchar"),Map.entry("action","varchar"),Map.entry("reason","varchar"),Map.entry("idempotency_key","varchar"),Map.entry("request_digest","varchar"),Map.entry("from_version","bigint"),Map.entry("to_version","bigint"),Map.entry("create_time","datetime")),
            "refund_dispute",Map.of("refund_type","varchar","refund_id","bigint","customer_id","bigint","responsible_station_id","bigint","status","varchar","version","bigint","last_result","varchar","update_time","datetime"),
            "refund_dispute_action",Map.ofEntries(Map.entry("id","bigint"),Map.entry("refund_type","varchar"),Map.entry("refund_id","bigint"),Map.entry("customer_id","bigint"),Map.entry("responsible_station_id","bigint"),Map.entry("actor_key","varchar"),Map.entry("operator_id","bigint"),Map.entry("action","varchar"),Map.entry("reason","varchar"),Map.entry("idempotency_key","varchar"),Map.entry("request_digest","varchar"),Map.entry("from_version","bigint"),Map.entry("to_version","bigint"),Map.entry("create_time","datetime")));
        boolean valid=true;
        for(var table:required.entrySet()) {
            var columns=jdbc.queryForList("select column_name,data_type,is_nullable,character_maximum_length,collation_name,column_default,extra from information_schema.columns where table_schema=database() and table_name=?",table.getKey());
            var engines=jdbc.queryForList("select engine from information_schema.tables where table_schema=database() and table_name=?",table.getKey());
            valid &= engines.size()==1 && "InnoDB".equalsIgnoreCase(String.valueOf(engines.get(0).get("engine")));
            for(var field:table.getValue().entrySet())valid &= columns.stream().anyMatch(c->field.getKey().equals(c.get("column_name")) && field.getValue().equals(c.get("data_type"))
                    && ("last_result".equals(field.getKey())?"YES":"NO").equals(c.get("is_nullable")));
            for(var c:columns) {
                String name=String.valueOf(c.get("column_name"));
                int min=switch(name){case "reason","last_result"->1000;case "actor_key","idempotency_key","request_digest"->64;case "refund_type","action"->24;case "status"->16;default->0;};
                if(min>0)valid &= c.get("character_maximum_length") instanceof Number n && n.intValue()>=min;
                if(Set.of("actor_key","idempotency_key").contains(name))valid &= "utf8mb4_bin".equals(c.get("collation_name"));
                if("customer_refusal_resolution".equals(table.getKey()) && Set.of("judgment_revoked","asset_freeze_released","version").contains(name))
                    valid &= "0".equals(String.valueOf(c.get("column_default")));
                if("id".equals(name))valid &= String.valueOf(c.get("extra")).contains("auto_increment");
            }
        }
        for(var index:List.of(
                List.of("customer_refusal_resolution","PRIMARY","order_id"),List.of("customer_refusal_action","PRIMARY","id"),
                List.of("customer_refusal_action","uk_refusal_action","actor_key","order_id","idempotency_key"),
                List.of("refund_dispute","PRIMARY","refund_type","refund_id"),List.of("refund_dispute_action","PRIMARY","id"),
                List.of("refund_dispute_action","uk_refund_dispute_action","actor_key","refund_type","refund_id","idempotency_key"))) {
            var rows=jdbc.queryForList("select column_name,sub_part from information_schema.statistics where table_schema=database() and table_name=? and index_name=? and non_unique=0 order by seq_in_index",index.get(0),index.get(1));
            valid &= rows.stream().map(r->r.get("column_name")).toList().equals(index.subList(2,index.size())) && rows.stream().allMatch(r->r.get("sub_part")==null);
        }
        if(!valid)throw new IllegalStateException("异常结案结构未就绪；核实目标并备份后先安装 migration_v77_exception_closeout.sql，勿关闭保护继续运行");
    }
}
