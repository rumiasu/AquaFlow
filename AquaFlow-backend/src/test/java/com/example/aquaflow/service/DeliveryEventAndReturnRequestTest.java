package com.example.aquaflow.service;

import com.example.aquaflow.entity.OrderTransfer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.OrderTransferMapper;
import com.example.aquaflow.service.impl.DeliveryConsoleServiceImpl;
import com.example.aquaflow.service.impl.OrderWorkflowServiceImpl;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BusinessTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production service calls only; no Spring context or external database. SQL behavior has a separate memory-only probe. */
class DeliveryEventAndReturnRequestTest {
    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @Test void dailyListAndStatsUseTheSameDeliveryWindowIncludingDeliveredUnpaidAndZeroReturns() {
        OrderMapper mapper = mock(OrderMapper.class);
        DeliveryConsoleServiceImpl service = new DeliveryConsoleServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", mapper);
        ReflectionTestUtils.setField(service, "businessTime", new BusinessTime(Clock.fixed(
                Instant.parse("2026-10-23T16:30:00Z"), ZoneId.of("Asia/Shanghai"))));
        Orders unpaid = delivered(1L, 3, 2), zero = delivered(2L, 4, 0);
        var start = LocalDate.of(2026, 10, 24).atStartOfDay();
        var until = start.plusDays(1);
        when(mapper.listDeliveredByDeliveryStaffIdAndPeriod(20L, start, until)).thenReturn(List.of(unpaid, zero));
        when(mapper.countDeliveryTimeUnverified(20L)).thenReturn(3);
        when(mapper.listByDeliveryStaffId(20L, 2)).thenReturn(List.of(new Orders()));
        var rows = service.listCompletedToday(20L);
        assertEquals(2, rows.size()); assertNull(unpaid.getCustomerName());
        assertEquals("receiver", unpaid.getReceiverName());
        var stats = service.todayStats(20L, null);
        assertEquals(2, stats.get("completedCount")); assertEquals(2, stats.get("returnBarrels"));
        assertEquals(1, stats.get("deliveringCount")); assertEquals(3, stats.get("deliveryTimeUnverifiedCount"));
        verify(mapper, times(2)).listDeliveredByDeliveryStaffIdAndPeriod(20L, start, until);
    }

    @Test void barrelHistoryPreservesZeroReturnsAndMasksCrossStationProfiles() {
        OrderMapper mapper = mock(OrderMapper.class);
        DeliveryConsoleServiceImpl service = new DeliveryConsoleServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", mapper);
        Orders zero = delivered(1L, 3, 0);
        when(mapper.listBarrelRecords(20L)).thenReturn(List.of(zero));
        assertEquals(List.of(zero), service.listBarrelRecords(20L));
        assertNull(zero.getCustomerName()); assertNull(zero.getCustomerPhone());
        assertEquals("receiver", zero.getReceiverName()); assertEquals(0, zero.getReturnBucketQty());
    }

    @Test void firstReturnCreatesOneRequestAndRepeatedReturnDoesNotAppendOrInsertAgain() {
        Fixture f = new Fixture();
        when(f.orders.listPendingStaffRequestsForUpdate(1L)).thenReturn(List.of());
        doAnswer(call -> {
            OrderTransfer saved = call.getArgument(0); saved.setId(70L);
            when(f.orders.listPendingStaffRequestsForUpdate(1L)).thenReturn(List.of(saved));
            return null;
        }).when(f.transfers).insert(any());
        when(f.orders.updateStatusIf(1L, 2, 1)).thenAnswer(call -> { f.order.setStatus(1); return 1; });
        f.service.returnToStation(1L, "原原因");
        f.service.returnToStation(1L, "重试的新原因");
        verify(f.transfers, times(1)).insert(any());
        verify(f.orders, times(1)).appendSpecialNote(1L, "[退回站长] 原原因");
        verify(f.audit, times(1)).log(eq("ORDER"), eq("RETURN_TO_STATION"), eq("order:1"), anyString(), isNull());
        var readOrder = inOrder(f.orders);
        readOrder.verify(f.orders).getByIdForUpdate(1L);
        readOrder.verify(f.orders).listPendingStaffRequestsForUpdate(1L);
    }

    @ParameterizedTest @ValueSource(strings = {"TRANSFER", "CANCEL_REQUEST", "REDISPATCH"})
    void anotherPendingStaffRequestMustRejectBeforeAnyMutation(String subKind) {
        Fixture f = new Fixture();
        when(f.orders.listPendingStaffRequestsForUpdate(1L)).thenReturn(List.of(request(subKind, 20L, 8L)));
        assertThrows(BusinessException.class, () -> f.service.returnToStation(1L, "不能覆盖"));
        f.verifyNoWrites();
    }

    @Test void duplicateLegacyRequestsAndMismatchedOwnersRequireVerification() {
        for (List<OrderTransfer> requests : List.of(
                List.of(request("RETURN_STATION", 20L, 8L), request("RETURN_STATION", 20L, 8L)),
                List.of(request("RETURN_STATION", 21L, 8L)),
                List.of(request("RETURN_STATION", 20L, 9L)))) {
            Fixture f = new Fixture();
            when(f.orders.listPendingStaffRequestsForUpdate(1L)).thenReturn(requests);
            assertThrows(BusinessException.class, () -> f.service.returnToStation(1L, "重复"));
            f.verifyNoWrites();
        }
    }

    @Test void originalStaffAndStationAuthorizationStillRunBeforeIdempotentReturn() {
        Fixture f = new Fixture();
        AuthContext.set(new AuthContext.AuthUser(21L, "staff", "DELIVERY", 8L));
        assertThrows(BusinessException.class, () -> f.service.returnToStation(1L, "越权"));
        verify(f.orders, never()).listPendingStaffRequestsForUpdate(anyLong());
        f.verifyNoWrites();
    }

    private static Orders delivered(long id, int status, int returned) {
        Orders row = new Orders(); row.setId(id); row.setStatus(status); row.setReturnBucketQty(returned);
        row.setStationId(7L); row.setDeliveryStationId(8L); row.setCustomerName("private");
        row.setCustomerPhone("private"); row.setReceiverName("receiver"); return row;
    }
    private static OrderTransfer request(String subKind, long staff, long station) {
        OrderTransfer request = new OrderTransfer(); request.setId(70L); request.setOrderId(1L);
        request.setKind("STAFF"); request.setStatus("PENDING"); request.setSubKind(subKind);
        request.setFromStaffId(staff); request.setFromStationId(station); return request;
    }
    private static class Fixture {
        final OrderMapper orders = mock(OrderMapper.class);
        final OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        final AuditLogService audit = mock(AuditLogService.class);
        final OrderWorkflowServiceImpl service = new OrderWorkflowServiceImpl();
        final Orders order = new Orders();
        Fixture() {
            order.setId(1L); order.setStationId(8L); order.setDeliveryStaffId(20L); order.setStatus(2);
            when(orders.getByIdForUpdate(1L)).thenReturn(order);
            ReflectionTestUtils.setField(service, "orderMapper", orders);
            ReflectionTestUtils.setField(service, "orderTransferMapper", transfers);
            ReflectionTestUtils.setField(service, "auditLogService", audit);
            AuthContext.set(new AuthContext.AuthUser(20L, "staff", "DELIVERY", 8L));
        }
        void verifyNoWrites() {
            verify(orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
            verify(orders, never()).appendSpecialNote(anyLong(), anyString());
            verify(transfers, never()).insert(any()); verifyNoInteractions(audit);
        }
    }
}
