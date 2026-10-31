package com.example.aquaflow.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 先安装可空成本快照列，再启用配套代码；绝不在业务库自动DDL。 */
@Component
public class InventoryCostSchemaGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public InventoryCostSchemaGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void run(ApplicationArguments args) {
        Integer n = jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() "
                + "and table_name in ('inventory','inventory_record') and column_name='cost_amount' "
                + "and data_type='decimal' and numeric_precision=18 and numeric_scale=2 and is_nullable='YES'", Integer.class);
        if (n == null || n != 2) throw new IllegalStateException("先核对并完成 v87 库存成本快照迁移，再启动配套后台；不会自动修改数据库");
    }
}
