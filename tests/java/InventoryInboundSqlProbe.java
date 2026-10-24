import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.*;
import com.example.aquaflow.service.impl.InventoryServiceImpl;
import com.example.aquaflow.service.impl.InventoryLedgerServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.mock;

/** Actual production SQL/service with a synthetic in-memory H2 database only.
 * Requires H2 2.3.232; never loads application configuration or any external database. */
public class InventoryInboundSqlProbe {
    static int passed;
    static void check(String name, boolean value) { if (!value) throw new AssertionError(name); passed++; System.out.println("PASS " + name); }
    static void identity() { AuthContext.set(new AuthContext.AuthUser(20L,"staff","STATION_MANAGER",8L)); }
    static List<InventoryInboundDTO.ItemDTO> items(int quantity) {
        var item = new InventoryInboundDTO.ItemDTO(); item.setProductId(42L); item.setQuantity(quantity); return List.of(item);
    }
    public static void main(String[] args) throws Exception {
        var ds = new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:inbound_receipt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        var jdbc = new JdbcTemplate(ds);
        for (String ddl : List.of(
                "create table station(id bigint primary key)",
                "create table product(id bigint primary key,owner_station_id bigint)",
                "create table inventory(id bigint auto_increment primary key,station_id bigint,product_id bigint,quantity int,enabled int,ticket_enabled int,ticket_price decimal(10,2),priority_display int,create_time timestamp,update_time timestamp,unique(station_id,product_id))",
                "create table inventory_record(id bigint auto_increment primary key,station_id bigint,product_id bigint,delta int,type varchar(32),ref_id bigint,operator_id bigint,note varchar(500),create_time timestamp)",
                "create table audit_log(id int auto_increment primary key,user_id int,username varchar(50),role varchar(20),module varchar(50),action varchar(50),target varchar(100),detail clob,ip varchar(50),create_time timestamp)",
                "insert into station values(8)","insert into product values(42,null)",
                "insert into inventory(station_id,product_id,quantity) values(8,42,10)")) jdbc.execute(ddl);
        var config = new Configuration(new Environment("synthetic-memory-only",new SpringManagedTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(StationMapper.class, ProductMapper.class, InventoryMapper.class, InventoryRecordMapper.class, AuditLogMapper.class)) config.addMapper(mapper);
        var template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        var firstLocked = new CountDownLatch(1); var secondAttempt = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1);
        var reads = new AtomicInteger(); var actualStations = template.getMapper(StationMapper.class);
        StationMapper stations = (StationMapper) Proxy.newProxyInstance(StationMapper.class.getClassLoader(),new Class<?>[]{StationMapper.class},(proxy,method,arguments)->{
            int count = method.getName().equals("getByIdForUpdate") ? reads.incrementAndGet() : -1;
            if (count == 2) secondAttempt.countDown();
            try { Object value = method.invoke(actualStations,arguments); if (count == 1) { firstLocked.countDown(); if (!releaseFirst.await(5,TimeUnit.SECONDS)) throw new AssertionError("first release"); } return value; }
            catch (InvocationTargetException e) { throw e.getCause(); }
        });
        var service = new InventoryServiceImpl();
        var ledger = new InventoryLedgerServiceImpl(); ReflectionTestUtils.setField(ledger,"inventoryRecordMapper",template.getMapper(InventoryRecordMapper.class));
        var audit = new AuditLogService(); ReflectionTestUtils.setField(audit,"auditLogMapper",template.getMapper(AuditLogMapper.class));
        ReflectionTestUtils.setField(service,"stationMapper",stations);
        ReflectionTestUtils.setField(service,"productMapper",template.getMapper(ProductMapper.class));
        ReflectionTestUtils.setField(service,"inventoryMapper",template.getMapper(InventoryMapper.class));
        ReflectionTestUtils.setField(service,"inventoryLedgerService",ledger);
        ReflectionTestUtils.setField(service,"inventoryReservationService",mock(InventoryReservationService.class));
        ReflectionTestUtils.setField(service,"auditLogMapper",template.getMapper(AuditLogMapper.class));
        ReflectionTestUtils.setField(service,"auditLogService",audit);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        String key = "INB-concurrent-request-0001";
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Map<String,Object>> command = ()->{identity();try{return tx.execute(status->service.inboundWithReceipt(8L,items(3),key));}finally{AuthContext.clear();}};
            var a = pool.submit(command); check("first request obtains the station lock",firstLocked.await(5,TimeUnit.SECONDS));
            var b = pool.submit(command); check("second same-key request attempts the same station lock",secondAttempt.await(5,TimeUnit.SECONDS));
            releaseFirst.countDown(); var receiptA = a.get(8,TimeUnit.SECONDS); var receiptB = b.get(8,TimeUnit.SECONDS);
            check("concurrent same-key calls return the same original applied receipt",receiptA.equals(receiptB)&&"APPLIED".equals(receiptA.get("status")));
            check("actual SQL increments stock only once",jdbc.queryForObject("select quantity from inventory where station_id=8 and product_id=42",Integer.class)==13);
            check("one actual inventory ledger and one receipt commit",jdbc.queryForObject("select count(*) from inventory_record",Integer.class)==1&&jdbc.queryForObject("select count(*) from audit_log",Integer.class)==1);
            identity();
            boolean changed = false; try { tx.execute(status->service.inboundWithReceipt(8L,items(4),key)); } catch(BusinessException expected){changed=true;}
            check("same original key with another quantity is rejected",changed&&jdbc.queryForObject("select quantity from inventory",Integer.class)==13);
        } finally { releaseFirst.countDown(); pool.shutdownNow(); }
        try {
            identity();
            jdbc.execute("alter table audit_log add constraint reject_new_receipt check (detail <> 'v1|8|" + digest("42:4;") + "')");
            boolean failed = false; try { tx.execute(status->service.inboundWithReceipt(8L,items(4),"INB-rollback-request-0002")); } catch(RuntimeException expected){failed=true;}
            check("receipt insert failure rolls stock and ledger back together",failed&&jdbc.queryForObject("select quantity from inventory",Integer.class)==13&&jdbc.queryForObject("select count(*) from inventory_record",Integer.class)==1&&jdbc.queryForObject("select count(*) from audit_log",Integer.class)==1);
            jdbc.execute("alter table audit_log drop constraint reject_new_receipt");
            tx.execute(status->service.inboundWithReceipt(8L,items(4),"INB-rollback-request-0002"));
            check("rolled-back original key can be safely retried once",jdbc.queryForObject("select quantity from inventory",Integer.class)==17&&jdbc.queryForObject("select count(*) from inventory_record",Integer.class)==2);
            jdbc.update("update audit_log set detail='corrupt' where id=(select min(id) from audit_log)");
            boolean corrupt=false; try{tx.execute(status->service.inboundWithReceipt(8L,items(3),key));}catch(BusinessException expected){corrupt=true;}
            check("corrupt original receipt never creates a replacement increment",corrupt&&jdbc.queryForObject("select quantity from inventory",Integer.class)==17);
            // Negative control: the pre-fix unkeyed worker really executes both SQL increments.
            tx.execute(status->{service.inbound(8L,items(3));service.inbound(8L,items(3));return null;});
            check("legacy negative control applies the same submitted quantity twice",jdbc.queryForObject("select quantity from inventory",Integer.class)==23&&jdbc.queryForObject("select count(*) from inventory_record",Integer.class)==4);
        } finally {AuthContext.clear();}
        System.out.println("AQUAFLOW_INBOUND_SQL_OK " + passed);
    }
    static String digest(String value) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
}
