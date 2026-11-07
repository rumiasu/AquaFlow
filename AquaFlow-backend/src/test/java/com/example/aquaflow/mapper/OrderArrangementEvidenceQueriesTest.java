package com.example.aquaflow.mapper;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderArrangementEvidenceQueriesTest {
    @Test
    void inheritedQueriesResolveInOrderMapperNamespaceWithTheirParameters() {
        Configuration configuration = new Configuration();
        configuration.addMapper(OrderMapper.class);
        SqlSession session = mock(SqlSession.class);
        when(session.getConfiguration()).thenReturn(configuration);
        OrderMapper mapper = configuration.getMapper(OrderMapper.class, session);
        LocalDateTime since = LocalDateTime.of(2026, 1, 2, 3, 4);

        mapper.latestDirectedReturnEvent(7L);
        mapper.latestStaffReturnEvent(7L);
        mapper.latestFulfillmentEventSince(7L, since);
        mapper.orderStateEventBefore(7L, 9L);
        mapper.latestOrderArrangementEvent(7L);

        String ns = OrderMapper.class.getName() + ".";
        for (String method : new String[] {"latestDirectedReturnEvent", "latestStaffReturnEvent",
                "latestFulfillmentEventSince", "orderStateEventBefore", "latestOrderArrangementEvent"}) {
            assertTrue(configuration.hasStatement(ns + method), method);
            verify(session).selectOne(eq(ns + method), any());
        }
        var sinceSql = configuration.getMappedStatement(ns + "latestFulfillmentEventSince")
                .getBoundSql(Map.of("orderId", 7L, "since", since));
        assertTrue(sinceSql.getSql().contains("create_time >= ?"));
        assertEquals(java.util.List.of("orderId", "since"), sinceSql.getParameterMappings().stream()
                .map(p -> p.getProperty()).toList());
        var beforeSql = configuration.getMappedStatement(ns + "orderStateEventBefore")
                .getBoundSql(Map.of("orderId", 7L, "eventId", 9L));
        assertTrue(beforeSql.getSql().contains("id < ?"));
        assertEquals(java.util.List.of("orderId", "eventId"), beforeSql.getParameterMappings().stream()
                .map(p -> p.getProperty()).toList());
    }
}
