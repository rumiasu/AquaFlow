import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.*;
import com.example.aquaflow.service.impl.OrderBarrelExceptionServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

/** Standalone actual-mapper probe, requiring H2 2.3.232 on the runtime classpath.
 * Uses this JVM's synthetic memory database exclusively, never Spring application config or an external datasource. */
public class DeliveryReportSqlProbe {
    static int passed;
    static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++; System.out.println("PASS " + name);
    }
    static void identity() { AuthContext.set(new AuthContext.AuthUser(20L, "staff", "DELIVERY", 8L)); }
    public static void main(String[] args) throws Exception {
        var ds = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:delivery_report;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        for (String ddl : List.of(
                "create table orders(id bigint primary key,customer_id bigint,station_id bigint,delivery_station_id bigint,delivery_staff_id bigint,status int,special_note varchar(2000),exception_flag int default 0,exception_category varchar(32),exception_count int default 0,barrel_exception_id bigint,return_bucket_qty int,barrel_discrepancy int,update_time timestamp)",
                "create table order_barrel_exception(id bigint auto_increment primary key,order_id bigint,customer_id bigint,station_id bigint,delivery_staff_id bigint,delivery_qty int,return_qty int,discrepancy int,category varchar(32),type varchar(32),staff_action varchar(16),staff_note varchar(500),water_given int,water_owed int,manager_action varchar(16),refund_ticket_qty int,refund_cash_amount decimal(10,2),adjust_asset_qty int,adjust_product_id bigint,manager_note varchar(500),suggested_ticket_qty int,suggested_cash_amount decimal(10,2),status varchar(32),created_at timestamp)",
                "create table audit_log(id bigint auto_increment primary key,user_id int,username varchar(50),role varchar(30),module varchar(50),action varchar(50),target varchar(100),detail clob,ip varchar(50),create_time timestamp)",
                "create index audit_actor on audit_log(user_id)",
                "insert into orders(id,customer_id,station_id,delivery_staff_id,status) values(1,7,8,20,2)")) jdbc.execute(ddl);
        var config = new Configuration(new Environment("synthetic-memory-only", new SpringManagedTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OrderMapper.class); config.addMapper(OrderBarrelExceptionMapper.class); config.addMapper(AuditLogMapper.class);
        var template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        var actualOrders = template.getMapper(OrderMapper.class);
        var exceptions = template.getMapper(OrderBarrelExceptionMapper.class);
        var audit = new AuditLogService(); ReflectionTestUtils.setField(audit, "auditLogMapper", template.getMapper(AuditLogMapper.class));
        var firstLocked = new CountDownLatch(1); var secondAttempt = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1);
        var reads = new AtomicInteger();
        OrderMapper orders = (OrderMapper) Proxy.newProxyInstance(OrderMapper.class.getClassLoader(), new Class<?>[]{OrderMapper.class}, (proxy, method, arguments) -> {
            boolean first = false;
            if (method.getName().equals("getByIdForUpdate")) {
                int count = reads.incrementAndGet(); first = count == 1;
                if (count == 2) secondAttempt.countDown();
            }
            try {
                Object result = method.invoke(actualOrders, arguments);
                if (first) { firstLocked.countDown(); if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("first lock wait timed out"); }
                return result;
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
        var service = new OrderBarrelExceptionServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orders); ReflectionTestUtils.setField(service, "exceptionMapper", exceptions);
        ReflectionTestUtils.setField(service, "auditLogService", audit); ReflectionTestUtils.setField(service, "barrelPolicy", mock(BarrelBusinessPolicy.class));
        ReflectionTestUtils.setField(service, "alertService", mock(AlertService.class));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Callable<Long> retry = () -> {
            identity();
            try { return tx.execute(status -> service.recordDeliveryProblem(1L, "barrel_damaged", "现场说明", "[配送异常] 水桶破损", "DR-concurrent-001").getId()); }
            finally { AuthContext.clear(); }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(retry); check("first worker holds actual order lock", firstLocked.await(5, TimeUnit.SECONDS));
            var second = pool.submit(retry); check("second worker attempts the same lock before first commits", secondAttempt.await(5, TimeUnit.SECONDS));
            releaseFirst.countDown();
            check("concurrent retries return the same original exception id", first.get(10, TimeUnit.SECONDS).equals(second.get(10, TimeUnit.SECONDS)));
        } finally { releaseFirst.countDown(); pool.shutdownNow(); }
        check("one exception and one durable receipt survive concurrent attempts",
                jdbc.queryForObject("select count(*) from order_barrel_exception", Integer.class) == 1
                && jdbc.queryForObject("select count(*) from audit_log where module='DELIVERY_REPORT' and action='RECEIPT'", Integer.class) == 1);
        check("one note and one exception count are appended", jdbc.queryForObject("select special_note from orders where id=1", String.class).equals("[配送异常] 水桶破损")
                && jdbc.queryForObject("select exception_count from orders where id=1", Integer.class) == 1);
        identity();
        try {
            String target = jdbc.queryForObject("select target from audit_log", String.class);
            check("receipt lookup requires the original actor", tx.execute(status -> exceptions.reportReceiptsForUpdate(21L, target).isEmpty()));
            jdbc.update("update order_barrel_exception set status='IGNORED'");
            check("manager handling does not create a fresh pending report on retry", tx.execute(status -> service.recordDeliveryProblem(1L, "barrel_damaged", "现场说明", "[配送异常] 水桶破损", "DR-concurrent-001").getStatus()).equals("IGNORED"));
            tx.execute(status -> service.recordDeliveryProblem(1L, "barrel_damaged", "现场说明", "[配送异常] 水桶破损", "DR-new-event-002"));
            check("a new key allows the same reason to describe a later event", jdbc.queryForObject("select count(*) from order_barrel_exception", Integer.class) == 2);
            boolean conflict = false;
            try { tx.execute(status -> service.recordDeliveryProblem(1L, "other", "现场说明", "[配送异常] 水桶破损", "DR-concurrent-001")); }
            catch (com.example.aquaflow.exception.BusinessException expected) { conflict = true; }
            check("changed content under the original key is rejected without a new report", conflict && jdbc.queryForObject("select count(*) from order_barrel_exception", Integer.class) == 2);

            // The following fault injection affects only the synthetic in-memory tables.
            jdbc.update("delete from audit_log"); jdbc.update("delete from order_barrel_exception");
            jdbc.update("update orders set special_note=null,exception_count=0,barrel_exception_id=null");
            jdbc.execute("alter table audit_log add constraint reject_receipt check(action <> 'RECEIPT')");
            boolean failed = false;
            try { tx.execute(status -> service.recordDeliveryProblem(1L, "barrel_damaged", null, "原说明", "DR-rollback-003")); }
            catch (RuntimeException expected) { failed = true; }
            check("receipt persistence failure rolls exception note and marker back atomically", failed
                    && jdbc.queryForObject("select count(*) from order_barrel_exception", Integer.class) == 0
                    && jdbc.queryForObject("select special_note from orders where id=1", String.class) == null
                    && jdbc.queryForObject("select exception_count from orders where id=1", Integer.class) == 0);
            jdbc.execute("alter table audit_log drop constraint reject_receipt");
            long original = tx.execute(status -> service.recordDeliveryProblem(1L, "barrel_damaged", null, "原说明", "DR-rollback-003").getId());
            check("same original key can succeed after a fully rolled back attempt", original > 0 && jdbc.queryForObject("select count(*) from order_barrel_exception", Integer.class) == 1);
            jdbc.update("delete from order_barrel_exception where id=?", original);
            boolean missing = false;
            try { tx.execute(status -> service.recordDeliveryProblem(1L, "barrel_damaged", null, "原说明", "DR-rollback-003")); }
            catch (com.example.aquaflow.exception.BusinessException expected) { missing = true; }
            check("dangling receipt never creates a replacement exception", missing && jdbc.queryForObject("select count(*) from order_barrel_exception", Integer.class) == 0);
        } finally { AuthContext.clear(); }
        System.out.println("AQUAFLOW_DELIVERY_REPORT_SQL_OK " + passed);
    }
}
