package com.example.aquaflow.service;

import com.example.aquaflow.aspect.RequireRoleAspect;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.controller.ManagerPayrollController;
import com.example.aquaflow.entity.StaffPayroll;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.exception.GlobalExceptionHandler;
import com.example.aquaflow.mapper.StaffPayrollMapper;
import com.example.aquaflow.util.AuthContext;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual controller/role advice and MyBatis SQL binding; fixture rows are in memory, never a DB. */
class ManagerPayrollCursorReadTest {
    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test
    void mapperBindsStationAndExclusiveIdCursorBeforeLimit() {
        BoundSql sql = boundSql(1L, 700L, 100);
        String text = sql.getSql().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(text.contains("station_id = ?"), text);
        assertTrue(text.contains("and id < ?"), text);
        assertTrue(text.contains("order by id desc limit ?"), text);
        assertFalse(text.contains("offset"), text);
        assertEquals(List.of("stationId", "beforeId", "limit"),
                sql.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test
    void omittedCursorPreservesTheFirstPageSqlAndArrayContract() {
        BoundSql sql = boundSql(2L, null, 100);
        assertFalse(sql.getSql().contains("id <"));
        assertEquals(List.of("stationId", "limit"),
                sql.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        var api = controller(List.of(row(3, 2), row(4, 1)), new AtomicInteger());
        login("STATION_MANAGER", 2L);
        Result<List<StaffPayroll>> result = list(api, 100, null);
        assertEquals(0, result.getCode());
        assertEquals(List.of(3L), result.getData().stream().map(StaffPayroll::getId).toList());
    }

    @Test
    void fixed503RowsTraverseWithoutDuplicatesAcrossStationsAndConcurrentNewRows() {
        List<StaffPayroll> rows = new ArrayList<>();
        for (int i = 1; i <= 503; i++) {
            rows.add(row(i * 2L, 1));
            if (i % 9 == 0) rows.add(row(i * 2L + 1, 2));
        }
        AtomicInteger reads = new AtomicInteger();
        var api = controller(rows, reads);
        login("STATION_MANAGER", 1L);
        Set<Long> seen = new LinkedHashSet<>();
        Long before = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            List<StaffPayroll> page = list(api, 100, before).getData();
            assertTrue(page.size() <= 100);
            if (page.isEmpty()) break;
            long previous = before == null ? Long.MAX_VALUE : before;
            for (StaffPayroll row : page) {
                assertEquals(1L, row.getStationId());
                assertTrue(row.getId() < previous);
                assertTrue(seen.add(row.getId()), "duplicate id=" + row.getId());
                previous = row.getId();
            }
            before = page.get(page.size() - 1).getId();
            if (reads.get() == 1) rows.add(row(2000, 1));
        }
        assertEquals(503, seen.size());
        assertFalse(seen.contains(2000L), "new rows must not shift the historical cursor");
        assertEquals(7, reads.get(), "six pages and one empty terminal read");
        assertTrue(list(api, 100, 1L).getData().isEmpty());
    }

    @Test
    void foreignStationCursorIsOnlyABoundaryAndDoesNotChangeStationAuthority() {
        var api = controller(List.of(row(9, 1), row(8, 2), row(7, 1), row(6, 2)), new AtomicInteger());
        login("STATION_MANAGER", 1L);
        assertEquals(List.of(7L), list(api, 5000, 8L).getData().stream().map(StaffPayroll::getId).toList());
        login("STATION_MANAGER", 2L);
        assertEquals(List.of(6L), list(api, 5000, 8L).getData().stream().map(StaffPayroll::getId).toList());
    }

    @Test
    void invalidCursorReturnsBusinessFailureWithoutInvokingMapper() {
        AtomicInteger reads = new AtomicInteger();
        var api = controller(List.of(row(1, 1)), reads);
        login("STATION_MANAGER", 1L);
        for (long before : List.of(0L, -1L, Long.MIN_VALUE)) {
            Result<List<StaffPayroll>> result = list(api, 100, before);
            assertEquals(1, result.getCode());
            assertNull(result.getData());
        }
        assertEquals(0, reads.get());
    }

    @Test
    void limitRemainsBoundedAndHugePositiveCursorDoesNotExpandTheRead() {
        List<StaffPayroll> rows = new ArrayList<>();
        for (int i = 1; i <= 503; i++) rows.add(row(i, 1));
        var api = controller(rows, new AtomicInteger());
        login("STATION_MANAGER", 1L);
        assertEquals(1, list(api, 0, null).getData().size());
        assertEquals(500, list(api, Integer.MAX_VALUE, Long.MAX_VALUE).getData().size());
    }

    @Test
    void customerDeliveryAndUnselectedCannotReadAnyPageAndUnboundManagerIsRejected() {
        AtomicInteger reads = new AtomicInteger();
        var api = controller(List.of(row(1, 1)), reads);
        for (String role : List.of("customer", "DELIVERY", "UNSELECTED")) {
            login(role, 1L);
            assertThrows(BusinessException.class, () -> list(api, 100, 2L));
        }
        login("STATION_MANAGER", null);
        assertThrows(BusinessException.class, () -> list(api, 100, 2L));
        assertEquals(0, reads.get());
    }

    @Test
    void standaloneMvcRejectsMalformedOverflowAndNonpositiveCursorBeforeAnyRead() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        var mvc = MockMvcBuilders.standaloneSetup(controller(List.of(row(1, 1)), reads))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        login("STATION_MANAGER", 1L);
        for (String before : List.of("abc", "1.2", "9223372036854775808", "0", "-1")) {
            mvc.perform(get("/api/manager/payroll").param("beforeId", before))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        }
        assertEquals(0, reads.get());
    }

    @Test
    void standaloneMvcKeepsArrayResponseAndIgnoresSpoofedStationOnEveryPage() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller(
                        List.of(row(9, 1), row(8, 2), row(7, 1), row(6, 2)), new AtomicInteger()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        login("STATION_MANAGER", 1L);
        mvc.perform(get("/api/manager/payroll"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/manager/payroll").param("beforeId", "8").param("stationId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].stationId").value(1));
    }

    private BoundSql boundSql(Long station, Long before, int limit) {
        Configuration configuration = new Configuration();
        configuration.addMapper(StaffPayrollMapper.class);
        Map<String, Object> params = new HashMap<>();
        params.put("stationId", station); params.put("beforeId", before); params.put("limit", limit);
        return configuration.getMappedStatement(StaffPayrollMapper.class.getName() + ".listByStation")
                .getBoundSql(params);
    }

    private ManagerPayrollController controller(List<StaffPayroll> rows, AtomicInteger reads) {
        StaffPayrollMapper mapper = (StaffPayrollMapper) Proxy.newProxyInstance(
                StaffPayrollMapper.class.getClassLoader(), new Class<?>[]{StaffPayrollMapper.class},
                (proxy, method, args) -> {
                    assertEquals("listByStation", method.getName());
                    reads.incrementAndGet();
                    Long station = (Long) args[0]; int limit = (Integer) args[1];
                    Long before = args.length > 2 ? (Long) args[2] : null;
                    return rows.stream().filter(p -> p.getStationId().equals(station))
                            .filter(p -> before == null || p.getId() < before)
                            .sorted(Comparator.comparing(StaffPayroll::getId).reversed()).limit(limit).toList();
                });
        ManagerPayrollController target = new ManagerPayrollController();
        ReflectionTestUtils.setField(target, "staffPayrollMapper", mapper);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new RequireRoleAspect());
        return factory.getProxy();
    }

    @SuppressWarnings("unchecked")
    private Result<List<StaffPayroll>> list(ManagerPayrollController api, int limit, Long before) {
        // The fallback runs the old method during the red baseline without changing production bytes.
        try {
            Method method;
            try { method = ManagerPayrollController.class.getMethod("listPayrolls", int.class, Long.class); }
            catch (NoSuchMethodException old) {
                return (Result<List<StaffPayroll>>) ManagerPayrollController.class.getMethod("listPayrolls", int.class)
                        .invoke(api, limit);
            }
            return (Result<List<StaffPayroll>>) method.invoke(api, limit, before);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException cause) throw cause;
            if (wrapped.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(wrapped.getCause());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void login(String role, Long station) {
        AuthContext.set(new AuthContext.AuthUser(9L, role.equals("customer") ? "customer" : "staff", role, station));
    }

    private StaffPayroll row(long id, long station) {
        StaffPayroll row = new StaffPayroll(); row.setId(id); row.setStationId(station); row.setStaffId(7L);
        row.setCreateTime(LocalDateTime.of(2026, 10, 7, 12, 0));
        return row;
    }
}
