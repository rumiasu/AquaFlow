package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** A held MySQL row lock provides a deterministic stop-before-profile-write barrier. */
class StaffProfileStatusRaceIntegrationTest extends AbstractIntegrationTest {
    @Test void profileWaitingBehindCommittedDisableCannotReactivateEmployee() throws Exception {
        long station=createStation("资料状态竞态");long staff=createStaff("原员工","DELIVERY",station,1);
        long otherStation=createStation("并发管理目标站");long otherStaff=createStaff("合成管理哨兵","STATION_MANAGER",otherStation,1);
        String changedHash=jdbc.queryForObject("select password_hash from staff where id=?",String.class,otherStaff);
        String oldPhone=jdbc.queryForObject("select phone from staff where id=?",String.class,staff);
        String refresh=jwtUtil.generateRefreshToken(staff,"staff");
        jdbc.update("insert into user_token(user_id,user_type,refresh_token,expire_time) values(?,?,?,?)",staff,"staff",refresh,java.time.LocalDateTime.now().plusDays(1));
        Api activeRefresh=post("/api/auth/refresh",null,om.writeValueAsString(java.util.Map.of("refreshToken",refresh)));
        assertEquals(0,activeRefresh.code(),activeRefresh.toString());refresh=activeRefresh.data().path("refreshToken").asText();assertFalse(refresh.isBlank());
        String token=staffToken(staff,"DELIVERY",station);ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Connection lock=jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try(var ps=lock.prepareStatement("update staff set status=2,role='STATION_MANAGER',station_id=?,password_hash=? where id=?")){
                ps.setLong(1,otherStation);ps.setString(2,changedHash);ps.setLong(3,staff);assertEquals(1,ps.executeUpdate());}
            Future<Api> profile=pool.submit(()->post("/api/auth/update-profile",token,"{\"nickname\":\"合成新名\",\"phone\":\"13800000000\"}"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean waiting=false;
            while(System.nanoTime()<deadline && !profile.isDone()) {
                waiting=longOf("select count(*) from information_schema.processlist where db=database() and lower(info) like 'update staff set name%'")>0;
                if(waiting)break;Thread.sleep(20);
            }
            assertTrue(waiting,"资料更新必须已到达SQL写入闸门，不能靠延时猜测交错");
            lock.commit();Api rejected=profile.get(20,TimeUnit.SECONDS);assertEquals(1,rejected.code(),rejected.toString());
            assertEquals(2,intOf("select status from staff where id=?",staff),"个人资料不得写回旧status=1");
            assertEquals(401,get("/api/auth/me",token).status(),"停用后旧访问凭据的新请求必须被拒绝");
            assertEquals(1,post("/api/auth/refresh",null,om.writeValueAsString(java.util.Map.of("refreshToken",refresh))).code());
            assertEquals("原员工",jdbc.queryForObject("select name from staff where id=?",String.class,staff));
            assertEquals(oldPhone,jdbc.queryForObject("select phone from staff where id=?",String.class,staff));
            assertEquals("STATION_MANAGER",jdbc.queryForObject("select role from staff where id=?",String.class,staff));
            assertEquals(otherStation,longOf("select station_id from staff where id=?",staff));
            assertEquals(changedHash,jdbc.queryForObject("select password_hash from staff where id=?",String.class,staff));
        } finally {pool.shutdownNow();}
    }
    @Test void committedProfileCannotOverrideALaterManagerDisable() {
        long station=createStation("资料后停用");long manager=createStaff("站长","STATION_MANAGER",station,1),staff=createStaff("员工","DELIVERY",station,1);
        assertEquals(0,post("/api/auth/update-profile",staffToken(staff,"DELIVERY",station),"{\"nickname\":\"合成新名\"}").code());
        assertEquals(0,put("/api/staff/"+staff,staffToken(manager,"STATION_MANAGER",station),"{\"status\":2}").code());
        assertEquals(2,intOf("select status from staff where id=?",staff));
    }
}
