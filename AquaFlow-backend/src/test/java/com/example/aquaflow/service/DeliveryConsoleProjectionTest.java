package com.example.aquaflow.service;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.impl.DeliveryConsoleServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 配送详情的本单押金凭据投影回归：全局新规则和押金金额不能冒充本单采用了新凭据。
 * 2026-10-04 将已有无库四态检查纳入测试集；读详情必须保留订单、款项及账本写边界。
 */
class DeliveryConsoleProjectionTest {

    @Test
    void stockPrepProjectionRemainsAvailableForTerminalOrderAudit() {
        var console = new DeliveryConsoleServiceImpl();
        var orders = mock(OrderMapper.class);
        var items = mock(OrderItemMapper.class);
        var purchases = mock(OrderBarrelPurchaseService.class);
        var policy = mock(BarrelBusinessPolicy.class);
        var ledger = mock(BarrelLedgerService.class);
        var inventory = mock(InventoryReservationService.class);
        var dispatch = mock(DispatchAgreementService.class);
        var workflow = mock(OrderWorkflowService.class);
        ReflectionTestUtils.setField(console, "orderMapper", orders);
        ReflectionTestUtils.setField(console, "orderItemMapper", items);
        ReflectionTestUtils.setField(console, "orderBarrelPurchases", purchases);
        ReflectionTestUtils.setField(console, "barrelPolicy", policy);
        ReflectionTestUtils.setField(console, "barrelLedger", ledger);
        ReflectionTestUtils.setField(console, "inventoryReservationService", inventory);
        ReflectionTestUtils.setField(console, "dispatchAgreements", dispatch);
        ReflectionTestUtils.setField(console, "orderWorkflowService", workflow);

        Map<String, Object> prep = Map.of("ready", false, "itemsWithoutCredential", 1);
        when(items.listByOrderId(anyLong())).thenReturn(List.of());
        when(inventory.prepInfoOfOrder(anyLong())).thenReturn(prep);
        when(dispatch.info(anyLong())).thenReturn(Map.of());
        when(policy.isEnabled()).thenReturn(false);
        when(purchases.hasPurchase(anyLong())).thenReturn(false);

        // 退款是支付状态，不是 OrderStatus；送达/完成/取消与退款支付态都继续保留服务端事实。
        for (var state : List.of(
                new int[]{OrderStatus.DELIVERED, PaymentStatus.PENDING},
                new int[]{OrderStatus.COMPLETED, PaymentStatus.PAID},
                new int[]{OrderStatus.CANCELLED, PaymentStatus.CANCELLED},
                new int[]{OrderStatus.PENDING, PaymentStatus.REFUNDED})) {
            long orderId = 42L + state[0];
            var order = new Orders();
            order.setId(orderId);
            order.setStatus(state[0]);
            order.setPaymentStatus(state[1]);
            when(orders.getById(orderId)).thenReturn(order);

            Orders result = console.orderDetail(orderId);
            assertEquals(prep, result.getStockPrep());
            verify(inventory).prepInfoOfOrder(orderId);
        }
    }

    @ParameterizedTest(name = "本单凭据={0}, 已收款={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void orderPurchaseEvidenceIsSerializedWithoutBusinessWrites(boolean hasPurchase, boolean paid) {
        var console = new DeliveryConsoleServiceImpl();
        var orders = mock(OrderMapper.class);
        var items = mock(OrderItemMapper.class);
        var purchases = mock(OrderBarrelPurchaseService.class);
        var policy = mock(BarrelBusinessPolicy.class);
        var ledger = mock(BarrelLedgerService.class);
        var inventory = mock(InventoryReservationService.class);
        var dispatch = mock(DispatchAgreementService.class);
        var workflow = mock(OrderWorkflowService.class);
        ReflectionTestUtils.setField(console, "orderMapper", orders);
        ReflectionTestUtils.setField(console, "orderItemMapper", items);
        ReflectionTestUtils.setField(console, "orderBarrelPurchases", purchases);
        ReflectionTestUtils.setField(console, "barrelPolicy", policy);
        ReflectionTestUtils.setField(console, "barrelLedger", ledger);
        ReflectionTestUtils.setField(console, "inventoryReservationService", inventory);
        ReflectionTestUtils.setField(console, "dispatchAgreements", dispatch);
        ReflectionTestUtils.setField(console, "orderWorkflowService", workflow);

        var order = new Orders();
        order.setId(42L);
        order.setStationId(1L);
        order.setDeliveryStationId(1L);
        order.setStatus(OrderStatus.DELIVERING);
        order.setPaymentMethod(PayMethod.CASH);
        int paymentStatus = paid ? PaymentStatus.PAID : PaymentStatus.PENDING;
        order.setPaymentStatus(paymentStatus);
        order.setTotalAmount(new BigDecimal("90"));
        order.setWaterAmount(new BigDecimal("40"));
        order.setDepositAmount(new BigDecimal("50"));
        when(orders.getById(42L)).thenReturn(order);
        when(items.listByOrderId(42L)).thenReturn(List.of());
        when(policy.isEnabled()).thenReturn(true);
        when(purchases.hasPurchase(42L)).thenReturn(hasPurchase);
        when(inventory.prepInfoOfOrder(42L)).thenReturn(Map.of("ready", true));
        when(dispatch.info(42L)).thenReturn(Map.of());

        Orders result = console.orderDetail(42L);
        assertSame(order, result);
        assertEquals(hasPurchase, result.getHasOrderBarrelPurchase());
        assertTrue(result.getIndependentBusinessRules());
        assertEquals(!paid, result.getNeedCollect());
        assertEquals(OrderStatus.DELIVERING, result.getStatus());
        assertEquals(paymentStatus, result.getPaymentStatus());
        assertEquals(new BigDecimal("90"), result.getTotalAmount());
        assertEquals(new BigDecimal("40"), result.getWaterAmount());
        assertEquals(new BigDecimal("50"), result.getDepositAmount());
        var json = JsonMapper.builder().build().valueToTree(result);
        assertEquals(hasPurchase, json.get("hasOrderBarrelPurchase").asBoolean());

        verify(orders).getById(42L);
        verify(items).listByOrderId(42L);
        verify(policy).isEnabled();
        verify(purchases).hasPurchase(42L);
        verify(inventory).prepInfoOfOrder(42L);
        verify(dispatch).info(42L);
        verify(workflow).pendingTransferOf(42L);
        verifyNoMoreInteractions(orders, items, policy, purchases, inventory, dispatch, workflow);
        verifyNoInteractions(ledger);
    }
}
