package com.example.aquaflow.service;

import com.example.aquaflow.mapper.OrderMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies generated SQL and bindings without opening a database connection. */
class DeliveryHistoryMapperBindingTest {
    private BoundSql sql(LocalDateTime beforeTime, Long beforeId) {
        Configuration config = new Configuration();
        config.addMapper(OrderMapper.class);
        Map<String, Object> params = new HashMap<>();
        params.put("staffId", 8L);
        params.put("status", 4);
        params.put("beforeTime", beforeTime);
        params.put("beforeId", beforeId);
        params.put("limit", 21);
        return config.getMappedStatement(OrderMapper.class.getName() + ".listHistoryPage").getBoundSql(params);
    }

    @Test
    void firstPageKeepsOriginalStaffScopeAndStableOrder() {
        BoundSql bound = sql(null, null);
        String query = bound.getSql().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(query.contains("where o.delivery_staff_id=? and o.status=?"));
        assertTrue(query.contains("order by o.create_time desc, o.id desc limit ?"));
        assertFalse(query.contains("o.create_time < ?"));
        assertFalse(query.contains("from staff s"));
        assertEquals(List.of("staffId", "status", "limit"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

    @Test
    void cursorUsesTimeAndIdAsTieBreaker() {
        BoundSql bound = sql(LocalDateTime.of(2026, 10, 2, 10, 0), 42L);
        String query = bound.getSql().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(query.contains("o.create_time < ? or (o.create_time=? and o.id < ?)"));
        assertEquals(List.of("staffId", "status", "beforeTime", "beforeTime", "beforeId", "limit"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
}