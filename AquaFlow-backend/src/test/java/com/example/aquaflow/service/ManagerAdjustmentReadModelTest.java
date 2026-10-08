package com.example.aquaflow.service;

import com.example.aquaflow.aspect.RequireRoleAspect;
import com.example.aquaflow.controller.ManagerAdjustmentController;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.impl.CustomerServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes read-model projection and actual role advice; no database connection. */
class ManagerAdjustmentReadModelTest {
    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test void boundWithoutOrdersIsSelectableButOrderOnlyAndUnknownEligibilityAreNot() {
        CustomerMapper mapper = mock(CustomerMapper.class);
        CustomerServiceImpl service = new CustomerServiceImpl();
        ReflectionTestUtils.setField(service, "customerMapper", mapper);
        when(mapper.listSearchCandidates(eq(1L), anyInt())).thenReturn(List.of(
                Map.of("id", 7L, "name", "bound", "phone", "synthetic", "adjustmentEligible", 1),
                Map.of("id", 8L, "name", "order-only", "adjustmentEligible", 0),
                Map.of("id", 9L, "name", "unknown")));
        var items = service.searchStationCustomers(1L, null);
        assertEquals(3, items.size());
        assertEquals(true, items.get(0).get("adjustmentEligible"));
        assertEquals(false, items.get(1).get("adjustmentEligible"));
        assertEquals(false, items.get(2).get("adjustmentEligible"));
        assertEquals("synthetic", items.get(0).get("phone"));
        verify(mapper).listSearchCandidates(eq(1L), anyInt());
    }

    @Test void anotherStationDoesNotInheritTheFirstStationsBoundFlag() {
        CustomerMapper mapper = mock(CustomerMapper.class);
        CustomerServiceImpl service = new CustomerServiceImpl();
        ReflectionTestUtils.setField(service, "customerMapper", mapper);
        when(mapper.listSearchCandidates(eq(2L), anyInt())).thenReturn(List.of(
                Map.of("id", 7L, "name", "order-only-in-B", "adjustmentEligible", 0)));
        assertEquals(false, service.searchStationCustomers(2L, null).get(0).get("adjustmentEligible"));
        assertTrue(service.searchStationCustomers(null, null).isEmpty());
        verify(mapper).listSearchCandidates(eq(2L), anyInt());
    }

    @Test void adjustmentReadEntryStillRejectsCustomerDeliveryAndUnselected() {
        var factory = new AspectJProxyFactory(new ManagerAdjustmentController());
        factory.addAspect(new RequireRoleAspect());
        ManagerAdjustmentController proxy = factory.getProxy();
        for (String role : List.of("customer", "DELIVERY", "UNSELECTED")) {
            AuthContext.set(new AuthContext.AuthUser(9L, role.equals("customer") ? "customer" : "staff", role, 1L));
            assertThrows(BusinessException.class, () -> proxy.list(null, 1, 20));
        }
    }
}
