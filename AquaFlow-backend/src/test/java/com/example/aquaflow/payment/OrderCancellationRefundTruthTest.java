package com.example.aquaflow.payment;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.*;
import com.example.aquaflow.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service-level tests use doubles only; they do not exercise a database or transaction locks. */
class OrderCancellationRefundTruthTest {
    PaymentServiceImpl service;
    ConsumptionRefundMapper locks;
    PaymentRecordMapper payments;
    OrderMapper orders;
    TicketPaymentService tickets;
    OrderBarrelPurchaseService purchases;
    BarrelLedgerService ledger;
    StationRecoveryService recoveries;
    InventoryReservationService inventory;
    OrderItemMapper items;
    TicketRecordMapper ticketRecords;
    TicketAccountService ticketAccounts;
    DepositRecordMapper deposits;
    CustomerDepositAccountMapper depositAccounts;
    Orders order;

    @BeforeEach void setup() {
        service = new PaymentServiceImpl();
        locks = mock(ConsumptionRefundMapper.class); payments = mock(PaymentRecordMapper.class);
        orders = mock(OrderMapper.class); tickets = mock(TicketPaymentService.class);
        purchases = mock(OrderBarrelPurchaseService.class); ledger = mock(BarrelLedgerService.class);
        recoveries = mock(StationRecoveryService.class); inventory = mock(InventoryReservationService.class);
        items = mock(OrderItemMapper.class); ticketRecords = mock(TicketRecordMapper.class);
        ticketAccounts = mock(TicketAccountService.class); deposits = mock(DepositRecordMapper.class);
        depositAccounts = mock(CustomerDepositAccountMapper.class);
        set("paymentLocks", locks); set("paymentRecordMapper", payments); set("orderMapper", orders);
        set("ticketPayments", tickets); set("orderPurchases", purchases); set("barrelLedgerService", ledger);
        set("stationRecoveries", recoveries); set("inventoryReservationService", inventory);
        set("orderItemMapper", items); set("ticketRecordMapper", ticketRecords); set("ticketAccountService", ticketAccounts);
        set("depositRecordMapper", deposits); set("customerDepositAccountMapper", depositAccounts);
        set("customerBarrelInTransitMapper", mock(CustomerBarrelInTransitMapper.class));
        set("mockPolicy", new MockPaymentPolicy(new MockEnvironment().withProperty("app.payment.test-mode", "true"), mock(DataSource.class)));
        order = new Orders(); order.setId(90L); order.setCustomerId(7L); order.setStationId(1L);
        order.setStatus(OrderStatus.PENDING); order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(PayMethod.WECHAT); order.setDepositAmount(BigDecimal.ZERO);
        when(locks.lockOrder(90L)).thenReturn(order);
        when(orders.updateStatusIf(90L, OrderStatus.PENDING, OrderStatus.CANCELLED)).thenReturn(1);
        when(payments.updateStatusTo(anyLong(), anyInt(), anyInt())).thenReturn(1);
    }
    void set(String field, Object value) { ReflectionTestUtils.setField(service, field, value); }
    PaymentRecord paid(int method, String transaction) {
        PaymentRecord r = new PaymentRecord(); r.setId(12L); r.setOrderId(90L); r.setCustomerId(7L);
        r.setStationId(1L); r.setStatus(PaymentStatus.PAID); r.setPaymentMethod(method);
        r.setAmount(new BigDecimal("40.00")); r.setTransactionNo(transaction); return r;
    }
    void active(PaymentRecord... records) { when(payments.activeByOrderForUpdate(90L)).thenReturn(List.of(records)); }

    @Test void unknownWechatIsBlockedBeforeAnyEffectsEvenInTestModeAndOnRetry() {
        PaymentRecord cash = paid(PayMethod.CASH, null); cash.setId(11L);
        PaymentRecord real = paid(PayMethod.WECHAT, "WX-REAL"); active(cash, real);
        for (int i=0; i<2; i++) {
            var error = assertThrows(BusinessException.class, () -> service.refundOrder(90L, "取消"));
            assertTrue(error.getMessage().contains("暂不能取消"));
            assertTrue(error.getMessage().contains("退款"));
        }
        verify(payments, never()).updateStatusTo(anyLong(), anyInt(), anyInt());
        verify(payments, never()).insert(any()); verifyNoInteractions(orders, tickets, purchases, ledger, recoveries, inventory, deposits, depositAccounts);
        assertEquals(PaymentStatus.PAID, real.getStatus()); assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(OrderStatus.PENDING, order.getStatus());
    }
    @Test void absentTransactionProofCannotBeGuessedAsSimulated() {
        PaymentRecord unknown = paid(PayMethod.WECHAT, null); unknown.setNote("模拟"); active(unknown);
        assertThrows(BusinessException.class, () -> service.refundOrder(90L, "取消"));
        verifyNoInteractions(orders, tickets, purchases, ledger);
    }
    @Test void unpaidOrderRemainsCancellableWithoutInventingRefund() {
        active(); order.setPaymentStatus(PaymentStatus.PENDING);
        service.refundOrder(90L, "取消");
        verify(payments, never()).insert(any());
        verify(orders).updatePaymentStatusIf(90L, PaymentStatus.PENDING, PaymentStatus.CANCELLED);
        verify(orders).updateStatusIf(90L, OrderStatus.PENDING, OrderStatus.CANCELLED);
    }
    @Test void simulatedButNoLongerAuthorizedAlsoBlocks() {
        set("mockPolicy", new MockPaymentPolicy(new MockEnvironment(), mock(DataSource.class)));
        active(paid(PayMethod.WECHAT, "SIM-WX:00000000-0000-0000-0000-000000000001"));
        assertThrows(BusinessException.class, () -> service.refundOrder(90L, "取消"));
        verifyNoInteractions(orders, tickets, purchases, ledger);
    }
    @Test void allowedSimulatedWechatCancelsAndWritesOnlySimulatedRefundOnce() {
        PaymentRecord simulated = paid(PayMethod.WECHAT, "SIM-WX:00000000-0000-0000-0000-000000000001"); active(simulated);
        service.refundOrder(90L, "取消");
        var captured = org.mockito.ArgumentCaptor.forClass(PaymentRecord.class);
        verify(payments).updateStatusTo(12L, PaymentStatus.REFUNDED, PaymentStatus.PAID);
        verify(payments).insert(captured.capture());
        assertTrue(captured.getValue().isSimulated()); assertEquals(0, captured.getValue().getAmount().compareTo(new BigDecimal("-40.00")));
        assertTrue(captured.getValue().getNote().contains("模拟"));
        verify(orders).updatePaymentStatusIf(90L, PaymentStatus.PAID, PaymentStatus.REFUNDED);
        verify(inventory).releaseForOrder(90L);
        order.setStatus(OrderStatus.CANCELLED);
        assertThrows(BusinessException.class, () -> service.refundOrder(90L, "重复"));
        verify(payments, times(1)).insert(any());
    }
    @Test void cancellationPreviewDoesNotOfferUnsupportedWechatRefund() {
        var preview = new ConsumptionRefundService();
        var previewOrders = mock(OrderMapper.class); var previewPayments = mock(PaymentRecordMapper.class);
        var previewTickets = mock(TicketPaymentService.class);
        ReflectionTestUtils.setField(preview, "orders", previewOrders);
        ReflectionTestUtils.setField(preview, "payments", previewPayments);
        ReflectionTestUtils.setField(preview, "ticketPayments", previewTickets);
        ReflectionTestUtils.setField(preview, "mockPolicy", new MockPaymentPolicy(
                new MockEnvironment().withProperty("app.payment.test-mode", "true"), mock(DataSource.class)));
        PaymentRecord real = paid(PayMethod.WECHAT, "WX-REAL");
        when(previewPayments.getById(12L)).thenReturn(real); when(previewOrders.getById(90L)).thenReturn(order);
        var result = preview.preview(12L);
        assertTrue(result.get("notice").toString().contains("暂不能取消"));
        assertEquals(List.of(), result.get("scopes"));
        verifyNoInteractions(previewTickets);
    }
    @Test void failedOriginalPaymentCasNeverCreatesRefundVoucher() {
        active(paid(PayMethod.CASH, null));
        when(payments.updateStatusTo(12L, PaymentStatus.REFUNDED, PaymentStatus.PAID)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.refundOrder(90L, "取消"));
        verify(payments, never()).insert(any());
        verify(orders, never()).updatePaymentStatusIf(anyLong(), anyInt(), anyInt());
        verify(orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
    }
    @Test void cashAndPrepaidDepositKeepExistingCancellationPath() {
        PaymentRecord cash = paid(PayMethod.CASH, null); active(cash); order.setPaymentMethod(PayMethod.CASH);
        order.setDepositAmount(new BigDecimal("30.00")); when(deposits.countByOrderAndType(90L, DepositType.PREPAID)).thenReturn(1);
        when(depositAccounts.decreaseBalance(7L, 1L, new BigDecimal("30.00"))).thenReturn(1);
        service.refundOrder(90L, "取消");
        verify(payments).insert(any(PaymentRecord.class)); verify(deposits).insert(any(DepositRecord.class));
        verify(orders).updateStatusIf(90L, OrderStatus.PENDING, OrderStatus.CANCELLED);
    }
    @Test void ticketPaymentStillRestoresConsumedTickets() {
        active(paid(PayMethod.TICKET, null)); order.setPaymentMethod(PayMethod.TICKET);
        var item = new OrderItem(); item.setProductId(3L); item.setQuantity(2); when(items.listByOrderId(90L)).thenReturn(List.of(item));
        when(ticketRecords.countConsumeByOrderAndProduct(90L, 3L)).thenReturn(1);
        service.refundOrder(90L, "取消");
        verify(ticketAccounts).refundTicket(7L, 3L, 2, 90L, 1L);
        verify(orders).updateStatusIf(90L, OrderStatus.PENDING, OrderStatus.CANCELLED);
    }
    @Test void combinedPurchaseWithAllowedSimulatedPaymentUsesExistingPath() {
        PaymentRecord simulated = paid(PayMethod.WECHAT, "SIM-WX:00000000-0000-0000-0000-000000000001"); active(simulated);
        when(purchases.hasPurchase(90L)).thenReturn(true);
        service.refundOrder(90L, "取消");
        verify(purchases).cancel(eq(order), eq(List.of(simulated)), eq("取消"), isNull());
        verify(orders).updateStatusIf(90L, OrderStatus.PENDING, OrderStatus.CANCELLED);
    }
}
