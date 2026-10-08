package com.example.aquaflow.service;

import com.example.aquaflow.entity.OrderTransfer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.OrderTransferMapper;
import com.example.aquaflow.service.impl.OrderWorkflowServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** Calls production commands without Spring, JDBC, or any database fixture. */
class OrderTransferCommandGuardTest {
    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @ParameterizedTest
    @CsvSource({"TRANSFER,true", "TRANSFER,false", "CANCEL_REQUEST,true", "CANCEL_REQUEST,false", "REDISPATCH,true", "REDISPATCH,false", "NONE,true", "NONE,false"})
    void returnDecisionMustRejectOtherRequestTypesBeforeWriting(String subKind, boolean approve) {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, "STATION_MANAGER", 8L, 2);
        if (!subKind.equals("NONE")) {
            OrderTransfer request = new OrderTransfer();
            request.setKind(OrderTransfer.KIND_STAFF);
            request.setSubKind(subKind);
            request.setStatus(OrderTransfer.STATUS_PENDING);
            when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        }
        when(orders.updateStatusIf(anyLong(), anyInt(), anyInt())).thenReturn(1);
        assertThrows(BusinessException.class, () -> { if (approve) service.approveReturn(1L); else service.rejectReturn(1L); });
        verify(orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
        verify(orders, never()).clearDeliveryStaff(anyLong());
        verify(transfers, never()).resolvePendingByKind(anyLong(), anyString(), anyString(), anyLong());
        verify(transfers, never()).resolvePendingRequest(any(), anyString(), anyString(), anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"true,1", "true,2", "false,1", "false,2"})
    void correctReturnDecisionResolvesOnlyInspectedRequestAndPreservesOrder(boolean approve, int status) {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, "STATION_MANAGER", 8L, status);
        OrderTransfer request = new OrderTransfer(); request.setId(70L);
        request.setSubKind(OrderTransfer.SUB_RETURN_STATION);
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        when(transfers.resolvePendingRequest(eq(70L), eq(OrderTransfer.KIND_STAFF), eq(OrderTransfer.SUB_RETURN_STATION), anyString(), eq(20L))).thenReturn(1);
        when(orders.updateStatusIf(anyLong(), anyInt(), anyInt())).thenReturn(1);
        if (approve) service.approveReturn(1L); else service.rejectReturn(1L);
        verify(transfers).resolvePendingRequest(70L, OrderTransfer.KIND_STAFF, OrderTransfer.SUB_RETURN_STATION,
                approve ? OrderTransfer.STATUS_APPROVED : OrderTransfer.STATUS_REJECTED, 20L);
        if (approve) verify(orders).clearDeliveryStaff(1L);
        else verify(orders, never()).clearDeliveryStaff(anyLong());
        verify(transfers, never()).resolvePendingByKind(anyLong(), anyString(), anyString(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void alreadyResolvedRequestCannotChangeTheOrder(boolean approve) {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, "STATION_MANAGER", 8L, 2);
        OrderTransfer request = new OrderTransfer(); request.setId(70L);
        request.setSubKind(OrderTransfer.SUB_RETURN_STATION);
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        assertThrows(BusinessException.class, () -> { if (approve) service.approveReturn(1L); else service.rejectReturn(1L); });
        verify(orders, never()).clearDeliveryStaff(anyLong());
        verify(orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @CsvSource({"DELIVERY,8,2", "STATION_MANAGER,9,2", "STATION_MANAGER,8,3", "STATION_MANAGER,8,4", "STATION_MANAGER,8,5"})
    void returnDecisionRejectsWrongRoleStationOrTerminalState(String role, long station, int status) {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, role, station, status);
        OrderTransfer request = new OrderTransfer();
        request.setKind(OrderTransfer.KIND_STAFF);
        request.setSubKind(OrderTransfer.SUB_RETURN_STATION);
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        when(orders.updateStatusIf(anyLong(), anyInt(), anyInt())).thenReturn(1);
        assertThrows(BusinessException.class, () -> service.approveReturn(1L));
        verify(orders, never()).clearDeliveryStaff(anyLong());
    }

    private OrderWorkflowServiceImpl fixture(OrderMapper orders, OrderTransferMapper transfers, String role, long station, int status) {
        Orders order = new Orders();
        order.setId(1L); order.setStationId(8L); order.setDeliveryStationId(8L);
        order.setDeliveryStaffId(20L); order.setStatus(status);
        when(orders.getByIdForUpdate(1L)).thenReturn(order);
        AuthContext.set(new AuthContext.AuthUser(20L, "staff", role, station));
        OrderWorkflowServiceImpl service = new OrderWorkflowServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "orderTransferMapper", transfers);
        ReflectionTestUtils.setField(service, "auditLogService", mock(AuditLogService.class));
        return service;
    }
}
