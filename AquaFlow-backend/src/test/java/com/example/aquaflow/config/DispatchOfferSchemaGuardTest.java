package com.example.aquaflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Startup guard is read-only; missing or incompatible v88 must not become a runtime write failure. */
class DispatchOfferSchemaGuardTest {
    @Test void expectedColumnAllowsStartup() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(1,1);
        assertDoesNotThrow(()->new DispatchOfferSchemaGuard(jdbc).run(null));verify(jdbc,never()).execute(anyString());
    }
    @Test void preAgreementSchemaDoesNotRequireAnUnusableFeature() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        assertDoesNotThrow(()->new DispatchOfferSchemaGuard(jdbc).run(null));verify(jdbc,times(1)).queryForObject(anyString(),eq(Integer.class));
    }
    @Test void missingVersionRejectsWithoutDdl() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(1,0);
        assertTrue(assertThrows(IllegalStateException.class,()->new DispatchOfferSchemaGuard(jdbc).run(null)).getMessage().contains("v88"));verify(jdbc,never()).execute(anyString());
    }
}
