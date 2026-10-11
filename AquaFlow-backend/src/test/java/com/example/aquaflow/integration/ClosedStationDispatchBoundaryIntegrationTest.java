package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.plugin.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** 硬停业挡新增接入；已承担的履约不被停业开关抹除。 */
@Import(ClosedStationDispatchBoundaryIntegrationTest.LockConfig.class)
class ClosedStationDispatchBoundaryIntegrationTest extends AbstractIntegrationTest {
    long a,b,ma,mb,driver,product,customer,address;
    String at,bt;
    void seed() {
        a=createStation("派单甲站");b=createStation("接单乙站");
        ma=createStaff("甲长","STATION_MANAGER",a,1);mb=createStaff("乙长","STATION_MANAGER",b,1);
        driver=createStaff("乙配送","DELIVERY",b,1);
        product=createProduct("普通水",1,"20.00","30.00",0,"0.00");
        createInventoryFull(a,product,50,1,"10.00");createInventoryFull(b,product,50,1,"10.00");
        customer=createCustomer("客户","closed-station-dispatch");address=createAddress(customer,"一楼");
        at=staffToken(ma,"STATION_MANAGER",a);bt=staffToken(mb,"STATION_MANAGER",b);
    }
    long order() { return createOrderFull(customer,address,a,product,1,1,2,"40.00","0.00","40.00",false,0); }
    @Test void hardClosedStationCannotClaimNewPoolOrder() {
        seed();long id=order();
        assertEquals(0,post("/api/delivery/orders/transfer/"+id+"/outsource",at,"{}").code());
        jdbc.update("update station set status=2 where id=?",b);
        Api r=post("/api/delivery/orders/"+id+"/claim-pool",bt,"{\"deliveryStaffId\":"+driver+"}");
        assertEquals(1,r.code(),"硬停业站不能新抢单："+r);
        assertNull(jdbc.queryForObject("select delivery_station_id from orders where id=?",Long.class,id));
        assertEquals(1,intOf("select status from orders where id=?",id));
    }
    @Test void hardClosedTargetCannotReceiveNewDirectDispatchThroughEitherEntry() {
        seed();jdbc.update("update station set status=2 where id=?",b);
        for(String entry:new String[]{"/api/delivery/orders/","/api/delivery/orders/transfer/"}) {
            long id=order();String suffix=entry.contains("transfer")?"/outsource":"/dispatch";
            Api r=post(entry+id+suffix,at,"{\"targetStationId\":"+b+"}");
            assertEquals(1,r.code(),"硬停业目标站不能新接定向外派："+r);
            assertEquals(a,longOf("select delivery_station_id from orders where id=?",id));
            assertEquals(0,intOf("select count(*) from order_transfer where order_id=?",id));
        }
    }
    @Test void hardClosureAfterDirectOfferStopsFirstAcceptanceWithoutChangingOrder() {
        seed();long id=order();
        assertEquals(0,post("/api/delivery/orders/"+id+"/dispatch",at,"{\"targetStationId\":"+b+"}").code());
        jdbc.update("update station set status=2 where id=?",b);
        assertEquals(1,post("/api/delivery/orders/"+id+"/accept",bt,null).code());
        assertEquals(1,post("/api/delivery/orders/assign/"+id,bt,"{\"deliveryStaffId\":"+driver+"}").code());
        assertEquals(1,intOf("select status from orders where id=?",id));
        assertNull(jdbc.queryForObject("select delivery_staff_id from orders where id=?",Long.class,id));
    }
    @Test void alreadyAssignedTaskAndOwnOldOrderCanStillBeAcceptedAfterHardClosure() {
        seed();long incoming=order(),own=order();
        assertEquals(0,post("/api/delivery/orders/"+incoming+"/dispatch",at,"{\"targetStationId\":"+b+"}").code());
        assertEquals(0,post("/api/delivery/orders/assign/"+incoming,bt,"{\"deliveryStaffId\":"+driver+"}").code());
        jdbc.update("update station set status=2 where id in (?,?)",a,b);
        String dt=staffToken(driver,"DELIVERY",b);
        assertEquals(0,post("/api/delivery/orders/"+incoming+"/accept",dt,null).code());
        assertEquals(0,post("/api/delivery/orders/"+own+"/accept",at,null).code());
        assertEquals(2,intOf("select status from orders where id=?",incoming));
        assertEquals(2,intOf("select status from orders where id=?",own));
    }
    @Test void restingAndPausedDeliverySoftStatesAllowNewDispatchAndClaim() {
        seed();for(int soft:new int[]{2,4}) {
            jdbc.update("update station set operating_status=? where id=?",soft,b);
            long pool=order(),direct=order();
            assertEquals(0,post("/api/delivery/orders/transfer/"+pool+"/outsource",at,"{}").code());
            assertEquals(0,post("/api/delivery/orders/"+pool+"/claim-pool",bt,"{\"deliveryStaffId\":"+driver+"}").code());
            assertEquals(0,post("/api/delivery/orders/"+direct+"/dispatch",at,"{\"targetStationId\":"+b+"}").code());
        }
    }

    @Autowired LockProbe probe;
    @TestConfiguration(proxyBeanMethods=false)
    static class LockConfig { @Bean LockProbe stationLockProbe() { return new LockProbe(); } }
    static class LockBarrier {
        final long station;final CountDownLatch acquired=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile long connectionId;
        LockBarrier(long station) { this.station=station; }
    }
    // Pause after the real SELECT FOR UPDATE; no mock result and no production hook.
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class LockProbe implements Interceptor {
        volatile LockBarrier armed;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object result=invocation.proceed();LockBarrier barrier=armed;
            MappedStatement statement=(MappedStatement)invocation.getArgs()[0];
            if(barrier!=null&&statement.getId().equals("com.example.aquaflow.mapper.StationMapper.getByIdForUpdate")
                    &&result instanceof List<?> rows&&!rows.isEmpty()
                    &&rows.get(0) instanceof com.example.aquaflow.entity.Station station&&station.getId()==barrier.station) {
                var connection=((Executor)invocation.getTarget()).getTransaction().getConnection();
                try(var sql=connection.createStatement();var rs=sql.executeQuery("select connection_id()")) { assertTrue(rs.next());barrier.connectionId=rs.getLong(1); }
                barrier.acquired.countDown();
                if(!barrier.release.await(20,TimeUnit.SECONDS)) throw new AssertionError("real station lock release timed out");
            }
            return result;
        }
    }
    Map<String,List<Map<String,Object>>> businessSnapshot() {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for(String table:new String[]{"orders","order_item","order_transfer","payment_record","inventory","inventory_record","inventory_reservation","dispatch_agreement","customer_notification","audit_log","inter_station_settlement","inter_station_recovery"})
            result.put(table,jdbc.queryForList("select * from "+table+" order by 1"));
        return result;
    }
    void awaitStationLockWait(long blocker,long requester) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        do {
            Integer waiting=jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id and l.engine=w.engine join performance_schema.threads blocked on blocked.thread_id=w.requesting_thread_id join performance_schema.threads holder on holder.thread_id=w.blocking_thread_id where l.object_schema=database() and l.object_name='station' and l.index_name='PRIMARY' and l.lock_data=? and holder.processlist_id=? and (?=0 or blocked.processlist_id=?)",Integer.class,String.valueOf(b),blocker,requester,requester);
            if(waiting!=null&&waiting>0) return;
            Thread.sleep(20);
        } while(System.nanoTime()<deadline);
        fail("expected a real station PRIMARY row-lock wait for exact blocker/requester");
    }
    @Test
    void hardClosureAndNewClaimSerializeBothWaysAndRejectedClaimHasNoWrites() throws Exception {
        seed();ExecutorService workers=Executors.newFixedThreadPool(2);LockBarrier barrier=null;
        try {
            long denied=order();assertEquals(0,post("/api/delivery/orders/transfer/"+denied+"/outsource",at,"{}").code());
            Map<String,List<Map<String,Object>>> before=businessSnapshot();
            try(var stop=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
                stop.setAutoCommit(false);long stopId;
                try(var sql=stop.createStatement();var rs=sql.executeQuery("select connection_id()")) { assertTrue(rs.next());stopId=rs.getLong(1); }
                try(var sql=stop.prepareStatement("select id from station where id=? for update")) { sql.setLong(1,b);try(var rs=sql.executeQuery()) { assertTrue(rs.next()); } }
                Future<Api> claim=workers.submit(()->post("/api/delivery/orders/"+denied+"/claim-pool",bt,"{\"deliveryStaffId\":"+driver+"}"));
                try {
                    awaitStationLockWait(stopId,0);assertFalse(claim.isDone());
                    try(var sql=stop.prepareStatement("update station set status=2 where id=?")) { sql.setLong(1,b);assertEquals(1,sql.executeUpdate()); }
                    stop.commit();assertEquals(1,claim.get(15,TimeUnit.SECONDS).code());
                } finally { stop.rollback(); }
            }
            assertEquals(before,businessSnapshot(),"denied claim must not write any of the 12 business tables");
            assertEquals(2,intOf("select status from station where id=?",b));
            assertNull(jdbc.queryForObject("select delivery_station_id from orders where id=?",Long.class,denied));
            jdbc.update("update station set status=1 where id=?",b);
            long accepted=order();assertEquals(0,post("/api/delivery/orders/transfer/"+accepted+"/outsource",at,"{}").code());
            barrier=new LockBarrier(b);probe.armed=barrier;
            Future<Api> claim=workers.submit(()->post("/api/delivery/orders/"+accepted+"/claim-pool",bt,"{\"deliveryStaffId\":"+driver+"}"));
            assertTrue(barrier.acquired.await(10,TimeUnit.SECONDS),"claim must acquire the real station lock");
            AtomicLong stopId=new AtomicLong();CountDownLatch stopStarted=new CountDownLatch(1);
            Future<Integer> stop=workers.submit(()->{
                try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
                    try(var sql=connection.createStatement();var rs=sql.executeQuery("select connection_id()")) { assertTrue(rs.next());stopId.set(rs.getLong(1)); }
                    stopStarted.countDown();
                    try(var sql=connection.prepareStatement("update station set status=2 where id=?")) { sql.setLong(1,b);return sql.executeUpdate(); }
                }
            });
            assertTrue(stopStarted.await(10,TimeUnit.SECONDS));awaitStationLockWait(barrier.connectionId,stopId.get());assertFalse(stop.isDone());
            barrier.release.countDown();assertEquals(0,claim.get(15,TimeUnit.SECONDS).code());assertEquals(1,stop.get(15,TimeUnit.SECONDS));
            assertEquals(2,intOf("select status from station where id=?",b));
            assertEquals(2,intOf("select status from orders where id=?",accepted));
            assertEquals(b,longOf("select delivery_station_id from orders where id=?",accepted));
            assertEquals(driver,longOf("select delivery_staff_id from orders where id=?",accepted));
        } finally {
            if(barrier!=null) barrier.release.countDown();probe.armed=null;
            workers.shutdown();assertTrue(workers.awaitTermination(30,TimeUnit.SECONDS));
        }
    }
}
