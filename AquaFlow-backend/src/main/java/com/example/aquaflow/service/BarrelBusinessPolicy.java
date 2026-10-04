package com.example.aquaflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 新建业务的切换开关；历史单通过分配凭据识别，不随开关重新解释。 */
@Component
public class BarrelBusinessPolicy {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private boolean schemaInstalled;
    private boolean combinedSchemaInstalled;
    public boolean hasCombinedSchema() { return combinedSchemaInstalled; }
    @Value("${aquaflow.barrel.independent-rights-enabled:true}")
    private boolean enabled;
    public boolean isEnabled() { return enabled; }
    public boolean hasSchema() { return schemaInstalled; }
    @jakarta.annotation.PostConstruct
    public void verifySchema() {
        Integer count=jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('barrel_right_purchase','barrel_return_detail','barrel_return_lot_hold','barrel_purchase_refund','barrel_return_fee_refund','barrel_right_reservation','customer_refusal_case','consumption_refund','ticket_exit_refund','inter_station_recovery','dispatch_agreement')",Integer.class);
        schemaInstalled=count!=null && count==11;
        Integer combined=jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('order_barrel_purchase','order_barrel_refund')",Integer.class);
        Integer fields=jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='barrel_right_reservation' and column_name in ('pending_qty','pending_pickup_qty') and column_type='int' and is_nullable='NO'",Integer.class);
        combinedSchemaInstalled=combined!=null && combined==2 && fields!=null && fields==2;
        if(enabled && schemaInstalled && !combinedSchemaInstalled)throw new IllegalStateException("随单押金未安装完整 v74 结构，请先备份并安装增量迁移");
        if(enabled && !schemaInstalled) throw new IllegalStateException("独立桶权益未安装完整 v71 表结构；先关闭 INDEPENDENT_BARREL_RIGHTS_ENABLED，再按 sql/README.md 部署结构后启用");
    }
}
