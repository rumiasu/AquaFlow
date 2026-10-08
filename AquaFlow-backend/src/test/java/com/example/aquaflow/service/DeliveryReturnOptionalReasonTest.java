package com.example.aquaflow.service;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.impl.OrderWorkflowServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production completion commands with mocked dependencies only; no Spring context or JDBC. */
class DeliveryReturnOptionalReasonTest {
    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "null", "empty"})
    void shortageWithoutReasonStillRecordsActualBucketsAndCompletes(String shape) throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> row = f.row();
        if (shape.equals("null")) row.put("reasons", null);
        if (shape.equals("empty")) row.put("reasons", List.of());
        assertDoesNotThrow(() -> f.service.completeDelivery(1L, f.params(row)));
        verify(f.ledger).applyDelivery(1L, 9L, 8L, Map.of(10L, 1), 20L);
        verify(f.orders).updateDeliveryOutcome(1L, 1, 2, "测试水少2桶;", 70L);
        verify(f.orders).updateStatusIf(1L, OrderStatus.DELIVERING, OrderStatus.COMPLETED);
        verify(f.earnings).recordDeliveryEarnings(1L);
        verify(f.payments, never()).recordCashCollection(anyLong());
    }

    @Test void partiallyKnownReasonIsRetainedWithoutInventingTheRemainder() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> row = f.row();
        row.put("reasons", List.of(Map.of("key", "damaged", "qty", 1)));
        f.service.completeDelivery(1L, f.params(row));
        verify(f.orders).updateDeliveryOutcome(1L, 1, 2, "测试水少2桶(破损×1);", 70L);
    }

    @Test void unknownReasonCannotBeSilentlyRelabeledAsOther() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> row = f.row();
        row.put("reasons", List.of(Map.of("key", "invented", "qty", 2)));
        assertThrows(BusinessException.class, () -> f.service.completeDelivery(1L, f.params(row)));
        f.verifyNoCompletionWrites();
    }

    @Test void forgedExpectedQuantityCannotExpandTheReasonAllowance() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> row = f.row();
        row.put("expected", 100);
        row.put("reasons", List.of(Map.of("key", "lost", "qty", 99)));
        assertThrows(BusinessException.class, () -> f.service.completeDelivery(1L, f.params(row)));
        f.verifyNoCompletionWrites();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 0, 1.5, 3, 2147483648.0})
    void suppliedReasonQuantityMustBePositiveWholeAndWithinActualGap(double quantity) throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> row = f.row();
        row.put("reasons", List.of(Map.of("key", "lost", "qty", quantity)));
        assertThrows(BusinessException.class, () -> f.service.completeDelivery(1L, f.params(row)));
        f.verifyNoCompletionWrites();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 1.5, 2147483648.0})
    void actualReturnQuantityCannotBeNegativeFractionalOrOverflowed(double quantity) throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> row = f.row(); row.put("actual", quantity);
        assertThrows(BusinessException.class, () -> f.service.completeDelivery(1L, f.params(row)));
        f.verifyNoCompletionWrites();
    }

    @Test void ordinaryCashShortageDoesNotAutoCollectMoneyOrActivateDeposit() throws Exception {
        Fixture f = new Fixture(); f.cash(false);
        f.service.completeDelivery(1L, f.params(f.row()));
        verify(f.orders).updateStatusIf(1L, OrderStatus.DELIVERING, OrderStatus.DELIVERED);
        verify(f.payments, never()).recordCashCollection(anyLong());
        verify(f.payments, never()).applyDepositOnPaid(anyLong());
        verify(f.orders, never()).markPaidIfCollectable(anyLong());
    }

    @Test void newCashDepositStillRequiresFullCollectionBeforeBucketDelivery() throws Exception {
        Fixture f = new Fixture(); f.cash(true);
        BusinessException failure = assertThrows(BusinessException.class,
                () -> f.service.completeDelivery(1L, f.params(f.row())));
        assertTrue(failure.getMessage().contains("请先收齐"));
        f.verifyNoCompletionWrites();
    }

    @Test void inventoryBarrierStillPreventsStatusAndEarningWrites() throws Exception {
        Fixture f = new Fixture();
        doThrow(new BusinessException("本单备货不足")).when(f.inventory).shipForOrder(1L, 8L);
        BusinessException failure = assertThrows(BusinessException.class,
                () -> f.service.completeDelivery(1L, f.params(f.row())));
        assertEquals("本单备货不足", failure.getMessage());
        verify(f.orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
        verifyNoInteractions(f.earnings);
    }

    @Test void alreadyCompletedDeliveryStillRejectsWithoutRepeatingBucketWrites() throws Exception {
        Fixture f = new Fixture(); f.order.setStatus(OrderStatus.COMPLETED);
        assertThrows(BusinessException.class, () -> f.service.completeDelivery(1L, f.params(f.row())));
        f.verifyNoCompletionWrites();
    }

    private static class Fixture {
        final OrderWorkflowServiceImpl service = new OrderWorkflowServiceImpl();
        final Map<Class<?>, Object> dependencies = new HashMap<>();
        final Orders order = new Orders();
        final OrderMapper orders;
        final BarrelLedgerService ledger;
        final PaymentService payments;
        final StaffEarningService earnings;
        final InventoryReservationService inventory;
        Fixture() throws Exception {
            for (var field : OrderWorkflowServiceImpl.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    Object dependency = mock(field.getType()); dependencies.put(field.getType(), dependency);
                    ReflectionTestUtils.setField(service, field.getName(), dependency);
                }
            }
            orders = (OrderMapper) dependencies.get(OrderMapper.class);
            ledger = (BarrelLedgerService) dependencies.get(BarrelLedgerService.class);
            payments = (PaymentService) dependencies.get(PaymentService.class);
            earnings = (StaffEarningService) dependencies.get(StaffEarningService.class);
            inventory = (InventoryReservationService) dependencies.get(InventoryReservationService.class);
            order.setId(1L); order.setCustomerId(9L); order.setStationId(8L); order.setDeliveryStationId(8L);
            order.setStatus(OrderStatus.DELIVERING); order.setDeliveryStaffId(20L);
            order.setPaymentMethod(PayMethod.TICKET); order.setPaymentStatus(PaymentStatus.PAID);
            order.setQuantity(3);
            when(orders.getByIdForUpdate(1L)).thenReturn(order);
            when(orders.updateStatusIf(eq(1L), eq(OrderStatus.DELIVERING), anyInt())).thenReturn(1);
            OrderItem item = new OrderItem(); item.setId(501L); item.setProductId(10L); item.setQuantity(3);
            when(((OrderItemMapper) dependencies.get(OrderItemMapper.class)).listByOrderId(1L)).thenReturn(List.of(item));
            when(payments.hasPaidRecord(1L)).thenReturn(true);
            BarrelLedgerService.DeliveryOutcome outcome = new BarrelLedgerService.DeliveryOutcome();
            outcome.add(10L, 3, 1, 0, 0, 2);
            when(ledger.applyDelivery(eq(1L), eq(9L), eq(8L), anyMap(), eq(20L))).thenReturn(outcome);
            OrderBarrelExceptionService.OrderBarrelExceptionDTO exception = new OrderBarrelExceptionService.OrderBarrelExceptionDTO();
            exception.setId(70L);
            when(((OrderBarrelExceptionService) dependencies.get(OrderBarrelExceptionService.class))
                    .recordReturn(eq(1L), any())).thenReturn(exception);
            BarrelService plans = (BarrelService) dependencies.get(BarrelService.class);
            if (plans != null) {
                BarrelService.ReturnPlanItem plan = new BarrelService.ReturnPlanItem();
                plan.setOrderItemId(501L); plan.setSentQty(3); plan.setSuggestedQty(3);
                when(plans.returnPlanOfOrder(1L, 9L, 8L)).thenReturn(List.of(plan));
            }
            AuthContext.set(new AuthContext.AuthUser(20L, "staff", "DELIVERY", 8L));
        }
        void cash(boolean newDeposit) {
            order.setPaymentMethod(PayMethod.CASH); order.setPaymentStatus(PaymentStatus.PENDING);
            when(payments.hasPaidRecord(1L)).thenReturn(false);
            when(((OrderBarrelPurchaseService) dependencies.get(OrderBarrelPurchaseService.class)).hasPurchase(1L)).thenReturn(newDeposit);
        }
        Map<String, Object> row() {
            return new HashMap<>(Map.of("orderItemId", 501L, "productName", "测试水", "expected", 3, "actual", 1));
        }
        Map<String, Object> params(Map<String, Object> row) {
            return Map.of("itemReturns", List.of(row), "collected", false);
        }
        void verifyNoCompletionWrites() {
            verify(ledger, never()).applyDelivery(anyLong(), anyLong(), anyLong(), anyMap(), anyLong());
            verify(orders, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
            verifyNoInteractions(earnings);
        }
    }
}
