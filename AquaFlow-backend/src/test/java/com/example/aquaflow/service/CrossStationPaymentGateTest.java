package com.example.aquaflow.service;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.OrderWorkflowServiceImpl;
import com.example.aquaflow.util.AuthContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrossStationPaymentGateTest {
    enum Route { EXTERNAL, DIRECTED, POOL, REJECT_TO_POOL, CLAIM }
    enum Payment { UNPAID, PAID, CASH, TICKET_COD }
    static Stream<Arguments> cases() {
        return Stream.of(Route.values()).flatMap(route ->
                Stream.of(Payment.values()).map(payment -> Arguments.of(route, payment)));
    }
    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @ParameterizedTest @MethodSource("cases")
    void eligibilityAppliesBeforeAnyHandoffWrites(Route route, Payment payment) throws Exception {
        Fixture f = new Fixture(route, payment);
        if (payment == Payment.UNPAID) {
            BusinessException ex = assertThrows(BusinessException.class, () -> f.invoke(route));
            assertTrue(ex.getMessage().contains("支付"));
            verify(f.mapper, never()).dispatchIfStatus(any(), any(), anyInt());
            verify(f.mapper, never()).outsourceToStationIf(any(), any(), anyInt(), anyInt());
            verify(f.mapper, never()).outsourceToPoolIf(any(), anyInt(), anyInt());
            verify(f.mapper, never()).claimPoolIfFree(any(), any(), any(), anyInt(), anyInt());
            verifyNoInteractions(f.agreements, f.inventory, f.payments, f.notifications);
        } else {
            assertDoesNotThrow(() -> f.invoke(route));
            switch (route) {
                case EXTERNAL -> verify(f.mapper).dispatchIfStatus(1L, 9L, OrderStatus.PENDING);
                case DIRECTED -> verify(f.mapper).outsourceToStationIf(1L, 9L, OrderStatus.PENDING, OrderStatus.PENDING);
                case POOL, REJECT_TO_POOL -> verify(f.mapper).outsourceToPoolIf(1L, OrderStatus.PENDING, OrderStatus.PENDING);
                case CLAIM -> verify(f.mapper).claimPoolIfFree(1L, 8L, 21L, OrderStatus.DELIVERING, OrderStatus.PENDING);
            }
        }
        verify(f.mapper).getByIdForUpdate(1L);
    }

    @Test void ticketCodClassifiesCashEvenAfterWaterRefund() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TicketPaymentService tickets = new TicketPaymentService(jdbc, mock(TicketLotService.class),
                mock(TicketDeliveryBenefitService.class), mock(OrderItemMapper.class));
        Orders order = new Orders(); order.setId(1L); order.setPaymentMethod(PayMethod.TICKET);
        String sql = "select * from order_ticket_payment where order_id=?";
        when(jdbc.queryForList(sql, 1L)).thenReturn(List.of(Map.of("cash_method", PayMethod.CASH, "state", "HELD")));
        assertTrue(tickets.cod(order));
        when(jdbc.queryForList(sql, 1L)).thenReturn(List.of(Map.of("cash_method", PayMethod.CASH, "state", "REFUNDED")));
        assertTrue(tickets.cod(order)); // Water returned, but the service-fee cash due may remain.
        when(jdbc.queryForList(sql, 1L)).thenReturn(List.of(Map.of("cash_method", PayMethod.CASH, "state", "CANCELLED")));
        assertFalse(tickets.cod(order));
        when(jdbc.queryForList(sql, 1L)).thenReturn(List.of(Map.of("cash_method", PayMethod.WECHAT, "state", "HELD")));
        assertFalse(tickets.cod(order));
        when(jdbc.queryForList(sql, 1L)).thenReturn(List.of());
        assertFalse(tickets.cod(order));
    }

    static Stream<Arguments> terminalCases() {
        return Stream.of("EXTERNAL", "DIRECTED", "POOL", "REJECT_TO_POOL", "CLAIM", "ACCEPT", "ASSIGN")
                .flatMap(route -> Stream.of(
                        Arguments.of(route, PayMethod.CASH, PaymentStatus.REFUNDED, "HELD"),
                        Arguments.of(route, PayMethod.CASH, PaymentStatus.CANCELLED, "HELD"),
                        Arguments.of(route, PayMethod.TICKET, PaymentStatus.REFUNDED, "HELD"),
                        Arguments.of(route, PayMethod.TICKET, PaymentStatus.CANCELLED, "HELD"),
                        Arguments.of(route, PayMethod.TICKET, PaymentStatus.PENDING, "REFUNDED"),
                        Arguments.of(route, PayMethod.TICKET, PaymentStatus.PENDING, "CANCELLED"),
                        Arguments.of(route, PayMethod.TICKET, PaymentStatus.PAID, "REFUNDED"),
                        Arguments.of(route, PayMethod.TICKET, PaymentStatus.PAID, "CANCELLED")));
    }

    @ParameterizedTest @MethodSource("terminalCases")
    void actualPredicateRejectsTerminalPaymentBeforeAnyEntryWrites(String route, int method, int status, String state) throws Exception {
        Route fixtureRoute = route.equals("CLAIM") ? Route.CLAIM : Route.EXTERNAL;
        Fixture f = new Fixture(fixtureRoute, Payment.CASH);
        Orders order = f.mapper.getByIdForUpdate(1L);
        order.setPaymentMethod(method); order.setPaymentStatus(status);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select * from order_ticket_payment where order_id=?", 1L))
                .thenReturn(List.of(Map.of("cash_method", PayMethod.CASH, "state", state)));
        TicketPaymentService tickets = new TicketPaymentService(jdbc, mock(TicketLotService.class),
                mock(TicketDeliveryBenefitService.class), mock(OrderItemMapper.class));
        ReflectionTestUtils.setField(f.service, "ticketPayments", tickets);
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            if (route.equals("ACCEPT")) f.service.acceptOrder(1L);
            else if (route.equals("ASSIGN")) f.service.assignToStaff(1L, 21L, false);
            else f.invoke(Route.valueOf(route));
        });
        assertTrue(ex.getMessage().contains("支付"));
        verify(f.mapper, never()).dispatchIfStatus(any(), any(), anyInt());
        verify(f.mapper, never()).outsourceToStationIf(any(), any(), anyInt(), anyInt());
        verify(f.mapper, never()).outsourceToPoolIf(any(), anyInt(), anyInt());
        verify(f.mapper, never()).claimPoolIfFree(any(), any(), any(), anyInt(), anyInt());
        verifyNoInteractions(f.agreements, f.inventory, f.payments, f.notifications);
    }

    @Test void actualPredicatePreservesLegitimateCodAndPaidOrders() {
        OrderWorkflowServiceImpl service = new OrderWorkflowServiceImpl();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TicketPaymentService tickets = new TicketPaymentService(jdbc, mock(TicketLotService.class),
                mock(TicketDeliveryBenefitService.class), mock(OrderItemMapper.class));
        ReflectionTestUtils.setField(service, "ticketPayments", tickets);
        Orders order = new Orders(); order.setId(1L); order.setPaymentStatus(PaymentStatus.PENDING);
        order.setPaymentMethod(PayMethod.CASH);
        assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(service, "isPaidOrPayOnDelivery", order));
        order.setPaymentMethod(PayMethod.TICKET);
        when(jdbc.queryForList("select * from order_ticket_payment where order_id=?", 1L))
                .thenReturn(List.of(Map.of("cash_method", PayMethod.CASH, "state", "HELD")));
        assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(service, "isPaidOrPayOnDelivery", order));
        order.setPaymentStatus(PaymentStatus.PAID);
        assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(service, "isPaidOrPayOnDelivery", order));
        when(jdbc.queryForList("select * from order_ticket_payment where order_id=?", 1L)).thenReturn(List.of());
        assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(service, "isPaidOrPayOnDelivery", order));
        order.setPaymentMethod(PayMethod.WECHAT);
        assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(service, "isPaidOrPayOnDelivery", order));
    }

    @Test void refundedCashOrderCannotBeCollectedAgainAndReportsRefund() throws Exception {
        Fixture f = new Fixture(Route.EXTERNAL, Payment.CASH);
        Orders order = f.mapper.getByIdForUpdate(1L);
        order.setStatus(OrderStatus.DELIVERED); order.setPaymentStatus(PaymentStatus.REFUNDED);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select * from order_ticket_payment where order_id=?", 1L)).thenReturn(List.of());
        ReflectionTestUtils.setField(f.service, "ticketPayments", new TicketPaymentService(jdbc,
                mock(TicketLotService.class), mock(TicketDeliveryBenefitService.class), mock(OrderItemMapper.class)));
        BusinessException ex = assertThrows(BusinessException.class, () -> f.service.confirmOfflinePay(1L));
        assertTrue(ex.getMessage().contains("已退款"));
        verify(f.mapper, never()).markPaidIfCollectable(any());
        verifyNoInteractions(f.payments);
    }

    @Test void returnedTicketWaterDoesNotWaiveOutstandingCashServiceFee() throws Exception {
        Fixture f = new Fixture(Route.EXTERNAL, Payment.CASH);
        Orders order = f.mapper.getByIdForUpdate(1L);
        order.setStatus(OrderStatus.DELIVERED); order.setPaymentStatus(PaymentStatus.PENDING);
        order.setPaymentMethod(PayMethod.TICKET);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select * from order_ticket_payment where order_id=?", 1L))
                .thenReturn(List.of(Map.of("cash_method", PayMethod.CASH, "state", "REFUNDED", "cash_due", 7)));
        ReflectionTestUtils.setField(f.service, "ticketPayments", new TicketPaymentService(jdbc,
                mock(TicketLotService.class), mock(TicketDeliveryBenefitService.class), mock(OrderItemMapper.class)));
        when(f.mapper.markPaidIfCollectable(1L)).thenReturn(1);
        when(f.mapper.updateStatusIf(1L, OrderStatus.DELIVERED, OrderStatus.COMPLETED)).thenReturn(1);
        f.service.confirmOfflinePay(1L);
        verify(f.mapper).markPaidIfCollectable(1L);
        verify(f.mapper).updateStatusIf(1L, OrderStatus.DELIVERED, OrderStatus.COMPLETED);
        verify(f.payments).recordCashCollection(1L);
        verify(f.payments).applyDepositOnPaid(1L);
    }

    @Test void rejectionWithoutPoolStillAllowsCancellationOfUnpaidOrder() throws Exception {
        Fixture f = new Fixture(Route.REJECT_TO_POOL, Payment.UNPAID);
        f.service.stationReject(1L, "reason", false);
        verify(f.payments).refundOrder(1L, "reason");
        verify(f.mapper, never()).outsourceToPoolIf(any(), anyInt(), anyInt());
    }

    static class Fixture {
        final OrderWorkflowServiceImpl service = new OrderWorkflowServiceImpl();
        final OrderMapper mapper;
        final DispatchAgreementService agreements;
        final InventoryReservationService inventory;
        final PaymentService payments;
        final CustomerNotificationMapper notifications;
        Fixture(Route route, Payment payment) throws Exception {
            for (var field : OrderWorkflowServiceImpl.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class))
                    ReflectionTestUtils.setField(service, field.getName(), mock(field.getType()));
            }
            mapper = (OrderMapper) ReflectionTestUtils.getField(service, "orderMapper");
            agreements = (DispatchAgreementService) ReflectionTestUtils.getField(service, "dispatchAgreements");
            inventory = (InventoryReservationService) ReflectionTestUtils.getField(service, "inventoryReservationService");
            payments = (PaymentService) ReflectionTestUtils.getField(service, "paymentService");
            notifications = (CustomerNotificationMapper) ReflectionTestUtils.getField(service, "customerNotificationMapper");
            TicketPaymentService tickets = (TicketPaymentService) ReflectionTestUtils.getField(service, "ticketPayments");
            Orders order = new Orders(); order.setId(1L); order.setStationId(8L);
            order.setDeliveryStationId(route == Route.CLAIM ? null : 8L); order.setStatus(OrderStatus.PENDING);
            order.setPaymentMethod(payment == Payment.CASH ? PayMethod.CASH : payment == Payment.TICKET_COD ? PayMethod.TICKET : PayMethod.WECHAT);
            order.setPaymentStatus(payment == Payment.PAID ? PaymentStatus.PAID : PaymentStatus.PENDING);
            when(tickets.cod(order)).thenReturn(payment == Payment.CASH || payment == Payment.TICKET_COD);
            when(mapper.getByIdForUpdate(1L)).thenReturn(order);
            when(mapper.dispatchIfStatus(any(), any(), anyInt())).thenReturn(1);
            when(mapper.outsourceToStationIf(any(), any(), anyInt(), anyInt())).thenReturn(1);
            when(mapper.outsourceToPoolIf(any(), anyInt(), anyInt())).thenReturn(1);
            when(mapper.claimPoolIfFree(any(), any(), any(), anyInt(), anyInt())).thenReturn(1);
            StationMapper stations = (StationMapper) ReflectionTestUtils.getField(service, "stationMapper");
            Station station = new Station(); station.setId(route == Route.CLAIM ? 8L : 9L); station.setStatus(1);
            when(stations.getByIdForUpdate(any())).thenReturn(station);
            StaffMapper staffMapper = (StaffMapper) ReflectionTestUtils.getField(service, "staffMapper");
            Staff staff = new Staff(); staff.setId(21L); staff.setStationId(8L); staff.setRole("DELIVERY"); staff.setStatus(1); staff.setName("test");
            when(staffMapper.getById(21L)).thenReturn(staff);
            AuthContext.set(new AuthContext.AuthUser(20L, "staff", "STATION_MANAGER", 8L));
        }
        void invoke(Route route) {
            switch (route) {
                case EXTERNAL -> service.dispatchExternal(1L, 9L, "reason", false);
                case DIRECTED -> service.outsource(1L, 9L, "reason", false);
                case POOL -> service.outsource(1L, null, "reason", false);
                case REJECT_TO_POOL -> service.stationReject(1L, "reason", true);
                case CLAIM -> service.claimPool(1L, 21L, "quote");
            }
        }
    }
}
