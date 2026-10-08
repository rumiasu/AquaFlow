package com.example.aquaflow.service;

import com.example.aquaflow.aspect.RequireRoleAspect;
import com.example.aquaflow.constant.PendingItem;
import com.example.aquaflow.controller.ManagerPendingSummaryController;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不连接 MySQL；执行真实聚合、控制器和权限切面，SQL/业务写动作另由集成测试验证。 */
class BusinessPendingSummaryTest {
    final BusinessWaitingMapper mapper = mock(BusinessWaitingMapper.class);
    final BarrelBusinessPolicy policy = mock(BarrelBusinessPolicy.class);
    final ApprovedBarrelReturnService returns = mock(ApprovedBarrelReturnService.class);
    final BarrelLedgerService ledger = mock(BarrelLedgerService.class);
    final BusinessWaitingService service = new BusinessWaitingService();

    BusinessPendingSummaryTest() {
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "policy", policy);
        ReflectionTestUtils.setField(service, "approvedReturns", returns);
        ReflectionTestUtils.setField(service, "barrelLedger", ledger);
        when(policy.hasSchema()).thenReturn(true);
        when(mapper.countWaitingReturns(anyLong())).thenReturn(Map.of("returnsTotal", 1, "returnRefund", 1));
        when(mapper.countWaitingRecoveries(anyLong())).thenReturn(Map.of("recoverySend", 2, "recoveryReceive", 3));
        when(mapper.countWaitingBarrels(anyLong())).thenReturn(Map.of("barrelHandover", 4, "barrelDispute", 5));
    }
    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test void countsUseFullAggregationRatherThanLimitedLists() {
        when(mapper.countWaitingStock(1L)).thenReturn(201);
        var counts = service.pendingCounts(1L);
        assertEquals(201, counts.get("waitingStock"));
        assertEquals(5, counts.get("recoveriesTotal"));
        assertEquals(9, counts.get("barrelsTotal"));
        verify(mapper, never()).waitingStock(any());
        verify(mapper, never()).waitingRecoveries(any());
        verify(mapper, never()).waitingBarrels(any());
    }

    @Test void waitingRowsKeepOriginalIdsAndServerActionTexts() {
        when(mapper.delayedReturns(1L)).thenReturn(List.of(Map.of("recordId", 7L, "status", "RECEIVED")));
        when(mapper.waitingRecoveries(1L)).thenReturn(List.of(Map.of("orderId", 8L, "nextAction", "received")));
        var data = service.waiting(1L);
        assertEquals(200, data.get("limit"));
        var rows = (List<Map<String,Object>>) data.get("returns");
        assertEquals(7L, rows.get(0).get("recordId"));
        assertEquals("refund", rows.get(0).get("nextAction"));
        assertTrue(rows.get(0).get("waitingReason").toString().contains("尚未实际退款"));
        var recovery = (List<Map<String,Object>>) data.get("recoveries");
        assertEquals("确认返还款已实际到账", recovery.get(0).get("nextActionText"));
    }

    @Test void unavailableSchemaOrBrokenReadDoesNotBecomeZero() {
        when(policy.hasSchema()).thenReturn(false);
        assertNull(service.pendingCounts(1L).get("returnRefund"));
        verify(mapper, never()).countWaitingReturns(any());
        when(policy.hasSchema()).thenReturn(true);
        when(mapper.countWaitingReturns(1L)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.pendingCounts(1L));
        doReturn(Map.of()).when(mapper).countWaitingReturns(1L);
        assertThrows(BusinessException.class, () -> service.pendingCounts(1L));
    }

    @Test void originalReturnLookupRejectsOtherStationBeforeReadingDetail() {
        assertThrows(BusinessException.class, () -> service.returnRecord(2L, 7L));
        verify(mapper).returnRecord(7L, 2L);
        verifyNoInteractions(returns);
        var record = new BarrelRecord(); record.setId(7L); record.setStationId(1L); record.setCustomerId(3L); record.setProductId(5L);
        when(mapper.returnRecord(7L, 1L)).thenReturn(record);
        when(ledger.overQty(3L, 1L, 5L)).thenReturn(2);
        assertSame(record, service.returnRecord(1L, 7L));
        assertEquals(2, record.getOwedBuckets());
        verify(ledger).overQty(3L, 1L, 5L);
        verify(returns).detail(7L);
    }

    private ManagerPendingSummaryController controller() {
        var c = new ManagerPendingSummaryController();
        ReflectionTestUtils.setField(c, "businessWaitingService", service);
        ReflectionTestUtils.setField(c, "levelOverridesRaw", "returnRefund:P0,waitingStock:P2,barrelDispute:INVALID");
        ReflectionTestUtils.setField(c, "receivableService", mock(ReceivableService.class));
        ReflectionTestUtils.setField(c, "orderMapper", mock(OrderMapper.class));
        ReflectionTestUtils.setField(c, "paymentRecordMapper", mock(PaymentRecordMapper.class));
        ReflectionTestUtils.setField(c, "staffStationApplicationMapper", mock(StaffStationApplicationMapper.class));
        ReflectionTestUtils.setField(c, "customerEnterpriseApplyMapper", mock(CustomerEnterpriseApplyMapper.class));
        ReflectionTestUtils.setField(c, "barrelRecordMapper", mock(BarrelRecordMapper.class));
        ReflectionTestUtils.setField(c, "staffPayrollMapper", mock(StaffPayrollMapper.class));
        ReflectionTestUtils.setField(c, "alertLogMapper", mock(AlertLogMapper.class));
        ReflectionTestUtils.setField(c, "grossProfitMapper", mock(GrossProfitMapper.class));
        ReflectionTestUtils.setField(c, "interStationSettlementService", mock(InterStationSettlementService.class));
        var exceptions = mock(OrderBarrelExceptionService.class);
        when(exceptions.listExceptions(anyLong(), any())).thenReturn(new OrderBarrelExceptionService.Page<>(List.of(), 0, 1, 1));
        ReflectionTestUtils.setField(c, "orderBarrelExceptionService", exceptions);
        return c;
    }
    private Map<String,Object> item(Map<String,Object> data, String key) {
        return ((List<Map<String,Object>>) data.get("items")).stream().filter(r -> key.equals(r.get("key"))).findFirst().orElseThrow();
    }

    @Test void controllerPreservesOverridesAndP0NonzeroItemSemantics() {
        AuthContext.set(new AuthContext.AuthUser(9L, "staff", "STATION_MANAGER", 1L));
        when(mapper.countWaitingStock(1L)).thenReturn(201);
        var data = controller().summary().getData();
        assertEquals(PendingItem.values().length, ((List<?>) data.get("items")).size());
        assertEquals("P2", item(data,"waitingStock").get("level"));
        assertEquals("P0", item(data,"returnRefund").get("level"));
        assertEquals("P1", item(data,"barrelDispute").get("level"));
        assertEquals(1, data.get("p0Total"));
        assertNull(item(data,"returnRefund").get("amount"));
        assertEquals(true, data.get("complete"));
        assertEquals(216, data.get("businessWaitingTotal"), "six full responsibility counts, not a truncated refund list");
        verify(mapper).countWaitingReturns(1L);
    }

    @Test void controllerReportsUnavailableItemsAndRequiresBoundStation() {
        var c = controller();
        assertThrows(BusinessException.class, c::summary);
        when(policy.hasSchema()).thenReturn(false);
        AuthContext.set(new AuthContext.AuthUser(9L, "staff", "STATION_MANAGER", 1L));
        var data = c.summary().getData();
        assertEquals(false, data.get("complete"));
        assertNull(data.get("businessWaitingTotal"), "unavailable schema must not become a zero badge");
        assertNull(item(data,"returnRefund").get("count"));
        assertEquals(false, item(data,"returnRefund").get("available"));
    }

    @Test void actualRoleAspectRejectsCustomerDeliveryAndUnselectedForReadAndSummary() {
        var factory = new AspectJProxyFactory(controller());
        factory.addAspect(new RequireRoleAspect());
        ManagerPendingSummaryController proxy = factory.getProxy();
        for (String role : List.of("DELIVERY", "UNSELECTED", "customer")) {
            AuthContext.set(new AuthContext.AuthUser(9L, role.equals("customer") ? "customer" : "staff", role, 1L));
            assertThrows(BusinessException.class, proxy::summary);
            assertThrows(BusinessException.class, () -> proxy.returnRecord(7L));
        }
        verifyNoInteractions(mapper);
    }
}
