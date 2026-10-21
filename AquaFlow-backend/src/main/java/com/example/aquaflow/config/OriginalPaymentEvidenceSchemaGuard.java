package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** v83只读结构护栏；不补写旧支付时间、商户映射或未知退款结果。 */
@Component
public class OriginalPaymentEvidenceSchemaGuard {
    private final JdbcTemplate jdbc;
    public OriginalPaymentEvidenceSchemaGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct public void verifySchema() {
        for(var table:Map.of("payment_original_success",Map.ofEntries(Map.entry("payment_id","bigint"),Map.entry("customer_id","bigint"),Map.entry("station_id","bigint"),Map.entry("amount","decimal(10,2)"),Map.entry("payer_total_cents","int"),Map.entry("success_epoch_second","bigint"),Map.entry("success_nano","int"),Map.entry("binding_digest","char(64)"),Map.entry("transaction_digest","char(64)"),Map.entry("request_digest","char(64)")),
                "payment_original_refund_hold",Map.of("payment_id","bigint","request_key","varchar(128)","binding_reference","varchar(128)","binding_digest","char(64)","original_transaction_reference","varchar(128)","original_amount","decimal(10,2)","amount","decimal(10,2)","currency","char(3)","request_digest","char(64)")).entrySet()) {
            if(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name=? and engine='InnoDB'",Integer.class,table.getKey())!=1)fail();
            for(var field:table.getValue().entrySet())if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name=? and column_name=? and column_type=? and is_nullable='NO'",Integer.class,table.getKey(),field.getKey(),field.getValue())!=1)fail();
        }
        for(var e:Map.of("payment_original_success",List.of("binding_digest","transaction_digest","request_digest"),"payment_original_refund_hold",List.of("request_key","binding_reference","binding_digest","original_transaction_reference","currency","request_digest")).entrySet())
            for(String column:e.getValue())if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name=? and column_name=? and collation_name='ascii_bin'",Integer.class,e.getKey(),column)!=1)fail();
        requireIndex("payment_original_success","PRIMARY","payment_id");requireIndex("payment_original_success","uk_original_success_tx","binding_digest,transaction_digest");
        requireIndex("payment_original_refund_hold","PRIMARY","payment_id,request_key");requireIndex("payment_original_refund_hold","uk_original_refund_request","binding_digest,request_key");
        for(var field:Map.of("recorded_epoch_second","bigint","recorded_nano","int").entrySet())if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='barrel_return_payout' and column_name=? and column_type=? and is_nullable='YES'",Integer.class,field.getKey(),field.getValue())!=1)fail();
    }
    private void requireIndex(String table,String name,String columns) {
        var row=jdbc.queryForMap("select group_concat(column_name order by seq_in_index) as cols,sum(non_unique) as nonunique,sum(sub_part is not null) as prefixed from information_schema.statistics where table_schema=database() and table_name=? and index_name=?",table,name);
        if(!columns.equals(row.get("cols")) || !(row.get("nonunique") instanceof Number n) || n.intValue()!=0 || !(row.get("prefixed") instanceof Number p) || p.intValue()!=0)fail();
    }
    private static void fail(){throw new IllegalStateException("C08原款时间/原请求证据缺完整v83结构；先备份核对安装，不可跳过护栏");}
}
