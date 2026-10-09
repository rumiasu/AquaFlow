package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** 发布前核v78旁表；缺失时拒启，不自动执行迁移或把未知授权当免费安排。 */
@Component
public class CustomerConfirmationSchemaGuard {
    private final JdbcTemplate jdbc;
    public CustomerConfirmationSchemaGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct public void verifySchema() {
        var required=Map.of(
            "barrel_return_arrangement",Map.of("record_id","bigint","initial_pickup_mode","varchar","initial_companion_order_id","bigint","version","int","requires_confirmation","tinyint","confirmed_version","int"),
            "barrel_return_arrangement_change",Map.ofEntries(Map.entry("record_id","bigint"),Map.entry("version","int"),Map.entry("actor_key","varchar"),Map.entry("operator_id","bigint"),Map.entry("idempotency_key","varchar"),Map.entry("request_digest","varchar"),Map.entry("reason","varchar"),Map.entry("before_snapshot","json"),Map.entry("after_snapshot","json"),Map.entry("create_time","datetime")),
            "order_cancel_result",Map.of("request_id","bigint","result_note","varchar","automatic","tinyint","operator_id","bigint","create_time","datetime"));
        boolean valid=true;
        for(var table:required.entrySet()) {
            var columns=jdbc.queryForList("select column_name,data_type,is_nullable,collation_name from information_schema.columns where table_schema=database() and table_name=?",table.getKey());
            var engines=jdbc.queryForList("select engine from information_schema.tables where table_schema=database() and table_name=?",table.getKey());
            valid &= engines.size()==1 && "InnoDB".equalsIgnoreCase(String.valueOf(engines.get(0).get("engine")));
            for(var field:table.getValue().entrySet()) valid &= columns.stream().anyMatch(c->field.getKey().equals(c.get("column_name")) && field.getValue().equals(c.get("data_type"))
                    && (Set.of("initial_companion_order_id","confirmed_version").contains(field.getKey())?"YES":"NO").equals(c.get("is_nullable")));
            for(var c:columns) if(Set.of("actor_key","idempotency_key").contains(c.get("column_name")))valid &= "utf8mb4_bin".equals(c.get("collation_name"));
        }
        for(var index:List.of(List.of("barrel_return_arrangement","PRIMARY","record_id"),List.of("barrel_return_arrangement_change","PRIMARY","record_id","version"),List.of("barrel_return_arrangement_change","uk_return_arrangement_action","actor_key","record_id","idempotency_key"),List.of("order_cancel_result","PRIMARY","request_id"))) {
            var rows=jdbc.queryForList("select column_name,sub_part from information_schema.statistics where table_schema=database() and table_name=? and index_name=? and non_unique=0 order by seq_in_index",index.get(0),index.get(1));
            valid &= rows.stream().map(r->r.get("column_name")).toList().equals(index.subList(2,index.size())) && rows.stream().allMatch(r->r.get("sub_part")==null);
        }
        if(valid)valid=jdbc.queryForObject("select count(*) from barrel_return_detail d left join barrel_return_arrangement a on a.record_id=d.record_id where a.record_id is null",Integer.class)==0;
        if(!valid)throw new IllegalStateException("客户确认流程结构未就绪，请核实并安装 migration_v78_customer_confirmation_relief.sql，不要关闭保护继续运行");
    }
}
