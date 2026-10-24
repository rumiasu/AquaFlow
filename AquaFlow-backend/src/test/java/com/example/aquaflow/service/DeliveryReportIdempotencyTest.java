package com.example.aquaflow.service;

import com.example.aquaflow.controller.DeliveryTaskController;
import com.example.aquaflow.dto.DeliveryOrderActionDTO;
import com.example.aquaflow.entity.OrderBarrelException;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderBarrelExceptionMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.impl.OrderBarrelExceptionServiceImpl;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production services with controlled storage; the standalone H2 probe verifies real mapper locking and rollback. */
class DeliveryReportIdempotencyTest {
    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @Test void timeoutRetryReturnsOriginalIdAndNeverDuplicatesTheExceptionNoteOrAlert() {
        Fixture f = new Fixture();
        var first = f.report("DR-original-001");
        var retry = f.report("DR-original-001");
        verify(f.exceptions, times(1)).insert(any());
        verify(f.orders, times(1)).appendSpecialNote(1L, "现场说明");
        verify(f.orders, times(1)).markBarrelException(eq(1L), anyString(), anyLong(), isNull(), isNull());
        verify(f.alerts, times(1)).stationFault(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertEquals(first.getId(), retry.getId());
        assertEquals(0, first.getDiscrepancy());
        var sequence = inOrder(f.orders, f.exceptions);
        sequence.verify(f.orders).getByIdForUpdate(1L);
        sequence.verify(f.exceptions).reportReceiptsForUpdate(eq(20L), startsWith("report:"));
    }

    @Test void handledOriginalIsStillReturnedAndANewKeyCanReportANewIdenticalEvent() {
        Fixture f = new Fixture(); var first = f.report("DR-original-001");
        f.records.get(first.getId()).setStatus("IGNORED");
        assertEquals("IGNORED", f.report("DR-original-001").getStatus());
        assertNotEquals(first.getId(), f.report("DR-later-event-002").getId());
        verify(f.exceptions, times(2)).insert(any());
    }

    @Test void sameKeyCannotChangeReasonSupplementOrVisibleOrderNote() {
        for (String changed : List.of("reason", "supplement", "order-note")) {
            Fixture f = new Fixture(); f.report("DR-original-001");
            assertThrows(BusinessException.class, () -> f.service.recordDeliveryProblem(1L,
                    changed.equals("reason") ? "other" : "barrel_damaged",
                    changed.equals("supplement") ? "另一份说明" : "补充说明",
                    changed.equals("order-note") ? "另一现场说明" : "现场说明", "DR-original-001"));
            verify(f.exceptions, times(1)).insert(any());
            verify(f.orders, times(1)).appendSpecialNote(anyLong(), anyString());
        }
    }

    @Test void actorScopeDoesNotTreatAnotherEmployeesOwnReportAsARetry() {
        Fixture f = new Fixture(); long first = f.report("DR-shared-001").getId();
        AuthContext.set(new AuthContext.AuthUser(21L, "staff", "STATION_MANAGER", 8L));
        assertNotEquals(first, f.report("DR-shared-001").getId());
        assertEquals(2, f.evidence.size());
    }

    @Test void corruptDuplicateOrDanglingReceiptCannotCreateAReplacement() {
        for (String invalid : List.of("bad", "duplicate", "missing-record", "wrong-order", "overflow")) {
            Fixture f = new Fixture(); long id = f.report("DR-original-001").getId();
            String target = f.evidence.keySet().iterator().next();
            if (invalid.equals("bad")) f.evidence.put(target, List.of("v0|bad"));
            if (invalid.equals("duplicate")) f.evidence.put(target, List.of(f.evidence.get(target).get(0), f.evidence.get(target).get(0)));
            if (invalid.equals("missing-record")) f.records.clear();
            if (invalid.equals("wrong-order")) f.records.get(id).setOrderId(99L);
            if (invalid.equals("overflow")) f.evidence.put(target, List.of(f.evidence.get(target).get(0).replace("|" + id, "|9999999999999999999")));
            assertThrows(BusinessException.class, () -> f.report("DR-original-001"), invalid);
            verify(f.exceptions, times(1)).insert(any());
        }
    }

    @Test void legacyUnkeyedReportsRemainSeparateAndUseTheOrderLock() {
        Fixture f = new Fixture();
        assertNotEquals(f.report(null).getId(), f.report(null).getId());
        verify(f.orders, times(2)).getByIdForUpdate(1L);
        verify(f.exceptions, never()).reportReceiptsForUpdate(any(), anyString());
        verifyNoInteractions(f.audit);
    }

    @Test void currentOwnershipAndStationStillGuardBothNewAndRepeatedReports() {
        Fixture f = new Fixture(); f.report("DR-original-001");
        AuthContext.set(new AuthContext.AuthUser(21L, "staff", "DELIVERY", 8L));
        assertThrows(BusinessException.class, () -> f.report("DR-original-001"));
        AuthContext.set(new AuthContext.AuthUser(20L, "staff", "DELIVERY", 9L));
        assertThrows(BusinessException.class, () -> f.report("DR-original-001"));
        verify(f.exceptions, times(1)).insert(any());
    }

    @Test void malformedKeysAndMissingGeneratedIdNeverProduceASuccessReceipt() {
        for (String key : List.of("", "short", "DR key with spaces", "x".repeat(65))) {
            Fixture f = new Fixture(); assertThrows(BusinessException.class, () -> f.report(key));
            verifyNoInteractions(f.orders, f.audit);
        }
        Fixture f = new Fixture(); doNothing().when(f.exceptions).insert(any());
        assertThrows(BusinessException.class, () -> f.report("DR-original-001"));
        verifyNoInteractions(f.audit);
    }

    @Test void controllerReturnsRecordIdOrderAndTheVerifiedOriginalKey() {
        Fixture f = new Fixture();
        DeliveryTaskController controller = new DeliveryTaskController();
        DeliveryConsoleService console = mock(DeliveryConsoleService.class);
        when(console.findOrder(1L)).thenReturn(f.order);
        ReflectionTestUtils.setField(controller, "deliveryConsoleService", console);
        ReflectionTestUtils.setField(controller, "orderBarrelExceptionService", f.service);
        ReflectionTestUtils.setField(controller, "auditLogService", mock(AuditLogService.class));
        var body = new DeliveryOrderActionDTO.Report(); body.setReason("水桶破损"); body.setReasonKey("barrel_damaged");
        body.setClientRequestId("DR-controller-001");
        var first = controller.reportOrder(1L, body); var retry = controller.reportOrder(1L, body);
        assertEquals(0, first.getCode()); assertEquals(first.getData(), retry.getData());
        assertEquals(Set.of("id", "orderId", "clientRequestId"), first.getData().keySet());
        assertEquals(1L, first.getData().get("orderId"));
        assertEquals("DR-controller-001", first.getData().get("clientRequestId"));
    }

    @Test void dtoAllowsLegacyMissingKeyButRejectsMalformedNonNullKeys() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var body = new DeliveryOrderActionDTO.Report(); body.setReason("现场问题");
            assertTrue(validator.validate(body).isEmpty());
            body.setClientRequestId("DR-valid-0001"); assertTrue(validator.validate(body).isEmpty());
            body.setClientRequestId("contains spaces"); assertFalse(validator.validate(body).isEmpty());
        }
    }

    static final class Fixture {
        final OrderMapper orders = mock(OrderMapper.class);
        final OrderBarrelExceptionMapper exceptions = mock(OrderBarrelExceptionMapper.class);
        final AuditLogService audit = mock(AuditLogService.class);
        final AlertService alerts = mock(AlertService.class);
        final OrderBarrelExceptionServiceImpl service = new OrderBarrelExceptionServiceImpl();
        final Orders order = new Orders();
        final Map<Long, OrderBarrelException> records = new HashMap<>();
        final Map<String, List<String>> evidence = new HashMap<>();
        Fixture() {
            order.setId(1L); order.setCustomerId(7L); order.setStationId(8L); order.setDeliveryStaffId(20L);
            when(orders.getById(1L)).thenReturn(order); when(orders.getByIdForUpdate(1L)).thenReturn(order);
            when(orders.appendSpecialNote(anyLong(), anyString())).thenReturn(1);
            when(orders.markBarrelException(anyLong(), anyString(), anyLong(), isNull(), isNull())).thenReturn(1);
            var ids = new AtomicLong(100);
            doAnswer(call -> { OrderBarrelException ex = call.getArgument(0); ex.setId(ids.incrementAndGet()); records.put(ex.getId(), ex); return null; })
                    .when(exceptions).insert(any());
            when(exceptions.reportReceiptsForUpdate(anyLong(), anyString())).thenAnswer(call -> evidence.getOrDefault(call.getArgument(1), List.of()));
            when(exceptions.getByIdForUpdate(anyLong())).thenAnswer(call -> records.get(call.getArgument(0)));
            doAnswer(call -> { String target = call.getArgument(2), detail = call.getArgument(3); evidence.put(target, List.of(detail)); return null; })
                    .when(audit).log(anyString(), anyString(), anyString(), anyString(), isNull());
            ReflectionTestUtils.setField(service, "orderMapper", orders);
            ReflectionTestUtils.setField(service, "exceptionMapper", exceptions);
            ReflectionTestUtils.setField(service, "auditLogService", audit);
            ReflectionTestUtils.setField(service, "alertService", alerts);
            ReflectionTestUtils.setField(service, "barrelPolicy", mock(BarrelBusinessPolicy.class));
            AuthContext.set(new AuthContext.AuthUser(20L, "staff", "DELIVERY", 8L));
        }
        OrderBarrelExceptionService.OrderBarrelExceptionDTO report(String key) {
            return service.recordDeliveryProblem(1L, "barrel_damaged", "补充说明", "现场说明", key);
        }
    }
}
