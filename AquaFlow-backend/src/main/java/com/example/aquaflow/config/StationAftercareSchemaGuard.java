package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** v82只读拒启护栏，不迁移或重写存量责任事实。 */
@Component
public class StationAftercareSchemaGuard {
    private final JdbcTemplate jdbc;
    public StationAftercareSchemaGuard(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @PostConstruct public void verifySchema() {
        for(var e:Map.of("station_aftercare",List.of("refund_payment_id","order_id","refund_station_id","other_station_id","refund_amount","proposed_amount","proposed_station_id","proposed_by","reason","status","version","agreed_by","agreed_time","paid_by","paid_time","create_time","update_time"),
                "station_aftercare_action",List.of("id","refund_payment_id","actor_key","request_key","request_digest","action","amount","reason","from_version","to_version","create_time")).entrySet()) {
            if(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name=? and engine='InnoDB'",Integer.class,e.getKey())!=1)fail();
            for(String column:e.getValue())if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name=? and column_name=?",Integer.class,e.getKey(),column)!=1)fail();
        }
        for(String column:List.of("request_key","request_digest","actor_key","action"))if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='station_aftercare_action' and column_name=? and collation_name='ascii_bin' and is_nullable='NO'",Integer.class,column)!=1)fail();
        for(var table:Map.of("station_aftercare",Map.of("refund_payment_id","bigint","order_id","bigint","refund_station_id","bigint","other_station_id","bigint","refund_amount","decimal(10,2)","proposed_amount","decimal(10,2)","version","bigint"),
                "station_aftercare_action",Map.of("id","bigint","refund_payment_id","bigint","amount","decimal(10,2)","from_version","bigint","to_version","bigint")).entrySet())
            for(var field:table.getValue().entrySet())if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name=? and column_name=? and column_type=? and is_nullable='NO'",Integer.class,table.getKey(),field.getKey(),field.getValue())!=1)fail();
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='station_aftercare_action' and column_name='id' and extra like '%auto_increment%'",Integer.class)!=1)fail();
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='station_aftercare' and column_name='status' and column_type='varchar(16)' and collation_name='ascii_bin' and is_nullable='NO'",Integer.class)!=1)fail();
        requireIndex("station_aftercare","PRIMARY","refund_payment_id");requireIndex("station_aftercare_action","PRIMARY","id");requireIndex("station_aftercare_action","uk_aftercare_action","refund_payment_id,actor_key,request_key");
    }
    private void requireIndex(String table,String name,String columns) {
        var rows=jdbc.queryForList("select group_concat(column_name order by seq_in_index) as cols,sum(non_unique) as nonunique,sum(sub_part is not null) as prefixed from information_schema.statistics where table_schema=database() and table_name=? and index_name=?",table,name);
        if(rows.size()!=1 || !columns.equals(rows.get(0).get("cols")) || ((Number)rows.get(0).get("nonunique")).intValue()!=0 || ((Number)rows.get(0).get("prefixed")).intValue()!=0)fail();
    }
    private static void fail() {throw new IllegalStateException("C04责任凭据缺少完整v82结构；先备份核对安装，不可绕过护栏");}
}
