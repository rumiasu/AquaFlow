package com.example.aquaflow.service;

import com.example.aquaflow.config.PaymentSchemaGuard;
import com.example.aquaflow.controller.TicketAccountController;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.impl.TicketAccountServiceImpl;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.AuthContext.AuthUser;
import com.example.aquaflow.util.TicketPurchaseIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不接数据库的反向测试；执行真实服务/控制器，不替代 HTTP 与 MySQL 并发回归。 */
class TicketPurchaseIntentTest {
    @AfterEach void clearAuth() { AuthContext.clear(); }

    private PaymentRecord original(Long pack, Integer unified) {
        PaymentRecord p = new PaymentRecord();
        p.setId(10L); p.setCustomerId(7L); p.setStationId(1L); p.setTicketWaterTypeId(5L);
        p.setTicketQty(3); p.setPaymentMethod(1); p.setTicketPackageId(pack);
        p.setAmount(new BigDecimal("24.00")); p.setStatus(2);
        p.setPurchaseRequestDigest(TicketPurchaseIntent.digest(7L, 1L, 5L, 3, 1, pack, unified));
        return p;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void changingAnyBusinessFieldMustReject(int field) {
        PaymentRecord p = original(null, null);
        assertThrows(BusinessException.class, () -> TicketPurchaseIntent.requireSame(p,
                field == 0 ? 8L : 7L, field == 1 ? 2L : 1L, field == 2 ? 6L : 5L,
                field == 3 ? 4 : 3, field == 4 ? 2 : 1, field == 5 ? 9L : null, field == 6 ? 3 : null));
    }

    @Test void replayReturnsOriginalBeforeCatalogAndPriceChecks() {
        PaymentRecord p = original(null, null);
        var mapper = mock(PaymentRecordMapper.class);
        var catalog = mock(ProductMapper.class);
        when(mapper.getByCustomerAndIdempotencyKeyForUpdate(7L, "lost-response")).thenReturn(p);
        var service = new TicketAccountServiceImpl();
        ReflectionTestUtils.setField(service, "paymentRecordMapper", mapper);
        ReflectionTestUtils.setField(service, "productMapper", catalog);
        ReflectionTestUtils.setField(service, "purchaseFenceService", mock(TicketPurchaseFenceService.class));
        assertSame(p, service.purchaseTicket(7L, 5L, 3, 1, 1L, "lost-response", null, null));
        assertEquals(new BigDecimal("24.00"), p.getAmount());
        verifyNoInteractions(catalog);
        verify(mapper, never()).insert(any());
        assertThrows(BusinessException.class,
                () -> service.purchaseTicket(7L, 5L, 4, 1, 1L, "lost-response", null, null));
        verifyNoInteractions(catalog);
    }

    @Test void distinguishLooseAndUnifiedEvenWithSameQuantity() {
        PaymentRecord unified = original(null, 3);
        assertDoesNotThrow(() -> TicketPurchaseIntent.requireSame(unified, 7L, 1L, 5L, 3, 1, null, 3));
        assertThrows(BusinessException.class,
                () -> TicketPurchaseIntent.requireSame(unified, 7L, 1L, 5L, 3, 1, null, null));
    }

    @Test void historicalKnownNoteReplaysButUnknownNoteRequiresReview() {
        PaymentRecord old = original(null, null);
        old.setPurchaseRequestDigest(null); old.setNote("线上购买水票");
        assertDoesNotThrow(() -> TicketPurchaseIntent.requireSame(old, 7L, 1L, 5L, 3, 1, null, null));
        old.setNote("人工变更，原档位未知");
        assertThrows(BusinessException.class,
                () -> TicketPurchaseIntent.requireSame(old, 7L, 1L, 5L, 3, 1, null, null));
        old.setNote("线上购买水票（站级统一折扣 3 张档）");
        assertDoesNotThrow(() -> TicketPurchaseIntent.requireSame(old, 7L, 1L, 5L, 3, 1, null, 3));
    }

    @Test void lookupUsesSessionCustomerAndDoesNotCollectMoney() {
        AuthContext.set(new AuthUser(7L, "customer", null, null));
        var mapper = mock(PaymentRecordMapper.class);
        when(mapper.getByCustomerAndIdempotencyKey(7L, "key")).thenReturn(original(null, null));
        var payment = mock(PaymentService.class);
        var lookup = new TicketAccountServiceImpl();
        ReflectionTestUtils.setField(lookup, "paymentRecordMapper", mapper);
        var controller = new TicketAccountController();
        ReflectionTestUtils.setField(controller, "ticketAccountService", lookup);
        ReflectionTestUtils.setField(controller, "paymentService", payment);
        var res = controller.purchaseResult(" key ");
        assertEquals(0, res.getCode()); assertEquals(10L, res.getData().get("paymentId"));
        verifyNoInteractions(payment);
        AuthContext.set(new AuthUser(8L, "customer", null, null));
        var other = controller.purchaseResult("key");
        assertEquals(0, other.getCode()); assertNull(other.getData());
        verify(mapper).getByCustomerAndIdempotencyKey(8L, "key");
        AuthContext.set(new AuthUser(8L, "staff", "STATION_MANAGER", 1L));
        assertThrows(BusinessException.class, () -> controller.purchaseResult("key"));
    }

    @Test void lookupHidesOtherPaymentKindsAndDoesNotCreateOrConfirmMoney() {
        var mapper = mock(PaymentRecordMapper.class); var service = new TicketAccountServiceImpl();
        ReflectionTestUtils.setField(service, "paymentRecordMapper", mapper);
        var orderPayment = original(null, null); orderPayment.setOrderId(11L);
        when(mapper.getByCustomerAndIdempotencyKey(7L, "order")).thenReturn(orderPayment);
        var depositPayment = original(null, null); depositPayment.setTicketQty(null);
        when(mapper.getByCustomerAndIdempotencyKey(7L, "deposit")).thenReturn(depositPayment);
        assertNull(service.findPurchaseResult(7L, "order")); assertNull(service.findPurchaseResult(7L, "deposit"));
        assertNull(service.findPurchaseResult(8L, "order"));
        verify(mapper, never()).insert(any());
    }

    @Test void missingSchemaFailsBeforePaymentTraffic() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(java.util.List.of());
        assertThrows(IllegalStateException.class, () -> new PaymentSchemaGuard(jdbc).verifySchema());
    }

    @Test void firstPurchasePersistsFingerprintAlongsideOriginalMoney() {
        var mapper = mock(PaymentRecordMapper.class);
        var catalog = mock(ProductMapper.class);
        var inventory = mock(com.example.aquaflow.mapper.InventoryMapper.class);
        var tiers = mock(com.example.aquaflow.service.impl.TicketTierService.class);
        var policy = mock(BarrelBusinessPolicy.class);
        var product = new com.example.aquaflow.entity.Product();
        product.setId(5L); product.setCategory(2); product.setTicketEnabled(1);
        product.setTicketPrice(new BigDecimal("8.00")); product.setPrice(new BigDecimal("20.00"));
        when(catalog.getById(5L)).thenReturn(product);
        when(tiers.usesCustomTicket(product, null)).thenReturn(true);
        var service = new TicketAccountServiceImpl();
        ReflectionTestUtils.setField(service, "paymentRecordMapper", mapper);
        ReflectionTestUtils.setField(service, "productMapper", catalog);
        ReflectionTestUtils.setField(service, "inventoryMapper", inventory);
        ReflectionTestUtils.setField(service, "ticketTierService", tiers);
        ReflectionTestUtils.setField(service, "barrelPolicy", policy);
        ReflectionTestUtils.setField(service, "purchaseFenceService", mock(TicketPurchaseFenceService.class));
        var created = service.purchaseTicket(7L, 5L, 3, 1, 1L, "first", null, null);
        verify(mapper).insert(created);
        assertEquals(TicketPurchaseIntent.digest(7L, 1L, 5L, 3, 1, null, null), created.getPurchaseRequestDigest());
        assertEquals(new BigDecimal("24.00"), created.getAmount());
        assertEquals(1, created.getStatus());
    }
}
