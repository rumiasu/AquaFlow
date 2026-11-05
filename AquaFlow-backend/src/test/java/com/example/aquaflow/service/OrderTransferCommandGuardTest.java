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
        request.setFromStaffId(20L);
        request.setFromStationId(8L);
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        when(transfers.resolvePendingRequest(eq(70L), eq(OrderTransfer.KIND_STAFF), eq(OrderTransfer.SUB_RETURN_STATION), anyString(), eq(20L))).thenReturn(1);
        when(orders.updateStatusIf(anyLong(), anyInt(), anyInt())).thenReturn(1);
        // 待配送且已分配的订单，拒绝退回要靠申请时留的来源状态区分“未接单”和“配送中”。
        java.util.Map<String,Object> evidence = new java.util.HashMap<>();
        evidence.put("id", 99L);
        evidence.put("detail", "{sourceStatus=" + status + ", transferId=70, deliveryStaffId=20}");
        when(orders.latestStaffReturnEvent(1L)).thenReturn(evidence);
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
        request.setFromStaffId(20L);
        request.setFromStationId(8L);
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        when(orders.updateStatusIf(anyLong(), anyInt(), anyInt())).thenReturn(1);
        // 申请已被并发处理：resolve 返回 0；approve 的状态 CAS 成功后必须回滚/停止，
        // 不能再清配送员。reject 在恢复状态前就会因申请已失效而停止。
        assertThrows(BusinessException.class, () -> { if (approve) service.approveReturn(1L); else service.rejectReturn(1L); });
        verify(orders, never()).clearDeliveryStaff(anyLong());
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

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void changedAssigneeCannotUseOldRequestForNewFulfillment(boolean approve) {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, "STATION_MANAGER", 8L, 2);
        OrderTransfer request = new OrderTransfer(); request.setId(70L);
        request.setSubKind(OrderTransfer.SUB_RETURN_STATION);
        request.setFromStaffId(21L);
        request.setFromStationId(8L);
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        assertThrows(BusinessException.class, () -> { if (approve) service.approveReturn(1L); else service.rejectReturn(1L); });
        verify(orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
        verify(transfers, never()).resolvePendingRequest(any(), anyString(), anyString(), anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void fulfillmentChangeAfterRequestBlocksOldRequestEvenForSameStaff(boolean approve) {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, "STATION_MANAGER", 8L, 2);
        OrderTransfer request = new OrderTransfer(); request.setId(70L);
        request.setSubKind(OrderTransfer.SUB_RETURN_STATION);
        request.setFromStaffId(20L);
        request.setFromStationId(8L);
        request.setCreateTime(java.time.LocalDateTime.now().minusMinutes(1));
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        java.util.Map<String,Object> change = new java.util.HashMap<>();
        change.put("id", 99L);
        change.put("action", "ASSIGN");
        change.put("eventTime", java.time.LocalDateTime.now());
        when(orders.latestFulfillmentEventSince(eq(1L), any())).thenReturn(change);
        assertThrows(BusinessException.class, () -> { if (approve) service.approveReturn(1L); else service.rejectReturn(1L); });
        verify(orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
        verify(transfers, never()).resolvePendingRequest(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void approveStatusCasFailureDoesNotResolveRequest() {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, "STATION_MANAGER", 8L, 2);
        OrderTransfer request = new OrderTransfer(); request.setId(70L);
        request.setSubKind(OrderTransfer.SUB_RETURN_STATION);
        request.setFromStaffId(20L);
        request.setFromStationId(8L);
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        when(orders.updateStatusIf(1L, 2, 1)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.approveReturn(1L));
        verify(transfers, never()).resolvePendingRequest(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void oldAuditBeforeRequestCannotGuessSourceStatus() {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, "STATION_MANAGER", 8L, 1);
        OrderTransfer request = new OrderTransfer(); request.setId(70L);
        request.setSubKind(OrderTransfer.SUB_RETURN_STATION);
        request.setFromStaffId(20L);
        request.setFromStationId(8L);
        request.setCreateTime(java.time.LocalDateTime.now());
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        java.util.Map<String,Object> event = new java.util.HashMap<>();
        event.put("id", 99L);
        event.put("detail", "");
        event.put("eventTime", java.time.LocalDateTime.now().minusMinutes(1));
        when(orders.latestStaffReturnEvent(1L)).thenReturn(event);
        assertThrows(BusinessException.class, () -> service.rejectReturn(1L));
        verify(orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
        verify(transfers, never()).resolvePendingRequest(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void statusCasFailureDoesNotResolveRequest() {
        OrderMapper orders = mock(OrderMapper.class);
        OrderTransferMapper transfers = mock(OrderTransferMapper.class);
        OrderWorkflowServiceImpl service = fixture(orders, transfers, "STATION_MANAGER", 8L, 1);
        OrderTransfer request = new OrderTransfer(); request.setId(70L);
        request.setSubKind(OrderTransfer.SUB_RETURN_STATION);
        request.setFromStaffId(20L);
        request.setFromStationId(8L);
        when(transfers.findPendingByOrderAndKind(1L, OrderTransfer.KIND_STAFF)).thenReturn(request);
        java.util.Map<String,Object> event = new java.util.HashMap<>();
        event.put("id", 99L);
        event.put("detail", "{sourceStatus=2, transferId=70, deliveryStaffId=20}");
        when(orders.latestStaffReturnEvent(1L)).thenReturn(event);
        when(orders.updateStatusIf(1L, 1, 2)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.rejectReturn(1L));
        verify(transfers, never()).resolvePendingRequest(any(), anyString(), anyString(), anyString(), any());
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
