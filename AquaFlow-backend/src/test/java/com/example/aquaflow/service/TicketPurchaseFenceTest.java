package com.example.aquaflow.service;

import com.example.aquaflow.controller.TicketAccountController;
import com.example.aquaflow.dto.TicketPurchaseDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.TicketAccountServiceImpl;
import com.example.aquaflow.service.impl.TicketTierService;
import com.example.aquaflow.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实编排 + 替身 mapper；顺序/失败分支证据，不证明 InnoDB 持锁与真实代理提交。 */
class TicketPurchaseFenceTest {
    final TicketPurchaseFenceMapper fences = mock(TicketPurchaseFenceMapper.class);
    final PaymentRecordMapper payments = mock(PaymentRecordMapper.class);
    final TicketPurchaseFenceService service = new TicketPurchaseFenceService(fences, payments,
            new BusinessTime(Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneId.of("Asia/Shanghai"))));
    @BeforeEach void setup() {
        var f = new TicketPurchaseFence(); f.setCustomerId(7L); f.setIdempotencyKey("key");
        when(fences.lock(7L, "key")).thenReturn(f);
        when(fences.closeIfOpen(eq(7L), eq("key"), any())).thenReturn(1);
    }
    @AfterEach void clear() { AuthContext.clear(); }
    PaymentRecord original(int status) {
        var p = new PaymentRecord(); p.setId(9L); p.setCustomerId(7L); p.setStationId(1L);
        p.setTicketWaterTypeId(5L); p.setTicketQty(3); p.setPaymentMethod(1); p.setStatus(status);
        p.setAmount(new BigDecimal("24.00")); p.setTransactionNo("retained-receipt"); return p;
    }
    @Test void closesOnlyAfterSharedFenceLockAndCurrentRead() {
        var result = service.closeUnregistered(7L, " key ");
        assertTrue(result.closed()); assertNull(result.payment()); assertEquals("key", result.idempotencyKey());
        var order = inOrder(fences, payments);
        order.verify(fences).ensure(7L, "key"); order.verify(fences).lock(7L, "key");
        order.verify(payments).getByCustomerAndIdempotencyKeyForUpdate(7L, "key");
        order.verify(fences).closeIfOpen(eq(7L), eq("key"), any());
        verify(payments, never()).getByCustomerAndIdempotencyKey(any(), any());
        verify(payments, never()).insert(any());
    }
    @Test void sealedKeyBlocksLatePurchaseBeforePaymentOrCatalogRead() {
        var f = fences.lock(7L, "key"); f.setClosedTime(LocalDateTime.of(2026, 10, 2, 8, 0));
        var purchase = new TicketAccountServiceImpl(); var catalog = mock(ProductMapper.class);
        ReflectionTestUtils.setField(purchase, "purchaseFenceService", service);
        ReflectionTestUtils.setField(purchase, "paymentRecordMapper", payments);
        ReflectionTestUtils.setField(purchase, "productMapper", catalog);
        assertThrows(BusinessException.class, () -> purchase.purchaseTicket(7L, 5L, 3, 1, 1L, " key ", null, null));
        verifyNoInteractions(payments, catalog);
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4})
    void anyExistingPaymentIsReturnedUntouched(int status) {
        var p = original(status); when(payments.getByCustomerAndIdempotencyKeyForUpdate(7L, "key")).thenReturn(p);
        var result = service.closeUnregistered(7L, "key");
        assertFalse(result.closed()); assertSame(p, result.payment());
        assertEquals(status, p.getStatus()); assertEquals(new BigDecimal("24.00"), p.getAmount());
        assertEquals("retained-receipt", p.getTransactionNo()); assertNull(p.getPurchaseRequestDigest());
        verify(fences, never()).closeIfOpen(any(), any(), any()); verify(payments, never()).insert(any());
        verify(payments).getByCustomerAndIdempotencyKeyForUpdate(7L, "key");
        verifyNoMoreInteractions(payments);
    }
    @Test void otherPaymentKindRetainsMoneyAndRefusesCloseReceipt() {
        var p = original(1); p.setOrderId(11L); when(payments.getByCustomerAndIdempotencyKeyForUpdate(7L, "key")).thenReturn(p);
        assertThrows(BusinessException.class, () -> service.closeUnregistered(7L, "key"));
        assertEquals(1, p.getStatus()); verify(fences, never()).closeIfOpen(any(), any(), any());
    }
    @Test void repeatedCloseNeverReopensOrRewritesClosedTime() {
        var f = fences.lock(7L, "key"); f.setClosedTime(LocalDateTime.of(2026, 10, 2, 8, 0));
        assertTrue(service.closeUnregistered(7L, "key").closed());
        verify(fences, never()).closeIfOpen(any(), any(), any());
        assertThrows(BusinessException.class, () -> service.requireOpen(7L, "key"));
    }
    @Test void failedCloseCasAndMissingLockAreNotClosedSuccess() {
        when(fences.closeIfOpen(eq(7L), eq("key"), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.closeUnregistered(7L, "key"));
        when(fences.lock(7L, "key")).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.closeUnregistered(7L, "key"));
    }
    @Test void invalidKeyCannotWriteFence() {
        for (String key : new String[] {null, " ", "x".repeat(65)}) {
            assertThrows(BusinessException.class, () -> service.closeUnregistered(7L, key));
        }
        verifyNoInteractions(fences, payments);
    }
    @Test void realTransactionAdviceRejectsFenceLockWithoutCreationTransaction() {
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
        };
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxy = (TicketPurchaseFenceService) factory.getProxy();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> proxy.requireOpen(7L, "key"));
        verifyNoInteractions(fences, payments);
    }
    @Test void customerIdentityComesFromSessionAndStaffCannotClose() {
        var controller = new TicketAccountController(); ReflectionTestUtils.setField(controller, "purchaseFenceService", service);
        AuthContext.set(new AuthContext.AuthUser(7L, "customer", null, null));
        assertEquals(true, controller.closePurchaseIntent(Map.of("idempotencyKey", "key", "customerId", "8")).getData().get("closed"));
        verify(fences).lock(7L, "key");
        AuthContext.set(new AuthContext.AuthUser(8L, "staff", "STATION_MANAGER", 1L));
        assertThrows(BusinessException.class, () -> controller.closePurchaseIntent(Map.of("idempotencyKey", "other")));
    }
    @Test void creationThenConfirmationFailureStillReturnsOriginalWhenClosing() {
        var purchase = new TicketAccountServiceImpl(); var catalog = mock(ProductMapper.class);
        var product = new Product(); product.setId(5L); product.setCategory(2); product.setTicketEnabled(1); product.setStatus(1);
        product.setTicketPrice(new BigDecimal("8.00")); var tiers = mock(TicketTierService.class);
        var inventory = mock(InventoryMapper.class);
        var selected = new com.example.aquaflow.entity.Inventory(); selected.setEnabled(1);
        when(inventory.getByStationAndProduct(1L, 5L)).thenReturn(selected);
        when(catalog.getById(5L)).thenReturn(product); when(tiers.usesCustomTicket(product, selected)).thenReturn(true);
        ReflectionTestUtils.setField(purchase, "productMapper", catalog);
        ReflectionTestUtils.setField(purchase, "inventoryMapper", inventory);
        ReflectionTestUtils.setField(purchase, "paymentRecordMapper", payments);
        ReflectionTestUtils.setField(purchase, "purchaseFenceService", service);
        ReflectionTestUtils.setField(purchase, "barrelPolicy", mock(BarrelBusinessPolicy.class));
        ReflectionTestUtils.setField(purchase, "ticketTierService", tiers);
        var stations = mock(com.example.aquaflow.mapper.StationMapper.class);
        var station = new com.example.aquaflow.entity.Station(); station.setId(1L); station.setStatus(1);
        when(stations.getById(1L)).thenReturn(station);
        ReflectionTestUtils.setField(purchase, "stationMapper", stations);
        doAnswer(call -> { PaymentRecord p = call.getArgument(0); p.setId(9L);
            when(payments.getByCustomerAndIdempotencyKeyForUpdate(7L, "key")).thenReturn(p); return null;
        }).when(payments).insert(any());
        var confirm = mock(PaymentService.class);
        when(confirm.confirmMockChannelIfApplicable(9L)).thenThrow(new BusinessException("确认失败"));
        var controller = new TicketAccountController(); ReflectionTestUtils.setField(controller, "ticketAccountService", purchase);
        ReflectionTestUtils.setField(controller, "paymentService", confirm); ReflectionTestUtils.setField(controller, "purchaseFenceService", service);
        var dto = new TicketPurchaseDTO(); dto.setProductId(5L); dto.setStationId(1L); dto.setQuantity(3); dto.setPaymentMethod(1); dto.setIdempotencyKey("key");
        AuthContext.set(new AuthContext.AuthUser(7L, "customer", null, null));
        assertThrows(BusinessException.class, () -> controller.purchase(dto));
        var result = service.closeUnregistered(7L, "key");
        assertFalse(result.closed()); assertEquals(1, result.payment().getStatus());
        assertEquals(new BigDecimal("24.00"), result.payment().getAmount());
        verify(fences, never()).closeIfOpen(any(), any(), any()); verify(payments).insert(any());
    }
}
