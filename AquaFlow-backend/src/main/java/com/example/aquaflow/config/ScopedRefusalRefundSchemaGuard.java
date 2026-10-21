package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** v81只读结构护栏；缺结构拒启，不迁移或改历史退款。 */
@Component
public class ScopedRefusalRefundSchemaGuard {
    private final JdbcTemplate jdbc;
    public ScopedRefusalRefundSchemaGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct public void verifySchema() {
        for(var table:Map.of(
                "customer_refusal_freeze_scope",Map.of("order_id","bigint","product_id","bigint","amount","decimal(10,2)"),
                "barrel_return_payout",Map.ofEntries(Map.entry("id","bigint"),Map.entry("record_id","bigint"),Map.entry("actor_key","varchar(80)"),Map.entry("request_key","varchar(64)"),Map.entry("request_digest","char(64)"),Map.entry("amount","decimal(10,2)"),Map.entry("payer_id","bigint"),Map.entry("final_payout","tinyint"),Map.entry("create_time","datetime"))).entrySet()) {
            for(var field:table.getValue().entrySet())if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name=? and column_name=? and column_type=? and is_nullable='NO'",Integer.class,table.getKey(),field.getKey(),field.getValue())!=1)fail();
        }
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='barrel_return_payout' and column_name='id' and extra like '%auto_increment%'",Integer.class)!=1)fail();
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='customer_refusal_action' and column_name='scope_json' and data_type='longtext'",Integer.class)!=1)fail();
        for (String table:List.of("customer_refusal_freeze_scope","barrel_return_payout")) {
            if (jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name=? and engine='InnoDB'",Integer.class,table)!=1) fail();
        }
        requireIndex("customer_refusal_freeze_scope","PRIMARY","order_id,product_id");
        requireIndex("barrel_return_payout","PRIMARY","id");
        requireIndex("barrel_return_payout","uk_return_payout_key","record_id,request_key");
        for (var entry:Map.of("barrel_purchase_refund","uk_purchase_return","order_barrel_refund","uk_order_barrel_return").entrySet()) {
            String table=entry.getKey();
            if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name=? and column_name='payout_id' and column_type='bigint' and is_nullable='YES'",Integer.class,table)!=1)fail();
            var rows=jdbc.queryForList("select column_type,extra,generation_expression from information_schema.columns where table_schema=database() and table_name=? and column_name='legacy_return_record_id'",table);
            if(rows.size()!=1)fail();
            var row=rows.get(0);String expression=String.valueOf(row.get("GENERATION_EXPRESSION")).replaceAll("[`()\\s]","").toLowerCase(Locale.ROOT);
            if(!String.valueOf(row.get("COLUMN_TYPE")).equals("bigint") || !String.valueOf(row.get("EXTRA")).contains("STORED GENERATED")
                    || !Set.of("ifpayout_idisnull,return_record_id,null","ifisnullpayout_id,return_record_id,null").contains(expression))fail();
            requireIndex(table,entry.getValue(),"purchase_id,legacy_return_record_id");
            requireIndex(table,"uk_return_payout_source","purchase_id,return_record_id,payout_id");
        }
        for(String column:List.of("request_key","request_digest","actor_key")) {
            if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='barrel_return_payout' and column_name=? and is_nullable='NO' and collation_name='ascii_bin'",Integer.class,column)!=1)fail();
        }
    }
    private void requireIndex(String table,String name,String columns) {
        var rows=jdbc.queryForList("select group_concat(column_name order by seq_in_index) as cols,sum(non_unique) as nonunique,sum(sub_part is not null) as prefixed from information_schema.statistics where table_schema=database() and table_name=? and index_name=?",table,name);
        if(rows.size()!=1 || !columns.equals(rows.get(0).get("cols")) || ((Number)rows.get(0).get("nonunique")).intValue()!=0 || ((Number)rows.get(0).get("prefixed")).intValue()!=0)fail();
    }
    private static void fail() { throw new IllegalStateException("C03争议范围/实退凭据缺少完整v81结构；先备份核对并安装增量，不可跳过护栏"); }
}
