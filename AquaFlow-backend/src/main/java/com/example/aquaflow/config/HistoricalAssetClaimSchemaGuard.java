package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** 缺 v80 或防重键被改写时拒启；不自动迁移。 */
@Component
public class HistoricalAssetClaimSchemaGuard {
    private final JdbcTemplate jdbc;
    public HistoricalAssetClaimSchemaGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct public void verifySchema() {
        var required=Map.ofEntries(Map.entry("id","bigint"),Map.entry("customer_id","bigint"),Map.entry("station_id","bigint"),
            Map.entry("client_token","varchar"),Map.entry("request_digest","varchar"),Map.entry("request_json","json"),
            Map.entry("status","varchar"),Map.entry("reviewer_id","bigint"),Map.entry("review_note","varchar"),
            Map.entry("create_time","datetime"),Map.entry("review_time","datetime"),Map.entry("active_customer_id","bigint"));
        var columns=jdbc.queryForList("select column_name,data_type,is_nullable,collation_name,generation_expression,extra,character_maximum_length from information_schema.columns where table_schema=database() and table_name='historical_asset_claim'");
        boolean valid=jdbc.queryForList("select engine from information_schema.tables where table_schema=database() and table_name='historical_asset_claim'")
            .stream().anyMatch(r->"InnoDB".equals(r.get("engine")));
        for(var field:required.entrySet()) valid &= columns.stream().anyMatch(c->field.getKey().equals(c.get("column_name"))&&field.getValue().equals(c.get("data_type"))
            &&(Set.of("reviewer_id","review_note","review_time","active_customer_id").contains(field.getKey())?"YES":"NO").equals(c.get("is_nullable")));
        for(var c:columns) {
            String name=String.valueOf(c.get("column_name"));
            if(Set.of("client_token","request_digest","status").contains(name)) valid &= "utf8mb4_bin".equals(c.get("collation_name"));
            if(Set.of("client_token","request_digest").contains(name)) valid &= Long.valueOf(64).equals(c.get("character_maximum_length"));
            if("id".equals(name)) valid &= String.valueOf(c.get("extra")).contains("auto_increment");
            if("status".equals(name)) valid &= Long.valueOf(16).equals(c.get("character_maximum_length"));
            if("review_note".equals(name)) valid &= Long.valueOf(500).equals(c.get("character_maximum_length"));
            if("active_customer_id".equals(name)) {
                String expression=canonicalExpression(String.valueOf(c.get("generation_expression")));
                boolean expected=false;
                // MySQL 按建表会话编码打印常量引入符；只接受完整已知表达式，不能删改常量内容。
                for(String charset:List.of("","_utf8mb4","_latin1")) for(String quote:List.of("'","\\'")) {
                    String pending=charset+quote+"PENDING"+quote,approved=charset+quote+"APPROVED"+quote;
                    expected |= expression.equals("(casewhen(statusin("+pending+","+approved+"))thencustomer_idelsenullend)");
                }
                valid &= expected&&String.valueOf(c.get("extra")).contains("STORED GENERATED");
            }
        }
        for(var key:List.of(List.of("PRIMARY","id"),List.of("uk_asset_claim_token","customer_id","client_token"),List.of("uk_asset_claim_once","station_id","active_customer_id"))) {
            var rows=jdbc.queryForList("select column_name,sub_part from information_schema.statistics where table_schema=database() and table_name='historical_asset_claim' and index_name=? and non_unique=0 order by seq_in_index",key.get(0));
            valid &= rows.stream().map(r->r.get("column_name")).toList().equals(key.subList(1,key.size()))&&rows.stream().allMatch(r->r.get("sub_part")==null);
        }
        if(!valid) throw new IllegalStateException("历史资产认领结构不完整，请核对并手工执行 sql/migration_v80_historical_asset_claim.sql");
    }

    private String canonicalExpression(String sql) {
        var result=new StringBuilder();boolean quoted=false;
        for(char c:sql.toCharArray()) {
            if(c=='\'') { quoted=!quoted;result.append(c); }
            else if(quoted) result.append(c); // 保留常量的大小写、空格、反引号，不能把错误状态归一成正确状态。
            else if(c!='`'&&!Character.isWhitespace(c)) result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }
}
