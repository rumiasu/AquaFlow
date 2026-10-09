package com.example.aquaflow.service;

import com.example.aquaflow.entity.OrderTransfer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.OrderTransferMapper;
import com.example.aquaflow.service.impl.OrderWorkflowServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Direct production calls; SQL transaction/locking must be verified separately in an isolated DB. */
class OrderTransferDeliveryConsistencyTest {
    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @Test void successfulDeliveryClosesOnlyStaleColleagueTransferAndCustomerCancel() throws Exception {
        Fixture f = new Fixture();
        f.service.completeDelivery(1L, Map.of());
        verify(f.transfers).resolvePendingRequest(70L, "STAFF", "TRANSFER", "REJECTED", 20L);
        verify(f.transfers).resolvePendingRequest(71L, "CUSTOMER", "CANCEL_REQUEST", "REJECTED", 20L);
        verify((com.example.aquaflow.mapper.OrderCancelResultMapper) f.dependencies.get(com.example.aquaflow.mapper.OrderCancelResultMapper.class))
                .insert(eq(71L), contains("取消申请未生效"), eq(true), eq(20L));
        verify(f.transfers, never()).resolvePendingByKind(eq(1L), anyString(), anyString(), anyLong());
        verify(f.transfers, never()).resolvePendingByKind(eq(1L), eq("STAFF"), anyString(), anyLong());
    }

    @Test void failedCancellationResultMustRejectDeliveryTransaction() throws Exception {
        Fixture f = new Fixture();
        when(((com.example.aquaflow.mapper.OrderCancelResultMapper) f.dependencies.get(com.example.aquaflow.mapper.OrderCancelResultMapper.class))
                .insert(anyLong(), anyString(), anyBoolean(), anyLong())).thenReturn(0);
        assertThrows(BusinessException.class, () -> f.service.completeDelivery(1L, Map.of()));
        verify(f.transfers, never()).resolvePendingRequest(70L, "STAFF", "TRANSFER", "REJECTED", 20L);
    }

    @Test void alreadyDeliveredTransferCannotReassignTheOwner() throws Exception {
        Fixture f = new Fixture(); f.order.setStatus(4);
        AuthContext.set(new AuthContext.AuthUser(21L, "staff", "DELIVERY", 8L));
        when(f.orders.reassignStaffIf(1L, 21L, 20L)).thenReturn(1);
        assertThrows(BusinessException.class, () -> f.service.claimTransfer(1L));
        verify(f.orders, never()).reassignStaffIf(anyLong(), anyLong(), anyLong());
    }

    @Test void activeTransferAcceptanceChangesOnlyStaffAndKeepsPrimaryStatus() throws Exception {
        Fixture f = new Fixture();
        AuthContext.set(new AuthContext.AuthUser(21L, "staff", "DELIVERY", 8L));
        when(f.orders.reassignStaffIf(1L, 21L, 20L)).thenReturn(1);
        f.service.claimTransfer(1L);
        verify(f.orders).reassignStaffIf(1L, 21L, 20L);
        verify(f.orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
    }

    @Test void repeatedAcceptedHandoffMustNotFallBackToAnotherClaimWrite() throws Exception {
        Fixture f = new Fixture();
        f.order.setDeliveryStaffId(21L);
        AuthContext.set(new AuthContext.AuthUser(21L, "staff", "DELIVERY", 8L));
        when(f.transfers.findPendingByOrderAndKind(1L, "STAFF")).thenReturn(null);
        when(f.orders.claimIfUnassigned(1L, 21L)).thenReturn(1);
        assertThrows(BusinessException.class, () -> f.service.claimTransfer(1L));
        verify(f.orders, never()).claimIfUnassigned(anyLong(), anyLong());
        verify(f.orders, never()).appendSpecialNote(anyLong(), anyString());
        verifyNoInteractions((AuditLogService) f.dependencies.get(AuditLogService.class));
    }

    private static class Fixture {
        final OrderWorkflowServiceImpl service = new OrderWorkflowServiceImpl();
        final Map<Class<?>, Object> dependencies = new HashMap<>();
        final OrderMapper orders;
        final OrderTransferMapper transfers;
        final Orders order = new Orders();
        Fixture() throws Exception {
            for (var field : OrderWorkflowServiceImpl.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    Object dependency = mock(field.getType()); dependencies.put(field.getType(), dependency);
                    ReflectionTestUtils.setField(service, field.getName(), dependency);
                }
            }
            orders = (OrderMapper) dependencies.get(OrderMapper.class);
            transfers = (OrderTransferMapper) dependencies.get(OrderTransferMapper.class);
            order.setId(1L); order.setCustomerId(9L); order.setStationId(8L); order.setDeliveryStationId(8L);
            order.setStatus(2); order.setDeliveryStaffId(20L); order.setPaymentMethod(3); order.setPaymentStatus(2);
            order.setQuantity(0);
            when(orders.getByIdForUpdate(1L)).thenReturn(order);
            when(orders.updateStatusIf(1L, 2, 4)).thenReturn(1);
            OrderTransfer transfer = new OrderTransfer(); transfer.setId(70L); transfer.setKind("STAFF");
            transfer.setSubKind("TRANSFER"); transfer.setFromStaffId(20L); transfer.setToStaffId(21L);
            when(transfers.findPendingByOrderAndKind(1L, "STAFF")).thenReturn(transfer);
            OrderTransfer cancellation = new OrderTransfer(); cancellation.setId(71L); cancellation.setKind("CUSTOMER");
            cancellation.setSubKind("CANCEL_REQUEST");
            when(transfers.findPendingByOrderAndKind(1L, "CUSTOMER")).thenReturn(cancellation);
            when(transfers.resolvePendingRequest(71L, "CUSTOMER", "CANCEL_REQUEST", "REJECTED", 20L)).thenReturn(1);
            when(((com.example.aquaflow.mapper.OrderCancelResultMapper) dependencies.get(com.example.aquaflow.mapper.OrderCancelResultMapper.class))
                    .insert(anyLong(), anyString(), anyBoolean(), anyLong())).thenReturn(1);
            when(transfers.resolvePendingRequest(70L, "STAFF", "TRANSFER", "REJECTED", 20L)).thenReturn(1);
            when(transfers.resolvePendingRequest(eq(70L), eq("STAFF"), eq("TRANSFER"), anyString(), anyLong())).thenReturn(1);
            when(((PaymentService) dependencies.get(PaymentService.class)).hasPaidRecord(1L)).thenReturn(true);
            when(((BarrelLedgerService) dependencies.get(BarrelLedgerService.class)).applyDelivery(eq(1L), eq(9L), eq(8L), anyMap(), eq(20L))).thenReturn(new BarrelLedgerService.DeliveryOutcome());
            AuthContext.set(new AuthContext.AuthUser(20L, "staff", "DELIVERY", 8L));
        }
    }
}
