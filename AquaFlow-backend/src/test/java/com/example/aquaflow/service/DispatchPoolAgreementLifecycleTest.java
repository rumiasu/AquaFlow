package com.example.aquaflow.service;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.OrderWorkflowServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchPoolAgreementLifecycleTest {
    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothPoolEntrancesPrepareTheirOfferBeforeAnotherStationCanClaim(boolean rejected) throws Exception {
        Fixture f = new Fixture();
        if (rejected) f.service.stationReject(1L, "本站暂时无法配送", true);
        else f.service.outsource(1L, null, "请求别站配送", false);
        verify(f.agreements).recallIfUnstarted(1L);
        verify(f.agreements).prepare(f.order, null);
        verify(f.agreements, never()).accepted(any(), any());
    }

    @Test void directedOfferIsPreparedOnceForItsActualTarget() throws Exception {
        Fixture f = new Fixture(); f.service.outsource(1L, 9L, "指定别站配送", false);
        verify(f.agreements).prepare(f.order, 9L);
        verify(f.agreements, never()).prepare(any(), isNull());
    }

    @Test void reroutingAnOfferedQuoteToPoolKeepsMoneyAndBarrelTerms() {
        DispatchAgreementMapper mapper = mock(DispatchAgreementMapper.class);
        BarrelBusinessPolicy policy = mock(BarrelBusinessPolicy.class);
        when(policy.isEnabled()).thenReturn(true);
        Map<String,Object> quote = Map.of("status", "OFFERED", "service_amount", new BigDecimal("42.50"), "barrel_mode", "RETURN_EMPTY", "barrel_amount", BigDecimal.ZERO);
        when(mapper.lock(1L)).thenReturn(quote); when(mapper.route(1L, null)).thenReturn(1);
        DispatchAgreementService service = new DispatchAgreementService(mapper, mock(ConsumptionRefundMapper.class), mock(BarrelLedgerService.class), policy, mock(AuditLogService.class));
        Orders order = new Orders(); order.setId(1L); order.setStationId(8L);
        service.prepare(order, null);
        verify(mapper).lock(1L);
        verify(mapper).route(1L, null);
        verifyNoMoreInteractions(mapper); // No insert or re-quote: existing monetary terms survive.
    }

    @Test void missingOfferStillRejectsClaimInsteadOfBypassingQuoteValidation() {
        DispatchAgreementMapper mapper = mock(DispatchAgreementMapper.class);
        BarrelBusinessPolicy policy = mock(BarrelBusinessPolicy.class);
        when(policy.isEnabled()).thenReturn(true); when(policy.hasSchema()).thenReturn(true);
        when(mapper.lock(1L)).thenReturn(null);
        DispatchAgreementService service = new DispatchAgreementService(mapper, mock(ConsumptionRefundMapper.class), mock(BarrelLedgerService.class), policy, mock(AuditLogService.class));
        Orders order = new Orders(); order.setId(1L); order.setStationId(8L);
        assertThrows(BusinessException.class, () -> service.accepted(order, 9L));
        verify(mapper, never()).accept(any(), any(), any());
    }

    private static class Fixture {
        final OrderWorkflowServiceImpl service = new OrderWorkflowServiceImpl();
        final Orders order = new Orders();
        final DispatchAgreementService agreements;
        Fixture() throws Exception {
            for (var field : OrderWorkflowServiceImpl.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) ReflectionTestUtils.setField(service, field.getName(), mock(field.getType()));
            }
            OrderMapper mapper = mock(OrderMapper.class);
            order.setId(1L); order.setStationId(8L); order.setDeliveryStationId(8L); order.setStatus(1);
            order.setCustomerId(7L); order.setPaymentMethod(2); order.setPaymentStatus(1);
            when(mapper.getById(1L)).thenReturn(order);
            when(mapper.getByIdForUpdate(1L)).thenReturn(order);
            when(mapper.outsourceToPoolIf(1L, 1, 1)).thenReturn(1);
            when(mapper.outsourceToStationIf(1L, 9L, 1, 1)).thenReturn(1);
            ReflectionTestUtils.setField(service, "orderMapper", mapper);
            agreements = (DispatchAgreementService) ReflectionTestUtils.getField(service, "dispatchAgreements");
            AuthContext.set(new AuthContext.AuthUser(20L, "staff", "STATION_MANAGER", 8L));
        }
    }
}
