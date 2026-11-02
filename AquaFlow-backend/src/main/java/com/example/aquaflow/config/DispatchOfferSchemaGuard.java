package com.example.aquaflow.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only deployment guard; install v88 explicitly before starting the matching backend. */
@Component
public class DispatchOfferSchemaGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public DispatchOfferSchemaGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public void run(ApplicationArguments args) {
        Integer tables=jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='dispatch_agreement'",Integer.class);
        if (tables == null || tables == 0) return; // The disabled pre-v71 model has no dispatch-agreement commands.
        Integer count=jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='dispatch_agreement' "
                + "and column_name='offer_version' and data_type='bigint' and is_nullable='NO' and column_default='1'",Integer.class);
        if (count == null || count != 1) throw new IllegalStateException("请先备份并核对 v88 外派报价版本迁移，再启动配套后台；不会自动修改数据库");
    }
}
