package com.example.aquaflow.service;

import com.example.aquaflow.dto.BarrelRightPurchaseDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.BusinessTime;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实独立押金/封锁服务，mapper 为模型；不执行真实事务/数据库/渠道。 */
class IndependentBarrelPurchaseFenceTest {
    // SHA256('raw-key') 的支付表固定编号，不能错用原申请编号持锁。
    static final String ACTUAL = "BR:1cd0a1fd031655c0b42f04864c0a13d4c0a482fc2449031b9d1c519d68b0";
    final TicketPurchaseFenceMapper fences = mock(TicketPurchaseFenceMapper.class);
    final PaymentRecordMapper payments = mock(PaymentRecordMapper.class);
    final BarrelBusinessMapper barrels = mock(BarrelBusinessMapper.class);
    final ProductMapper catalog = mock(ProductMapper.class);
    final StationMapper stations = mock(StationMapper.class);
    final InventoryMapper inventory = mock(InventoryMapper.class);
    final BarrelLedgerService ledger = mock(BarrelLedgerService.class);
    final DepositRecordService deposits = mock(DepositRecordService.class);
    final PaymentService channel = mock(PaymentService.class);
    final Map<String, TicketPurchaseFence> fenceRows = new HashMap<>();
    final Map<String, PaymentRecord> paymentRows = new HashMap<>();
    final Map<String, BarrelRightPurchase> purchaseRows = new HashMap<>();
    final TicketPurchaseFenceService gate = new TicketPurchaseFenceService(fences, payments,
            new BusinessTime(Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC)));
    final IndependentBarrelService service = new IndependentBarrelService();
    @BeforeEach void setup() {
        var policy = mock(BarrelBusinessPolicy.class); when(policy.isEnabled()).thenReturn(true);
        var product = new Product(); product.setId(5L); product.setCategory(1); product.setStatus(1); product.setDeposit(new BigDecimal("30.00"));
        var station = new Station(); station.setId(1L); station.setStatus(1);
        var inv = new Inventory(); inv.setEnabled(1);
        when(catalog.getById(5L)).thenReturn(product); when(stations.getById(1L)).thenReturn(station);
        when(inventory.getByStationAndProduct(1L, 5L)).thenReturn(inv);
        Map<String, Object> fields = Map.of("policy", policy, "mapper", barrels, "productMapper", catalog, "stationMapper", stations,
                "inventoryMapper", inventory, "paymentMapper", payments, "ledger", ledger, "depositService", deposits,
                "paymentService", channel, "purchaseFenceService", gate);
        fields.forEach((name, value) -> ReflectionTestUtils.setField(service, name, value));
        when(fences.lock(anyLong(), anyString())).thenAnswer(call -> fenceRows.computeIfAbsent(call.getArgument(0) + "|" + call.getArgument(1), ignored -> new TicketPurchaseFence()));
        when(fences.closeIfOpen(anyLong(), anyString(), any())).thenAnswer(call -> {
            fenceRows.get(call.getArgument(0) + "|" + call.getArgument(1)).setClosedTime(call.getArgument(2)); return 1;
        });
        when(payments.getByCustomerAndIdempotencyKeyForUpdate(anyLong(), anyString())).thenAnswer(call -> paymentRows.get(call.getArgument(0) + "|" + call.getArgument(1)));
        doAnswer(call -> { PaymentRecord p = call.getArgument(0); p.setId(31L); paymentRows.put(p.getCustomerId() + "|" + p.getIdempotencyKey(), p); return null; }).when(payments).insert(any());
        when(payments.getById(31L)).thenAnswer(call -> paymentRows.values().iterator().next());
        when(channel.confirmMockChannelIfApplicable(31L)).thenAnswer(call -> paymentRows.values().iterator().next());
        doAnswer(call -> { BarrelRightPurchase p = call.getArgument(0); p.setId(41L); p.setStatus("PENDING");
            purchaseRows.put(p.getCustomerId() + "|" + p.getIdempotencyKey(), p); return null;
        }).when(barrels).insertPurchase(any());
        when(barrels.findPurchaseForUpdate(anyLong(), anyString())).thenAnswer(call -> purchaseRows.get(call.getArgument(0) + "|" + call.getArgument(1)));
        when(barrels.findPurchase(anyLong(), anyString())).thenAnswer(call -> purchaseRows.get(call.getArgument(0) + "|" + call.getArgument(1)));
    }
    BarrelRightPurchaseDTO body() {
        var dto = new BarrelRightPurchaseDTO(); dto.setStationId(1L); dto.setProductId(5L); dto.setQuantity(2); dto.setPaymentMethod(2); dto.setIdempotencyKey(" raw-key "); return dto;
    }
    @Test void sealedActualPaymentKeyRejectsLateBarrelPurchaseWithoutMoneyOrAssetWrite() {
        assertTrue(gate.closeUnregistered(7L, ACTUAL).closed());
        assertThrows(BusinessException.class, () -> service.purchase(7L, body()));
        verify(payments, never()).insert(any()); verify(barrels, never()).insertPurchase(any());
        verifyNoInteractions(catalog, stations, inventory, ledger, deposits, channel);
    }
    @Test void normalPurchaseLocksActualKeyThenCurrentReadsApplicationThenWritesOnePayment() {
        var result = service.purchase(7L, body()); assertEquals(31L, result.get("paymentId"));
        var order = inOrder(fences, barrels, payments);
        order.verify(fences).ensure(7L, ACTUAL); order.verify(fences).lock(7L, ACTUAL);
        order.verify(barrels).findPurchaseForUpdate(7L, "raw-key"); order.verify(payments).insert(any()); order.verify(barrels).insertPurchase(any());
        assertEquals(ACTUAL, paymentRows.values().iterator().next().getIdempotencyKey());
        assertEquals("raw-key", purchaseRows.values().iterator().next().getIdempotencyKey());
        assertEquals(1, paymentRows.values().iterator().next().getStatus());
        verifyNoInteractions(deposits);
    }
    @Test void originalBarrelMoneyMakesTicketCloseRefuseAndLeavesPaymentAndAssetsUntouched() {
        service.purchase(7L, body()); var original = paymentRows.values().iterator().next();
        clearInvocations(payments, barrels, ledger, deposits, channel);
        assertThrows(BusinessException.class, () -> gate.closeUnregistered(7L, ACTUAL));
        assertEquals(1, original.getStatus()); assertEquals(new BigDecimal("60.00"), original.getAmount());
        verify(fences, never()).closeIfOpen(any(), any(), any());
        verify(payments).getByCustomerAndIdempotencyKeyForUpdate(7L, ACTUAL);
        verifyNoMoreInteractions(payments); verifyNoInteractions(barrels, ledger, deposits, channel);
    }
    @Test void sealingRawApplicationKeyDoesNotSealDistinctActualPaymentKey() {
        assertTrue(gate.closeUnregistered(7L, "raw-key").closed());
        assertEquals(31L, service.purchase(7L, body()).get("paymentId"));
        verify(fences).lock(7L, ACTUAL); assertEquals(1, paymentRows.size());
    }
    @Test void fenceIsCustomerScoped() {
        assertTrue(gate.closeUnregistered(7L, ACTUAL).closed());
        assertEquals(31L, service.purchase(8L, body()).get("paymentId"));
        assertEquals(8L, paymentRows.values().iterator().next().getCustomerId());
        assertFalse(paymentRows.containsKey("7|" + ACTUAL));
    }
    @Test void replayAfterCatalogFailureReturnsOriginalAmountWithoutNewWrites() {
        service.purchase(7L, body()); clearInvocations(catalog, stations, inventory, ledger, deposits, channel, payments, barrels);
        when(catalog.getById(5L)).thenThrow(new AssertionError("must not reprice"));
        var replay = service.purchase(7L, body());
        assertEquals(new BigDecimal("60.00"), replay.get("amount"));
        verify(payments, never()).insert(any()); verify(barrels, never()).insertPurchase(any());
        verifyNoInteractions(catalog, stations, inventory, ledger, deposits, channel);
    }
}
