package com.example.aquaflow.service;

import com.example.aquaflow.dto.RecordDisplayNameRow;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.OrderBarrelExceptionService.OrderBarrelExceptionDTO;
import com.example.aquaflow.service.impl.BarrelServiceImpl;
import com.example.aquaflow.service.impl.OrderBarrelExceptionServiceImpl;
import com.example.aquaflow.service.impl.StationAdjustmentServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际服务/DTO；仅替换持久层，不连接业务库，不发起付款、退款或资产调整。 */
class RecordDisplayNameServiceTest {
    private final RecordDisplayNameMapper mapper = mock(RecordDisplayNameMapper.class);
    private final InterStationSettlementMapper stations = mock(InterStationSettlementMapper.class);
    private final RecordDisplayNameService names = new RecordDisplayNameService(mapper, stations);

    @AfterEach void clear() { AuthContext.clear(); }
    private void manager(long station) { AuthContext.set(new AuthContext.AuthUser(7L, "staff", "STATION_MANAGER", station)); }
    private BarrelRecord barrel(long id, long station, int type) {
        var r = new BarrelRecord(); r.setId(id); r.setStationId(station); r.setType(type);
        r.setCustomerId(7L); r.setProductId(5L); r.setQuantity(2); r.setDepositRefund(new BigDecimal("60.00")); return r;
    }
    private RecordDisplayNameRow label(long id) {
        var r = new RecordDisplayNameRow(); r.setId(id); r.setCustomerName("本站客户");
        r.setProductName("已核商品"); r.setProductSpec("18.5L"); r.setStationName("办理站"); return r;
    }
    private OrderBarrelExceptionDTO exception(long id, long station) {
        var r = new OrderBarrelExceptionDTO(); r.setId(id); r.setStationId(station); r.setCustomerId(7L); return r;
    }

    @Test void emptyOrForeignRecordsNeverBecomeAnUnboundedNameQuery() {
        names.barrels(null, List.of(barrel(1, 11, 2)));
        names.barrels(11L, List.of(barrel(1, 22, 2), barrel(2, 11, 8)));
        names.exceptions(11L, List.of(exception(1, 22)));
        names.stations(List.of(new HashMap<>(Map.of("otherStationId", 1.5))), "otherStation");
        verifyNoInteractions(mapper, stations);
    }

    @Test void aLargeReturnPageUsesOneBoundedBatchAndPreservesOriginalFacts() {
        List<BarrelRecord> rows = new ArrayList<>(); for (long id = 1; id <= 200; id++) rows.add(barrel(id, 11, 2));
        rows.add(barrel(999, 22, 2)); rows.add(barrel(1000, 11, 8));
        when(mapper.barrelNames(eq(11L), anyList())).thenReturn(List.of(label(1), label(2)));
        names.barrels(11L, rows);
        assertEquals("本站客户", rows.get(0).getCustomerName()); assertEquals("已核商品", rows.get(0).getProductName());
        assertEquals("18.5L", rows.get(0).getProductSpec()); assertNull(rows.get(2).getProductName());
        assertNull(rows.get(200).getCustomerName()); assertNull(rows.get(201).getProductName());
        assertEquals(7L, rows.get(0).getCustomerId()); assertEquals(5L, rows.get(0).getProductId());
        assertEquals(2, rows.get(0).getQuantity()); assertEquals(new BigDecimal("60.00"), rows.get(0).getDepositRefund());
        verify(mapper, times(1)).barrelNames(eq(11L), argThat(ids -> ids.size() == 200 && !ids.contains(999L) && !ids.contains(1000L)));
        verifyNoMoreInteractions(mapper); verifyNoInteractions(stations);
    }

    @Test void absentRowsAndDeletedNamesStayAbsentInsteadOfBorrowingAnotherRecord() {
        var a = barrel(1, 11, 2); var b = barrel(2, 11, 2);
        var deleted = new RecordDisplayNameRow(); deleted.setId(1L);
        when(mapper.barrelNames(11L, List.of(1L, 2L))).thenReturn(List.of(deleted, label(999)));
        names.barrels(11L, List.of(a, b));
        assertNull(a.getCustomerName()); assertNull(a.getProductName()); assertNull(b.getProductName());
        assertEquals(1L, a.getId()); assertEquals(2L, b.getId());
    }

    @Test void exceptionProfileAndReceiverSnapshotRemainDistinctAndBatchScoped() {
        var cross = exception(1, 11); var own = exception(2, 11); var foreign = exception(3, 22);
        var snapshot = new RecordDisplayNameRow(); snapshot.setId(1L); snapshot.setReceiverName("订单收件人");
        when(mapper.exceptionNames(11L, List.of(1L, 2L))).thenReturn(List.of(snapshot, label(2)));
        names.exceptions(11L, List.of(cross, own, foreign));
        assertNull(cross.getCustomerName()); assertEquals("订单收件人", cross.getReceiverName());
        assertEquals("本站客户", own.getCustomerName()); assertNull(foreign.getCustomerName());
        verify(mapper).exceptionNames(11L, List.of(1L, 2L)); verifyNoMoreInteractions(mapper);
    }

    @Test void allStationRolesShareOneDistinctBatchAndUnknownStationStaysNull() {
        var a = new LinkedHashMap<String,Object>(Map.of("fromStationId", 11L, "toStationId", "22"));
        var b = new LinkedHashMap<String,Object>(Map.of("responsibleStationId", 11L, "toStationId", 33L));
        when(stations.stationNames(List.of(11L, 22L, 33L))).thenReturn(List.of(Map.of("id", 11L, "name", "本站"), Map.of("id", 22L, "name", "合作站")));
        names.stations(List.of(a, b), "fromStation", "toStation", "responsibleStation");
        assertEquals("本站", a.get("fromStationName")); assertEquals("合作站", a.get("toStationName")); assertNull(b.get("toStationName"));
        assertEquals("22", a.get("toStationId")); assertEquals(33L, b.get("toStationId"));
        verify(stations).stationNames(List.of(11L, 22L, 33L)); verifyNoMoreInteractions(stations); verifyNoInteractions(mapper);
    }

    private StationAdjustmentServiceImpl adjustment(Product product) {
        var s = new StationAdjustmentServiceImpl(); var products = mock(ProductMapper.class); var customers = mock(CustomerMapper.class);
        when(products.getById(5L)).thenReturn(product);
        ReflectionTestUtils.setField(s, "productMapper", products); ReflectionTestUtils.setField(s, "customerMapper", customers); return s;
    }
    private StationAdjustment adjustmentRecord() {
        var a = new StationAdjustment(); a.setId(9L); a.setStationId(11L); a.setCustomerId(7L); a.setProductId(5L); return a;
    }
    @Test void adjustmentReadsGlobalAndOwnPrivateProductNamesWithoutChangingTheRecord() {
        manager(11);
        for (Long owner : Arrays.asList(null, 11L)) {
            var product = new Product(); product.setOwnerStationId(owner); product.setName("历史商品名"); product.setSpec("18.5L"); product.setStatus(0);
            var original = adjustmentRecord(); var view = adjustment(product).decorate(original);
            assertEquals("历史商品名", view.get("productName")); assertEquals("18.5L", view.get("productSpec"));
            assertEquals(5L, original.getProductId()); assertEquals(11L, original.getStationId());
        }
    }
    @Test void adjustmentMissingOrOtherStationPrivateProductKeepsItsIdentifierWithoutName() {
        manager(11); var foreign = new Product(); foreign.setName("不可见商品"); foreign.setOwnerStationId(22L);
        assertNull(adjustment(foreign).decorate(adjustmentRecord()).get("productName"));
        assertNull(adjustment(null).decorate(adjustmentRecord()).get("productName"));
        assertEquals(5L, adjustment(null).decorate(adjustmentRecord()).get("productId"));
    }
    @Test void adjustmentDenialHappensBeforeReadingProductOrCustomerNames() {
        manager(22); var s = new StationAdjustmentServiceImpl();
        assertThrows(BusinessException.class, () -> s.decorate(adjustmentRecord()));
    }

    @Test void exceptionListActuallyAttachesNamesAfterItsStationScopedPageRead() {
        var s = new OrderBarrelExceptionServiceImpl(); var exceptions = mock(OrderBarrelExceptionMapper.class);
        var ex = new OrderBarrelException(); ex.setId(1L); ex.setStationId(11L); ex.setCustomerId(7L); ex.setStatus("STAFF_RECORDED");
        when(exceptions.listByStation(eq(11L), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(ex));
        when(mapper.exceptionNames(11L, List.of(1L))).thenReturn(List.of(label(1)));
        ReflectionTestUtils.setField(s, "exceptionMapper", exceptions); ReflectionTestUtils.setField(s, "displayNames", names);
        var page = s.listExceptions(11L, new OrderBarrelExceptionService.ExceptionQuery());
        assertEquals("本站客户", page.getRecords().get(0).getCustomerName()); verify(mapper).exceptionNames(11L, List.of(1L));
    }
    @Test void historicalUndeliveredReturnsKeepAmountsAndReceiveNamesInOneBatch() {
        var s = new BarrelServiceImpl(); var records = mock(BarrelRecordMapper.class); var r = barrel(1, 11, 2);
        when(records.listRefundUndelivered(11L, 200)).thenReturn(List.of(r)); when(records.countRefundUndelivered(11L)).thenReturn(1);
        when(records.sumRefundUndelivered(11L)).thenReturn(new BigDecimal("60.00"));
        when(mapper.barrelNames(11L, List.of(1L))).thenReturn(List.of(label(1)));
        ReflectionTestUtils.setField(s, "barrelRecordMapper", records); ReflectionTestUtils.setField(s, "displayNames", names);
        var response = s.listRefundUndelivered(11L, null);
        assertEquals(new BigDecimal("60.00"), response.get("amount")); assertEquals("已核商品", r.getProductName());
        assertEquals(1, response.get("count")); verify(mapper).barrelNames(11L, List.of(1L));
    }
    @Test void aftercareAddsOnlyNamesForAlreadyAuthorizedPartiesAndDoesNotChangeItsActions() {
        manager(11); var locks = mock(ConsumptionRefundMapper.class); var cases = mock(StationAftercareMapper.class);
        var settlements = mock(StationRecoveryMapper.class); var order = new Orders(); order.setId(81L); order.setStationId(11L); order.setDeliveryStationId(22L); order.setSettleStationId(22L);
        when(locks.lockOrder(81L)).thenReturn(order);
        when(cases.originalConsumption(81L)).thenReturn(new BigDecimal("20.00"));
        when(cases.sources(81L)).thenReturn(List.of(Map.of("refund_payment_id", 9L, "refundStationId", 11L, "water_amount", BigDecimal.TEN)));
        when(stations.stationNames(List.of(11L, 22L))).thenReturn(List.of(Map.of("id", 11L, "name", "退款站"), Map.of("id", 22L, "name", "分担站")));
        var response = new StationAftercareService(cases, locks, settlements, names).view(81L, 11L);
        var row = (Map<?,?>) ((List<?>) response.get("items")).get(0);
        assertEquals("退款站", row.get("refundStationName")); assertEquals("分担站", row.get("otherStationName"));
        assertEquals(false, row.get("canPropose")); assertEquals(11L, row.get("refundStationId")); verify(stations).stationNames(List.of(11L, 22L));
    }
    @Test void foreignAftercareRequestCannotReachTheNameQuery() {
        manager(33); var locks = mock(ConsumptionRefundMapper.class); var order = new Orders(); order.setStationId(11L); order.setDeliveryStationId(22L); order.setSettleStationId(22L);
        when(locks.lockOrder(81L)).thenReturn(order);
        var s = new StationAftercareService(mock(StationAftercareMapper.class), locks, mock(StationRecoveryMapper.class), names);
        assertThrows(BusinessException.class, () -> s.view(81L, 33L)); verifyNoInteractions(mapper, stations);
    }
    @Test void preciseReturnNameReadHappensOnlyAfterTheStationScopedOriginalRecordExists() {
        var service = new BusinessWaitingService(); var waiting = mock(BusinessWaitingMapper.class);
        var returns = mock(ApprovedBarrelReturnService.class); var ledger = mock(BarrelLedgerService.class);
        var original = barrel(1, 11, 2); when(waiting.returnRecord(1L, 11L)).thenReturn(original);
        when(mapper.barrelNames(11L, List.of(1L))).thenReturn(List.of(label(1)));
        ReflectionTestUtils.setField(service, "mapper", waiting); ReflectionTestUtils.setField(service, "displayNames", names);
        ReflectionTestUtils.setField(service, "approvedReturns", returns); ReflectionTestUtils.setField(service, "barrelLedger", ledger);
        assertEquals("本站客户", service.returnRecord(11L, 1L).getCustomerName());
        assertThrows(BusinessException.class, () -> service.returnRecord(11L, 2L));
        verify(mapper, times(1)).barrelNames(11L, List.of(1L)); verifyNoMoreInteractions(mapper);
    }
    @Test void waitingSectionsShareOneStationNameReadWithoutChangingPendingCounts() {
        var service = new BusinessWaitingService(); var waiting = mock(BusinessWaitingMapper.class); var policy = mock(BarrelBusinessPolicy.class);
        when(policy.hasSchema()).thenReturn(true);
        when(waiting.countWaitingStock(11L)).thenReturn(1);
        when(waiting.countWaitingReturns(11L)).thenReturn(Map.of("returnsTotal", 1, "returnRefund", 0));
        when(waiting.countWaitingRecoveries(11L)).thenReturn(Map.of("recoverySend", 1, "recoveryReceive", 0));
        when(waiting.countWaitingBarrels(11L)).thenReturn(Map.of("barrelHandover", 0, "barrelDispute", 0));
        var since = java.time.LocalDateTime.of(2026, 10, 24, 8, 0);
        when(waiting.waitingStock(11L)).thenReturn(List.of(Map.of("orderId", 81L, "responsibleStationId", 11L, "waitingSinceTime", since)));
        when(waiting.delayedReturns(11L)).thenReturn(List.of(Map.of("recordId", 1L, "responsibleStationId", 11L, "status", "APPLIED", "waitingSinceTime", since)));
        when(waiting.waitingRecoveries(11L)).thenReturn(List.of(Map.of("orderId", 82L, "responsibleStationId", 11L, "fromStationId", 11L, "toStationId", 22L, "waitingSinceTime", since, "nextAction", "sent")));
        when(stations.stationNames(List.of(11L, 22L))).thenReturn(List.of(Map.of("id", 11L, "name", "本站名"), Map.of("id", 22L, "name", "合作站名")));
        ReflectionTestUtils.setField(service, "mapper", waiting); ReflectionTestUtils.setField(service, "policy", policy); ReflectionTestUtils.setField(service, "displayNames", names);
        var response = service.waiting(11L);
        var first = (Map<?,?>) ((List<?>) response.get("stock")).get(0);
        var recovery = (Map<?,?>) ((List<?>) response.get("recoveries")).get(0);
        assertEquals("本站名", first.get("responsibleStationName")); assertEquals("合作站名", recovery.get("toStationName"));
        assertEquals(1, ((Map<?,?>) response.get("counts")).get("waitingStock"));
        verify(stations, times(1)).stationNames(List.of(11L, 22L)); verifyNoMoreInteractions(stations);
    }
    @Test void webSerializerIncludesReadOnlyNamesAndKeepsReceiverSeparateFromCustomerProfile() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var record = barrel(1, 11, 2); record.setCustomerName("本站客户"); record.setProductName("历史商品"); record.setStationName("办理站");
        var tree = json.readTree(json.writeValueAsString(record));
        assertEquals("本站客户", tree.path("customerName").asString()); assertEquals("历史商品", tree.path("productName").asString());
        assertEquals(5, tree.path("productId").asInt()); assertEquals("办理站", tree.path("stationName").asString());
        var cross = exception(2, 11); cross.setReceiverName("订单收件人");
        var dto = json.readTree(json.writeValueAsString(cross));
        assertFalse(dto.hasNonNull("customerName")); assertEquals("订单收件人", dto.path("receiverName").asString());
    }
}
