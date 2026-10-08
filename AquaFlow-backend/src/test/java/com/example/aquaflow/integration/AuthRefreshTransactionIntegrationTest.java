package com.example.aquaflow.integration;

import com.example.aquaflow.entity.UserToken;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.util.PasswordUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, Spring transaction proxy and MySQL; only the write boundary is fault/gate injected. */
@Import(AuthRefreshTransactionIntegrationTest.Hooks.class)
class AuthRefreshTransactionIntegrationTest extends AbstractIntegrationTest {
    private static final String OLD_PASSWORD = "F78-public-old-password";
    @Autowired private WriteBoundary boundary;

    @AfterEach void clearHook() { boundary.gate = null; }

    private long user(String type) {
        if (type.equals("customer")) return createCustomer("F78-synthetic", "F78-synthetic-openid");
        long id = createStaff("F78-synthetic", "STATION_MANAGER", createStation("F78-station"), 1);
        jdbc.update("update staff set password_hash=? where id=?", PasswordUtil.encode(OLD_PASSWORD), id);
        return id;
    }

    private String seed(long id, String type) {
        String value = jwtUtil.generateRefreshToken(id, type);
        jdbc.update("insert into user_token(user_id,user_type,refresh_token,expire_time) values(?,?,?,?)",
                id, type, value, LocalDateTime.now().plusDays(1));
        return value;
    }

    private String access(long id, String type) {
        return jwtUtil.generateAccessToken(id, type, type.equals("staff") ? "manager" : "customer", null);
    }

    private JsonNode refresh(String value) throws Exception {
        return authPost("/api/auth/refresh", null, Map.of("refreshToken", value));
    }

    private JsonNode authPost(String path, String token, Map<String, ?> body) throws Exception {
        return post(path, token, om.writeValueAsString(body)).body();
    }

    private int tokenRows(String value) {
        return jdbc.queryForObject("select count(*) from user_token where refresh_token=?", Integer.class, value);
    }

    @ParameterizedTest @ValueSource(strings = {"customer", "staff"})
    void insertFailureRollsBackOldTokenAndPreservesOtherSessions(String type) throws Exception {
        long id = user(type);
        String old = seed(id, type), sibling = seed(id, type);
        Gate gate = new Gate(id, type); gate.failInsert = true; boundary.gate = gate;
        assertNotEquals(0, refresh(old).path("code").asInt(), "failed persistence must not return success");
        assertEquals(1, tokenRows(old), "delete and failed replacement must roll back together");
        assertEquals(1, tokenRows(sibling));
        boundary.gate = null;
        assertEquals(0, refresh(old).path("code").asInt(), "the original remains retryable after rollback");
        assertEquals(1, tokenRows(sibling));
    }

    @ParameterizedTest @ValueSource(strings = {"customer", "staff"})
    void logoutCannotFinishThenBeUndoneByLateRefreshInsertion(String type) throws Exception {
        raceRevocation(type, false);
    }

    @Test void passwordChangeCannotBeUndoneByLateRefreshInsertion() throws Exception {
        raceRevocation("staff", true);
    }

    private void raceRevocation(String type, boolean password) throws Exception {
        long id = user(type); String old = seed(id, type); seed(id, type);
        Gate gate = new Gate(id, type); boundary.gate = gate;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> rotation = pool.submit(() -> refresh(old));
            assertTrue(gate.beforeInsert.await(10, TimeUnit.SECONDS), "rotation must reach delete/insert boundary");
            Future<JsonNode> revocation = pool.submit(() -> password
                    ? authPost("/api/auth/change-password", access(id, type), Map.of("oldPassword", OLD_PASSWORD, "newPassword", "F78-public-new-password"))
                    : authPost("/api/auth/logout", access(id, type), Map.of()));
            assertTrue(gate.beforeRevoke.await(10, TimeUnit.SECONDS), "actual revocation mapper must enter");
            // An autocommit implementation finishes here; a transactional one waits on MySQL locks.
            try { revocation.get(300, TimeUnit.MILLISECONDS); } catch (TimeoutException expectedWait) { }
            gate.release.countDown();
            JsonNode rotated = rotation.get(15, TimeUnit.SECONDS);
            assertEquals(0, revocation.get(15, TimeUnit.SECONDS).path("code").asInt());
            assertEquals(0, jdbc.queryForObject("select count(*) from user_token where user_id=? and user_type=?", Integer.class, id, type),
                    "successful revocation must leave no late replacement behind");
            boundary.gate = null;
            assertEquals(1, refresh(old).path("code").asInt());
            if (rotated.path("code").asInt() == 0)
                assertEquals(1, refresh(rotated.path("data").path("refreshToken").asText()).path("code").asInt());
        } finally {
            gate.release.countDown(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "no background request may outlive the fixture");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"customer", "staff"})
    void committedLogoutRejectsOldRefreshBeforeAnyInsertion(String type) throws Exception {
        long id = user(type); String old = seed(id, type);
        assertEquals(0, authPost("/api/auth/logout", access(id, type), Map.of()).path("code").asInt());
        assertEquals(1, refresh(old).path("code").asInt());
        assertEquals(0, tokenRows(old));
    }

    @Test void logoutPreservesOtherCustomerAndSameNumericStaffIdentity() throws Exception {
        long id = user("customer"), other = createCustomer("F78-other", "F78-other-openid");
        long staff = user("staff"); assertEquals(id, staff);
        String own = seed(id, "customer"), opposite = seed(staff, "staff"), unrelated = seed(other, "customer");
        assertEquals(0, authPost("/api/auth/logout", access(id, "customer"), Map.of()).path("code").asInt());
        assertEquals(0, tokenRows(own)); assertEquals(1, tokenRows(opposite)); assertEquals(1, tokenRows(unrelated));
    }

    @ParameterizedTest @ValueSource(strings = {"customer", "staff"})
    void concurrentReplayOfSameOldTokenCannotReplaceTheReturnedWinner(String type) throws Exception {
        long id = user(type); String old = seed(id, type), sibling = seed(id, type);
        jdbc.update("insert into user_token(user_id,user_type,refresh_token,expire_time) values(?,?,?,?)",
                id, type, old, LocalDateTime.now().plusDays(1)); // Historical identical rows.
        Gate gate = new Gate(id, type); boundary.gate = gate;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> first = pool.submit(() -> refresh(old));
            assertTrue(gate.beforeInsert.await(10, TimeUnit.SECONDS));
            Future<JsonNode> replay = pool.submit(() -> refresh(old));
            assertTrue(gate.secondQuery.await(10, TimeUnit.SECONDS), "second real mapper query must enter while the first transaction is held");
            assertThrows(TimeoutException.class, () -> replay.get(300, TimeUnit.MILLISECONDS),
                    "the second locking query must still wait before the first transaction is released");
            gate.release.countDown();
            JsonNode winner = first.get(15, TimeUnit.SECONDS);
            assertEquals(0, winner.path("code").asInt());
            assertEquals(1, replay.get(15, TimeUnit.SECONDS).path("code").asInt(), "consumed old credentials cannot rotate again");
            assertEquals(0, tokenRows(old), "all historical identical rows are consumed");
            assertEquals(1, tokenRows(sibling), "a different session remains intact");
            String replacement = winner.path("data").path("refreshToken").asText();
            assertEquals(1, tokenRows(replacement));
            boundary.gate = null;
            assertEquals(0, refresh(replacement).path("code").asInt(), "the first returned replacement remains usable");
        } finally {
            gate.release.countDown(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"user", "type"})
    void storedTokenMustBelongToTheSignedIdentity(String mismatch) throws Exception {
        long id = user("customer"), other = createCustomer("F78-other", "F78-other-openid");
        String old = seed(id, "customer");
        if (mismatch.equals("user")) jdbc.update("update user_token set user_id=? where refresh_token=?", other, old);
        else jdbc.update("update user_token set user_type='staff' where refresh_token=?", old);
        assertEquals(1, refresh(old).path("code").asInt(), "mismatched stored ownership must not rotate");
        assertEquals(1, tokenRows(old));
    }

    /** The test hook never replaces the mapper, JDBC connection or transaction manager. */
    @TestConfiguration static class Hooks {
        @Bean WriteBoundary authRefreshWriteBoundary() { return new WriteBoundary(); }
    }

    private static final class Gate {
        final long id; final String type;
        final CountDownLatch beforeInsert = new CountDownLatch(1), beforeRevoke = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch secondQuery = new CountDownLatch(1);
        final AtomicInteger queryEntries = new AtomicInteger();
        volatile boolean failInsert;
        Gate(long id, String type) { this.id = id; this.type = type; }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    static class WriteBoundary implements Interceptor {
        volatile Gate gate;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Gate current = gate;
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            Object parameter = invocation.getArgs()[1];
            if (current != null && statement.endsWith("UserTokenMapper.findByRefreshToken")
                    && current.queryEntries.incrementAndGet() == 2) current.secondQuery.countDown();
            if (current != null && statement.endsWith("UserTokenMapper.insert") && parameter instanceof UserToken row
                    && row.getUserId() == current.id && current.type.equals(row.getUserType())) {
                if (current.failInsert) throw new IllegalStateException("synthetic refresh persistence failure");
                current.beforeInsert.countDown();
                if (!current.release.await(15, TimeUnit.SECONDS)) throw new AssertionError("refresh insertion gate timed out");
            }
            if (current != null && statement.endsWith("UserTokenMapper.deleteByUser") && parameter instanceof Map<?, ?> args
                    && args.get("userId").equals(current.id) && current.type.equals(args.get("userType")))
                current.beforeRevoke.countDown();
            return invocation.proceed();
        }
    }
}
