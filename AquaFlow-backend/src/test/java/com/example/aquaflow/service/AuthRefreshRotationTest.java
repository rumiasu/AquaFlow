package com.example.aquaflow.service;

import com.example.aquaflow.constant.WeChatApp;
import com.example.aquaflow.dto.AuthRequestDTO;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.UserToken;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StaffStationApplicationMapper;
import com.example.aquaflow.mapper.UserTokenMapper;
import com.example.aquaflow.util.JwtUtil;
import com.example.aquaflow.util.PasswordUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calls the production refresh/login methods with real signed JWTs and controllable in-memory
 * mapper semantics. Latches establish both original-token reads before either rotation writes.
 * No Spring application, HTTP, JDBC or integration-test reset is started.
 */
class AuthRefreshRotationTest {
    private static final long ID = 41L;
    private static final long STATION = 19L;
    // Public test-only signing material, never a local/application secret.
    private static final String TEST_SECRET = "F78-test-only-signing-material-never-used-by-an-application-1234567890";

    @ParameterizedTest
    @CsvSource({"customer,A", "customer,B", "staff,A", "staff,B"})
    void twoValidatedRefreshesKeepBothReturnedTokensUsable(String type, String first) throws Exception {
        Fixture f = new Fixture();
        f.addUser(ID, type);
        String original = f.seed(ID, type);
        f.tokens.add(f.tokens.find(original)); // Legacy identical rows must all be revoked.
        Gate gate = new Gate(original);
        f.tokens.beforePreciseDelete = gate::awaitPermission;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> a = start(pool, "A", () -> f.refresh(original));
            Future<Map<String, Object>> b = start(pool, "B", () -> f.refresh(original));
            assertTrue(gate.arrived.await(10, TimeUnit.SECONDS), "both validated refreshes must reach the write boundary");
            gate.release(first);
            Map<String, Object> firstResult = (first.equals("A") ? a : b).get(10, TimeUnit.SECONDS);
            gate.release(first.equals("A") ? "B" : "A");
            Map<String, Object> secondResult = (first.equals("A") ? b : a).get(10, TimeUnit.SECONDS);
            String firstToken = token(firstResult), secondToken = token(secondResult);
            System.out.println("F78_RACE type=" + type + " first=" + first
                    + " returnedStored=" + ((f.tokens.find(firstToken) != null ? 1 : 0) + (f.tokens.find(secondToken) != null ? 1 : 0))
                    + " oldRows=" + f.tokens.count(original) + " userDeletes=" + f.tokens.userDeletes.get());
            assertTrue(!firstToken.equals(secondToken), "two rotations must issue distinct refresh tokens");
            assertIdentity(f.jwt, firstResult, ID, type);
            assertIdentity(f.jwt, secondResult, ID, type);
            assertEquals(0, f.tokens.count(original), "all identical old rows must be removed");
            int writes = f.tokens.writes.get();
            assertThrows(BusinessException.class, () -> f.refresh(original), "old token must no longer rotate");
            assertEquals(writes, f.tokens.writes.get(), "rejected old-token replay must not write");
            assertDoesNotThrow(() -> f.refresh(firstToken), "first returned token must still refresh after the second request finishes");
            assertDoesNotThrow(() -> f.refresh(secondToken), "second returned token must still refresh after the first token rotates again");
            assertEquals(0, f.tokens.userDeletes.get(), "refresh must not clear all sessions for the user");
        } finally {
            gate.release("A"); gate.release("B");
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "controlled threads must stop");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer", "staff"})
    void failedSecondRotationDoesNotInvalidateCompletedSibling(String type) throws Exception {
        Fixture f = new Fixture(); f.addUser(ID, type);
        String original = f.seed(ID, type);
        Gate gate = new Gate(original); f.tokens.beforePreciseDelete = gate::awaitPermission;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> a = start(pool, "A", () -> f.refresh(original));
            Future<Map<String, Object>> b = start(pool, "B", () -> f.refresh(original));
            assertTrue(gate.arrived.await(10, TimeUnit.SECONDS));
            gate.release("A");
            String completed = token(a.get(10, TimeUnit.SECONDS));
            f.tokens.failInsertThread = "B";
            gate.release("B");
            var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> b.get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertDoesNotThrow(() -> f.refresh(completed), "a failing sibling may not delete a previously returned token");
            assertEquals(0, f.tokens.userDeletes.get());
            System.out.println("F78_FAILURE concurrentType=" + type + " failed=B completedAStillUsable=true");
        } finally {
            gate.release("A"); gate.release("B");
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void realJwtIssuesDistinctTokensWithinTheSameSecond() {
        JwtUtil jwt = jwt();
        boolean sameSecond = false;
        for (int i = 0; i < 100; i++) {
            String a = jwt.generateRefreshToken(ID, "customer"), b = jwt.generateRefreshToken(ID, "customer");
            var ca = jwt.parseToken(a); var cb = jwt.parseToken(b);
            if (ca.getIssuedAt().equals(cb.getIssuedAt()) && ca.getExpiration().equals(cb.getExpiration())) {
                assertTrue(!a.equals(b), "same-second tokens must differ");
                assertTrue(!ca.getId().equals(cb.getId()), "production UUID jti must differ");
                sameSecond = true; break;
            }
        }
        assertTrue(sameSecond, "the test must actually observe identical second-level iat/exp");
    }

    @Test void refreshIsolatesOtherCustomersAndStaffWithTheSameNumericId() {
        Fixture f = new Fixture();
        f.addUser(ID, "customer"); f.addUser(ID + 1, "customer"); f.addUser(ID, "staff");
        String main = f.seed(ID, "customer"), otherCustomer = f.seed(ID + 1, "customer"), staff = f.seed(ID, "staff");
        assertIdentity(f.jwt, f.refresh(main), ID, "customer");
        assertIdentity(f.jwt, f.refresh(otherCustomer), ID + 1, "customer");
        assertIdentity(f.jwt, f.refresh(staff), ID, "staff");
        assertEquals(0, f.tokens.userDeletes.get());
    }

    @Test void refreshKeepsAnotherExistingTokenOfTheSameUser() {
        Fixture f = new Fixture(); f.addUser(ID, "customer");
        String original = f.seed(ID, "customer"), sibling = f.seed(ID, "customer");
        f.refresh(original);
        assertDoesNotThrow(() -> f.refresh(sibling), "precise rotation must preserve another token of this user");
        assertEquals(0, f.tokens.userDeletes.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "jwt-expired", "access-type", "revoked", "stored-expired",
            "missing-customer", "missing-staff", "disabled-staff", "null-staff-status"})
    void invalidOrIneligibleRefreshRejectsBeforeAnyWrite(String scenario) {
        Fixture f = new Fixture();
        String type = scenario.contains("staff") ? "staff" : "customer";
        f.addUser(ID, type);
        String original = f.seed(ID, type);
        switch (scenario) {
            case "malformed" -> original = "not-a-jwt";
            case "jwt-expired" -> {
                ReflectionTestUtils.setField(f.jwt, "refreshTokenExpiry", -300_000L);
                original = f.jwt.generateRefreshToken(ID, type);
                ReflectionTestUtils.setField(f.jwt, "refreshTokenExpiry", 86_400_000L);
            }
            case "access-type" -> original = f.jwt.generateAccessToken(ID, type, "customer", null);
            case "revoked" -> f.tokens.removeWithoutWrite(original);
            case "stored-expired" -> f.tokens.expireWithoutWrite(original);
            case "missing-customer" -> f.customers.remove(ID);
            case "missing-staff" -> f.staff.remove(ID);
            case "disabled-staff" -> f.staff.get(ID).setStatus(0);
            case "null-staff-status" -> f.staff.get(ID).setStatus(null);
            default -> fail("unknown scenario");
        }
        String rejected = original;
        assertThrows(BusinessException.class, () -> f.refresh(rejected));
        assertEquals(0, f.tokens.writes.get(), "precondition rejection must not attempt a token write");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "customer-wechat", "staff-wechat"})
    void ordinaryLoginStillClearsOnlyThatUsersPreviousTokens(String path) {
        Fixture f = new Fixture();
        String type = path.equals("customer-wechat") ? "customer" : "staff";
        f.addUser(ID, type); f.addUser(ID + 1, type);
        String old1 = f.seed(ID, type), old2 = f.seed(ID, type), other = f.seed(ID + 1, type);
        String opposite = f.seed(ID, type.equals("staff") ? "customer" : "staff");
        Map<String, Object> result;
        if (path.equals("password")) {
            f.staff.get(ID).setPasswordHash(PasswordUtil.encode("F78-public-test-password"));
            AuthRequestDTO.Login request = new AuthRequestDTO.Login();
            request.setUsername("user-" + ID); request.setPassword("F78-public-test-password");
            result = f.service.login(request);
        } else if (path.equals("customer-wechat")) {
            AuthRequestDTO.WxLogin request = new AuthRequestDTO.WxLogin(); request.setCode("F78-fake-code");
            result = f.service.wxLogin(request);
        } else {
            AuthRequestDTO.WxLoginStaff request = new AuthRequestDTO.WxLoginStaff(); request.setCode("F78-fake-code");
            result = f.service.wxLoginStaff(request);
        }
        assertEquals(1, f.tokens.userDeletes.get(), "login retains one user-wide cleanup");
        assertNull(f.tokens.find(old1)); assertNull(f.tokens.find(old2));
        assertNotNull(f.tokens.find(other)); assertNotNull(f.tokens.find(opposite));
        assertNotNull(f.tokens.find(token(result)));
        assertEquals(1, f.tokens.active(ID, type).size(), "login retains one current token for this user/type");
        System.out.println("F78_LOGIN path=" + path + " oldRows=0 sameUserNewRows=1 otherIdentitiesPreserved=true");
    }

    @Test void unselectedStaffLoginStillClearsItsVirtualUsersPreviousToken() {
        Fixture f = new Fixture();
        long virtualId = -1L * Math.abs(("openid-" + ID + ":staff:unselected").hashCode());
        String old = f.seed(virtualId, "staff");
        AuthRequestDTO.WxLoginStaff request = new AuthRequestDTO.WxLoginStaff(); request.setCode("F78-fake-code");
        var result = f.service.wxLoginStaff(request);
        assertEquals("UNSELECTED", result.get("role"));
        assertEquals(1, f.tokens.userDeletes.get());
        assertNull(f.tokens.find(old)); assertNotNull(f.tokens.find(token(result)));
    }

    @Test void serialLogoutStillClearsAllRefreshTokensForOnlyTheBearerUser() {
        Fixture f = new Fixture(); f.addUser(ID, "customer"); f.addUser(ID + 1, "customer");
        String first = f.seed(ID, "customer"), second = f.seed(ID, "customer");
        String other = f.seed(ID + 1, "customer"), staff = f.seed(ID, "staff");
        f.service.logout("Bearer " + f.jwt.generateAccessToken(ID, "customer", "customer", null));
        assertNull(f.tokens.find(first)); assertNull(f.tokens.find(second));
        assertNotNull(f.tokens.find(other)); assertNotNull(f.tokens.find(staff));
        int writes = f.tokens.writes.get();
        assertThrows(BusinessException.class, () -> f.refresh(first));
        assertEquals(writes, f.tokens.writes.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"access", "refresh"})
    void signingFailureDoesNotWriteOrRevokeTheOldToken(String stage) {
        Fixture f = new Fixture(); f.addUser(ID, "customer"); String original = f.seed(ID, "customer");
        JwtUtil failing = stage.equals("access") ? new JwtUtil() {
            @Override public String generateAccessToken(Long id, String type, String role, Long station) {
                throw new IllegalStateException("F78 fake access signing failure");
            }
        } : new JwtUtil() {
            @Override public String generateRefreshToken(Long id, String type) {
                throw new IllegalStateException("F78 fake refresh signing failure");
            }
        };
        configure(failing); f.useJwt(failing);
        assertThrows(IllegalStateException.class, () -> f.refresh(original));
        assertEquals(0, f.tokens.writes.get()); assertNotNull(f.tokens.find(original));
    }

    @Test void preciseDeleteFailureStopsBeforeInsertAndKeepsTheOldRecord() {
        Fixture f = new Fixture(); f.addUser(ID, "customer"); String original = f.seed(ID, "customer");
        f.tokens.failPreciseDelete = true;
        assertThrows(IllegalStateException.class, () -> f.refresh(original));
        assertEquals(1, f.tokens.writes.get()); assertEquals(0, f.tokens.inserts.get());
        assertNotNull(f.tokens.find(original)); assertEquals(0, f.tokens.userDeletes.get());
    }

    @Test void insertFailureIsPropagatedAndExistingNonTransactionalOldTokenLossIsExplicit() {
        Fixture f = new Fixture(); f.addUser(ID, "customer");
        String original = f.seed(ID, "customer"), sibling = f.seed(ID, "customer");
        f.tokens.failInsertThread = Thread.currentThread().getName();
        assertThrows(IllegalStateException.class, () -> f.refresh(original), "an insertion failure may not return a success response");
        assertNull(f.tokens.find(original), "existing delete-before-insert order has no service transaction to restore this row");
        assertEquals(1, f.tokens.inserts.get()); assertEquals(0, f.tokens.userDeletes.get());
        f.tokens.failInsertThread = null;
        assertDoesNotThrow(() -> f.refresh(sibling), "a failed insert must not clear this users other refresh token");
        System.out.println("F78_FAILURE boundary=insert oldTokenRemainsRevoked=true siblingStillUsable=true");
    }

    private static String token(Map<String, Object> response) { return (String) response.get("refreshToken"); }

    private static void assertIdentity(JwtUtil jwt, Map<String, Object> response, long id, String type) {
        var refresh = jwt.parseToken(token(response));
        var access = jwt.parseToken((String) response.get("accessToken"));
        assertEquals(id, refresh.get("userId", Number.class).longValue());
        assertEquals(type, refresh.get("userType", String.class));
        assertEquals("refresh", refresh.get("tokenType", String.class));
        assertEquals(id, access.get("userId", Number.class).longValue());
        assertEquals(type, access.get("userType", String.class));
        assertEquals(type.equals("staff") ? "manager" : "customer", access.get("role", String.class));
        if (type.equals("staff")) assertEquals(STATION, access.get("stationId", Number.class).longValue());
        else assertNull(access.get("stationId"));
    }

    private static JwtUtil jwt() { JwtUtil jwt = new JwtUtil(); configure(jwt); return jwt; }
    private static void configure(JwtUtil jwt) {
        ReflectionTestUtils.setField(jwt, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwt, "accessTokenExpiry", 900_000L);
        ReflectionTestUtils.setField(jwt, "refreshTokenExpiry", 86_400_000L);
    }

    @FunctionalInterface private interface Work { Map<String, Object> run(); }
    private static Future<Map<String, Object>> start(ExecutorService pool, String label, Work work) {
        return pool.submit(() -> { Thread.currentThread().setName(label); return work.run(); });
    }

    private static final class Gate {
        final String original;
        final CountDownLatch arrived = new CountDownLatch(2);
        final Map<String, CountDownLatch> permits = Map.of("A", new CountDownLatch(1), "B", new CountDownLatch(1));
        Gate(String original) { this.original = original; }
        void release(String label) { permits.get(label).countDown(); }
        void awaitPermission(String token) {
            if (!original.equals(token)) return;
            arrived.countDown();
            try {
                if (!permits.get(Thread.currentThread().getName()).await(10, TimeUnit.SECONDS))
                    throw new AssertionError("controlled refresh permission timed out");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }

    /** Models the mapper predicates only; it is not a database/transaction implementation. */
    private static final class Tokens implements InvocationHandler {
        final List<UserToken> rows = new ArrayList<>();
        final AtomicInteger writes = new AtomicInteger(), inserts = new AtomicInteger(), userDeletes = new AtomicInteger();
        final AtomicInteger ids = new AtomicInteger();
        volatile Consumer<String> beforePreciseDelete = ignored -> {};
        volatile boolean failPreciseDelete;
        volatile String failInsertThread;
        UserTokenMapper mapper() { return proxy(UserTokenMapper.class, this); }
        synchronized void add(UserToken row) {
            UserToken copy = new UserToken();
            copy.setId((long) ids.incrementAndGet()); copy.setUserId(row.getUserId()); copy.setUserType(row.getUserType());
            copy.setRefreshToken(row.getRefreshToken()); copy.setExpireTime(row.getExpireTime()); copy.setCreateTime(row.getCreateTime());
            rows.add(copy);
        }
        synchronized UserToken find(String value) {
            return rows.stream().filter(r -> r.getRefreshToken().equals(value) && r.getExpireTime().isAfter(LocalDateTime.now()))
                    .max(java.util.Comparator.comparing(UserToken::getId)).orElse(null);
        }
        synchronized int count(String value) { return (int) rows.stream().filter(r -> r.getRefreshToken().equals(value)).count(); }
        synchronized List<UserToken> active(long id, String type) {
            return rows.stream().filter(r -> Objects.equals(r.getUserId(), id) && r.getUserType().equals(type)
                    && r.getExpireTime().isAfter(LocalDateTime.now())).toList();
        }
        synchronized void removeWithoutWrite(String token) { rows.removeIf(r -> r.getRefreshToken().equals(token)); }
        synchronized void expireWithoutWrite(String token) {
            rows.stream().filter(r -> r.getRefreshToken().equals(token)).forEach(r -> r.setExpireTime(LocalDateTime.now().minusSeconds(1)));
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "findByRefreshToken": return find((String) args[0]);
                case "findActiveByUser": return active((Long) args[0], (String) args[1]);
                case "deleteByRefreshToken":
                    beforePreciseDelete.accept((String) args[0]);
                    writes.incrementAndGet();
                    if (failPreciseDelete) throw new IllegalStateException("F78 fake precise-delete failure");
                    removeWithoutWrite((String) args[0]); return null;
                case "deleteByUser":
                    writes.incrementAndGet(); userDeletes.incrementAndGet();
                    synchronized (this) { rows.removeIf(r -> Objects.equals(r.getUserId(), args[0]) && r.getUserType().equals(args[1])); }
                    return null;
                case "insert":
                    writes.incrementAndGet(); inserts.incrementAndGet();
                    if (Thread.currentThread().getName().equals(failInsertThread)) throw new IllegalStateException("F78 fake insert failure");
                    add((UserToken) args[0]); return null;
                default: throw new AssertionError("unexpected token mapper method: " + method.getName());
            }
        }
    }

    private static final class Fixture {
        final AuthTokenService service = new AuthTokenService();
        final Tokens tokens = new Tokens();
        final Map<Long, Customer> customers = new HashMap<>();
        final Map<Long, Staff> staff = new HashMap<>();
        JwtUtil jwt = jwt();
        Fixture() {
            ReflectionTestUtils.setField(service, "jwtUtil", jwt);
            ReflectionTestUtils.setField(service, "userTokenMapper", tokens.mapper());
            ReflectionTestUtils.setField(service, "customerMapper", proxy(CustomerMapper.class, (p, m, a) -> switch (m.getName()) {
                case "getById" -> customers.get((Long) a[0]);
                case "findByOpenid" -> customers.values().stream().filter(c -> c.getOpenid().equals(a[0])).findFirst().orElse(null);
                default -> throw new AssertionError("unexpected customer mapper method: " + m.getName());
            }));
            ReflectionTestUtils.setField(service, "staffMapper", proxy(StaffMapper.class, (p, m, a) -> switch (m.getName()) {
                case "getById" -> staff.get((Long) a[0]);
                case "findByName" -> staff.values().stream().filter(s -> s.getName().equals(a[0])).findFirst().orElse(null);
                case "findByOpenid" -> staff.values().stream().filter(s -> s.getOpenid().equals(a[0])).findFirst().orElse(null);
                default -> throw new AssertionError("unexpected staff mapper method: " + m.getName());
            }));
            ReflectionTestUtils.setField(service, "appMapper", proxy(StaffStationApplicationMapper.class, (p, m, a) -> {
                if (m.getName().equals("listByStaff")) return List.of();
                throw new AssertionError("unexpected application mapper method: " + m.getName());
            }));
            ReflectionTestUtils.setField(service, "weChatLoginService", new WeChatLoginService() {
                @Override public Map<String, Object> code2Session(WeChatApp app, String code) { return Map.of("openid", "openid-" + ID); }
            });
        }
        void useJwt(JwtUtil replacement) { jwt = replacement; ReflectionTestUtils.setField(service, "jwtUtil", replacement); }
        void addUser(long id, String type) {
            if (type.equals("customer")) {
                Customer c = new Customer(); c.setId(id); c.setName("user-" + id); c.setOpenid("openid-" + id); c.setPhone("");
                c.setCreateTime(LocalDateTime.now()); c.setUpdateTime(c.getCreateTime()); customers.put(id, c);
            } else {
                Staff s = new Staff(); s.setId(id); s.setName("user-" + id); s.setOpenid("openid-" + id);
                s.setRole("STATION_MANAGER"); s.setStationId(STATION); s.setStatus(1); s.setPhone(""); staff.put(id, s);
            }
        }
        String seed(long id, String type) {
            String value = jwt.generateRefreshToken(id, type);
            UserToken row = new UserToken(); row.setUserId(id); row.setUserType(type); row.setRefreshToken(value);
            row.setExpireTime(LocalDateTime.now().plusDays(1)); row.setCreateTime(LocalDateTime.now()); tokens.add(row); return value;
        }
        Map<String, Object> refresh(String token) {
            AuthRequestDTO.Refresh request = new AuthRequestDTO.Refresh(); request.setRefreshToken(token);
            return service.refresh(request);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) return switch (m.getName()) {
                case "toString" -> "F78InMemory" + type.getSimpleName();
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                default -> throw new AssertionError(m.getName());
            };
            return handler.invoke(p, m, a);
        }));
    }
}
