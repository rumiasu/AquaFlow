package com.example.aquaflow.integration;

import com.example.aquaflow.entity.Notice;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP and SQL; a test-only interceptor pauses immediately before SQL, after the controller's ownership check. */
@Import(NoticePutIntegrationSupport.TestHooks.class)
abstract class NoticePutIntegrationSupport extends AbstractIntegrationTest {
    @Autowired WriteHook hook;
    @Value("${notice-test.changed-rows}") boolean changedRows;
    private static final String BODY = "{\"title\":\"Synthetic title\",\"content\":\"Synthetic body\",\"type\":2,\"status\":0}";

    @TestConfiguration
    static class TestHooks {
        // This test-scoped driver property leaves the guarded TCP URL and every production connection option unchanged.
        @Bean static BeanPostProcessor noticeRowMode(@Value("${notice-test.changed-rows}") boolean changed) {
            return new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof HikariDataSource source) {
                        Properties properties = new Properties(); properties.putAll(source.getDataSourceProperties());
                        properties.setProperty("useAffectedRows", String.valueOf(changed)); source.setDataSourceProperties(properties);
                    }
                    return bean;
                }
            };
        }
        @Bean WriteHook noticeWriteHook() { return new WriteHook(); }
    }

    @Intercepts(@Signature(type=Executor.class, method="update", args={MappedStatement.class,Object.class}))
    static class WriteHook implements Interceptor {
        volatile Long pauseId;
        volatile CountDownLatch entered = new CountDownLatch(0), resume = new CountDownLatch(0);
        volatile Timestamp freezeTime;
        volatile int lastAffected = -1;
        void pause(long id) { pauseId=id; entered=new CountDownLatch(1); resume=new CountDownLatch(1); }
        void reset() { resume.countDown(); pauseId=null; freezeTime=null; lastAffected=-1; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement=(MappedStatement)invocation.getArgs()[0];
            if (!statement.getId().equals("com.example.aquaflow.mapper.NoticeMapper.update")) return invocation.proceed();
            Notice request=(Notice)invocation.getArgs()[1];
            if (request.getId().equals(pauseId)) {
                entered.countDown();
                if (!resume.await(10,TimeUnit.SECONDS)) throw new AssertionError("notice write latch timed out");
            }
            var connection=((Executor)invocation.getTarget()).getTransaction().getConnection();
            boolean frozen=freezeTime!=null;
            try {
                if (frozen) {
                    try (var sql=connection.prepareStatement("SET timestamp = UNIX_TIMESTAMP(?)")) {
                        sql.setTimestamp(1,freezeTime); sql.execute();
                    }
                }
                Object result=invocation.proceed(); lastAffected=(Integer)result;
                System.out.println("NOTICE_SQL_AFFECTED " + lastAffected + " frozenNow=" + frozen);
                return result;
            } finally {
                if (frozen) try (var sql=connection.createStatement()) { sql.execute("SET timestamp = DEFAULT"); }
            }
        }
    }

    @AfterEach void releaseHook() { hook.reset(); }
    private String manager(long station) { return staffToken(createStaff("Synthetic manager","STATION_MANAGER",station,1),"STATION_MANAGER",station); }
    private long notice(Long station) {
        return insert("insert into notice(station_id,title,content,type,status,create_time,update_time) values(?,?,?,?,?,now(),now())",
                station,"Synthetic title","Synthetic body",2,0);
    }
    private void assertRowMode() throws Exception {
        try (var connection=jdbc.getDataSource().getConnection()) {
            // Connector is runtimeOnly; reflect its public interfaces rather than adding a test compile dependency.
            Class<?> driverType=Class.forName("com.mysql.cj.jdbc.JdbcConnection");
            Object properties=driverType.getMethod("getPropertySet").invoke(connection.unwrap(driverType));
            Class<?> keyType=Class.forName("com.mysql.cj.conf.PropertyKey");
            Object key=keyType.getField("useAffectedRows").get(null);
            Object property=Class.forName("com.mysql.cj.conf.PropertySet").getMethod("getBooleanProperty",keyType).invoke(properties,key);
            Object actual=Class.forName("com.mysql.cj.conf.RuntimeProperty").getMethod("getValue").invoke(property);
            assertEquals(changedRows,actual);
            System.out.println("NOTICE_ACTUAL_JDBC useAffectedRows=" + changedRows);
        }
    }
    @Test void deletionBetweenOwnershipCheckAndPutCannotReportSaved() throws Exception {
        assertRowMode(); long station=createStation("Synthetic notice station"), id=notice(station); String token=manager(station);
        hook.pause(id); var pool=Executors.newSingleThreadExecutor();
        try {
            Future<Api> call=pool.submit(()->put("/api/notices/"+id,token,BODY));
            assertTrue(hook.entered.await(10,TimeUnit.SECONDS),"PUT passed ownership check and reached SQL");
            // An independent autocommit connection deletes while the actual HTTP thread is paused before its UPDATE.
            try (var connection=jdbc.getDataSource().getConnection(); var sql=connection.prepareStatement("delete from notice where id=?")) {
                assertTrue(connection.getAutoCommit()); sql.setLong(1,id); assertEquals(1,sql.executeUpdate());
            }
            hook.resume.countDown(); Api result=call.get(10,TimeUnit.SECONDS);
            assertEquals(200,result.status()); assertEquals(1,result.code(),result.toString()); assertEquals(0,hook.lastAffected);
            assertEquals(0,intOf("select count(*) from notice where id=?",id));
        } finally { hook.resume.countDown(); pool.shutdownNow(); }
    }
    @Test void sameValueRepeatedPutSucceedsEvenWhenNowDoesNotChange() throws Exception {
        assertRowMode(); long station=createStation("Synthetic repeat station"), id=notice(station); String token=manager(station);
        Timestamp fixed=jdbc.queryForObject("select update_time from notice where id=?",Timestamp.class,id); hook.freezeTime=fixed;
        Map<String,Object> before=jdbc.queryForMap("select * from notice where id=?",id);
        for (int i=0;i<2;i++) {
            assertEquals(0,put("/api/notices/"+id,token,BODY).code());
            assertEquals(changedRows?0:1,hook.lastAffected,"same fields including frozen NOW must exercise real changed/matched row semantics");
            assertEquals(before,jdbc.queryForMap("select * from notice where id=?",id));
        }
    }
    @Test void ownedChangedFieldsAreActuallySavedInBothRowModes() throws Exception {
        assertRowMode(); long station=createStation("Synthetic edited station"), id=notice(station);
        String token=manager(station);
        String body="{\"id\":999999,\"title\":\"Synthetic new title\","
                +"\"content\":\"Synthetic new body\",\"type\":1,\"status\":1}";
        Api result=put("/api/notices/"+id,token,body);
        assertEquals(200,result.status()); assertEquals(0,result.code(),result.toString()); assertEquals(1,hook.lastAffected);
        Map<String,Object> row=jdbc.queryForMap("select * from notice where id=?",id);
        assertEquals("Synthetic new title",row.get("title")); assertEquals("Synthetic new body",row.get("content"));
        assertEquals(1,((Number)row.get("type")).intValue()); assertEquals(1,((Number)row.get("status")).intValue());
        assertEquals(station,((Number)row.get("station_id")).longValue());
        assertEquals(0,intOf("select count(*) from notice where id=999999"));
    }
    @Test void wrongRequestStationIsRejectedByTheExistingAspectBeforeSql() {
        long station=createStation("Synthetic aspect station"), other=createStation("Synthetic aspect other station");
        long id=notice(station); String token=manager(station);
        Map<String,Object> before=jdbc.queryForMap("select * from notice where id=?",id); hook.lastAffected=-1;
        String body="{\"stationId\":"+other+",\"title\":\"Synthetic rejected title\","
                +"\"content\":\"Synthetic rejected body\",\"type\":1,\"status\":1}";
        Api result=put("/api/notices/"+id,token,body);
        assertEquals(200,result.status()); assertEquals(1,result.code()); assertEquals(-1,hook.lastAffected);
        assertEquals(before,jdbc.queryForMap("select * from notice where id=?",id));
    }
    @Test void foreignAndSystemNoticesRejectWithNoSqlWrite() {
        long station=createStation("Synthetic own station"), other=createStation("Synthetic other station"); String token=manager(station);
        for (Long owner:new Long[]{other,null}) {
            long id=notice(owner); Map<String,Object> before=jdbc.queryForMap("select * from notice where id=?",id); hook.lastAffected=-1;
            assertEquals(1,put("/api/notices/"+id,token,BODY).code()); assertEquals(-1,hook.lastAffected);
            assertEquals(before,jdbc.queryForMap("select * from notice where id=?",id));
        }
    }
    @Test void deleteExistingAndMissingKeepExistingResponses() {
        long station=createStation("Synthetic delete station"), id=notice(station); String token=manager(station);
        assertEquals(0,delete("/api/notices/"+id,token).code()); assertEquals(1,delete("/api/notices/"+id,token).code());
    }
}
