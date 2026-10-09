package com.example.aquaflow.service;

import com.example.aquaflow.aspect.RequireRoleAspect;
import com.example.aquaflow.controller.ManagerAdjustmentController;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.impl.CustomerServiceImpl;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.CustomerStationVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    static java.util.stream.Stream<Arguments> matchedAddresses() {
        return java.util.stream.Stream.of(
                Arguments.of("阳光81301", "旧档案1号\n阳光小区八栋1单元301室", "", "PROFILE", "阳光小区八栋1单元301室"),
                Arguments.of("星河9202", "旧档案1号", "星河小区9栋202室", "ORDER_HISTORY", "星河小区9栋202室"),
                Arguments.of("星河9202", "", "星河小区9栋202室", "ORDER_HISTORY", "星河小区9栋202室"),
                Arguments.of("星河9202", "旧档案1号", "其他历史4号\n星河小区9栋202室", "ORDER_HISTORY", "星河小区9栋202室"),
                Arguments.of("阳光81301", "阳光小区八栋1单元301室\n其他档案2号", "", "PROFILE", "阳光小区八栋1单元301室"));
    }

    @ParameterizedTest @MethodSource("matchedAddresses")
    void addressExplanationUsesTheMatchingLineWithoutReplacingDefault(String keyword, String profile,
            String history, String source, String expected) {
        CustomerMapper mapper = mock(CustomerMapper.class);
        CustomerServiceImpl service = new CustomerServiceImpl();
        ReflectionTestUtils.setField(service, "customerMapper", mapper);
        var candidate = Map.<String, Object>of("id", 7L, "name", "Synthetic", "phone", "13800000000",
                "addressText", profile, "orderAddressText", history, "adjustmentEligible", 0);
        when(mapper.listSearchCandidates(eq(11L), anyInt())).thenReturn(List.of(candidate));
        CustomerStationVO vo = new CustomerStationVO(); vo.setId(7L); vo.setName("Synthetic"); vo.setPhone("13800000000");
        when(mapper.listStationCustomers(11L)).thenReturn(List.of(vo));
        var item = service.searchStationCustomers(11L, keyword).get(0);
        assertEquals(expected, item.get("matchedAddressText"));
        assertEquals(source, item.get("matchedAddressSource"));
        assertEquals(false, item.get("adjustmentEligible"));
        assertFalse(item.containsKey("orderAddressText"), "do not expose all history snapshots");
        var listed = service.listStationCustomers(11L, keyword).get(0);
        assertEquals(expected, ReflectionTestUtils.getField(listed, "matchedAddressText"));
        assertEquals(source, ReflectionTestUtils.getField(listed, "matchedAddressSource"));
        String originalDefault = profile.isEmpty() ? null : profile.split("\n")[0];
        assertEquals(originalDefault, item.get("addressText")); assertEquals(originalDefault, listed.getAddressText());
        verify(mapper, times(2)).listSearchCandidates(eq(11L), anyInt());
    }

    @Test void namePhoneAndBlankQueriesKeepTheirExistingDefaultAddressHabit() {
        CustomerMapper mapper = mock(CustomerMapper.class);
        CustomerServiceImpl service = new CustomerServiceImpl();
        ReflectionTestUtils.setField(service, "customerMapper", mapper);
        when(mapper.listSearchCandidates(eq(11L), anyInt())).thenReturn(List.of(
                Map.of("id", 7L, "name", "SyntheticName", "phone", "13800000000", "addressText", "档案地址7号")));
        for (String keyword : new String[] { "SyntheticName", "13800000000", "" }) {
            var item = service.searchStationCustomers(11L, keyword).get(0);
            assertEquals("档案地址7号", item.get("addressText"));
            assertNull(item.get("matchedAddressText")); assertNull(item.get("matchedAddressSource"));
        }
    }

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
