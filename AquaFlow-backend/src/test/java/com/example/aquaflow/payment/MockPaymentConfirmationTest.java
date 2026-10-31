package com.example.aquaflow.payment;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.*;
import com.example.aquaflow.service.impl.PaymentServiceImpl;
import com.example.aquaflow.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 执行真实支付服务，替身只隔离数据库与账本依赖；不声称证明 MySQL 锁/事务。 */
class MockPaymentConfirmationTest {
    PaymentServiceImpl service; PaymentRecordMapper payments; ConsumptionRefundMapper locks; OrderMapper orders;
    IndependentBarrelService independent; TicketAccountService tickets; PaymentRecord payment; Orders order;
    @BeforeEach void setup() {
        service=spy(new PaymentServiceImpl());payments=mock(PaymentRecordMapper.class);locks=mock(ConsumptionRefundMapper.class);
        orders=mock(OrderMapper.class);independent=mock(IndependentBarrelService.class);tickets=mock(TicketAccountService.class);
        var policy=mock(MockPaymentPolicy.class);
        ReflectionTestUtils.setField(service,"paymentRecordMapper",payments);ReflectionTestUtils.setField(service,"paymentLocks",locks);
        ReflectionTestUtils.setField(service,"orderMapper",orders);ReflectionTestUtils.setField(service,"independentBarrelService",independent);
        ReflectionTestUtils.setField(service,"ticketAccountService",tickets);ReflectionTestUtils.setField(service,"mockPolicy",policy);
        ReflectionTestUtils.setField(service,"mockAdapter",new MockWechatPaymentAdapter(policy));ReflectionTestUtils.setField(service,"mockWechatPay",true);
        ReflectionTestUtils.setField(service,"confirmedRefusals",mock(ConfirmedRefusalService.class));
        doNothing().when(service).applyDepositOnPaid(anyLong());
        payment=new PaymentRecord();payment.setId(12L);payment.setOrderId(90L);payment.setCustomerId(7L);payment.setStationId(1L);
        payment.setPaymentMethod(1);payment.setStatus(1);payment.setAmount(new BigDecimal("40.00"));
        payment.setTransactionNo("SIM-WX:00000000-0000-0000-0000-000000000001");
        order=new Orders();order.setId(90L);order.setCustomerId(7L);order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(1);order.setPaymentMethod(1);order.setTotalAmount(new BigDecimal("40.00"));
        when(payments.getById(12L)).thenAnswer(x->payment);when(locks.lockPayment(12L)).thenAnswer(x->payment);
        when(locks.lockOrder(90L)).thenAnswer(x->order);when(orders.getById(90L)).thenAnswer(x->order);
        when(payments.updateStatusTo(12L,2,1)).thenAnswer(x->{payment.setStatus(2);return 1;});
        when(orders.markPaidIfCollectable(90L)).thenReturn(1);
    }
    @Test void repeatedNotificationsUseCommonConfirmationAndEffectsOnce() {
        for(int i=0;i<20;i++)assertEquals(2,service.confirmMockChannelIfApplicable(12L).getStatus());
        verify(payments,times(1)).updateStatusTo(12L,2,1);verify(orders,times(1)).markPaidIfCollectable(90L);
        verify(independent,times(1)).activate(payment);verify(service,times(1)).applyDepositOnPaid(90L);verifyNoInteractions(tickets);
    }
    @Test void ticketPurchaseCreditsThroughCommonServiceOnce() {
        payment.setOrderId(null);payment.setTicketWaterTypeId(4L);payment.setTicketQty(5);
        for(int i=0;i<3;i++)service.confirmMockChannelIfApplicable(12L);
        verify(tickets,times(1)).creditPurchasedTickets(7L,4L,5,1L,12L,new BigDecimal("40.00"));
        verify(orders,never()).markPaidIfCollectable(anyLong());
    }
    @Test void closeWinningRacePreventsResurrection() {
        order.setStatus(OrderStatus.CANCELLED);
        assertThrows(BusinessException.class,()->service.confirmMockChannelIfApplicable(12L));
        verify(payments,never()).updateStatusTo(anyLong(),anyInt(),anyInt());verifyNoInteractions(independent,tickets);
    }
    @Test void lockReadOverridesStalePendingObservation() {
        var stale=new PaymentRecord();stale.setPaymentMethod(1);stale.setCustomerId(7L);stale.setOrderId(90L);stale.setStatus(1);
        when(payments.getById(12L)).thenReturn(stale);payment.setStatus(PaymentStatus.CANCELLED);
        assertThrows(BusinessException.class,()->service.confirmMockChannelIfApplicable(12L));
        verify(payments,never()).updateStatusTo(anyLong(),anyInt(),anyInt());
    }
    @Test void amountAndOwnerMismatchRefuseBeforeCasOrEffects() {
        payment.setAmount(new BigDecimal("0.01"));
        assertThrows(BusinessException.class,()->service.confirmMockChannelIfApplicable(12L));
        payment.setAmount(order.getTotalAmount());order.setCustomerId(8L);
        assertThrows(BusinessException.class,()->service.confirmMockChannelIfApplicable(12L));
        verify(payments,never()).updateStatusTo(anyLong(),anyInt(),anyInt());verifyNoInteractions(independent,tickets);
    }
    @Test void failedCasHasNoDownstreamEffects() {
        doReturn(0).when(payments).updateStatusTo(12L,2,1);
        assertThrows(BusinessException.class,()->service.confirmMockChannelIfApplicable(12L));
        verifyNoInteractions(independent,tickets);verify(service,never()).applyDepositOnPaid(anyLong());
    }
}
