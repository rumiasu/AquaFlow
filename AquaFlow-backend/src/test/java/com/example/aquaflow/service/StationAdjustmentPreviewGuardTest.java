package com.example.aquaflow.service;

import com.example.aquaflow.aspect.RequireRoleAspect;
import com.example.aquaflow.constant.AdjustType;
import com.example.aquaflow.controller.ManagerAdjustmentController;
import com.example.aquaflow.dto.AdjustmentPreviewDTO;
import com.example.aquaflow.entity.CustomerDepositAccount;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.entity.StationAdjustment;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.StationAdjustmentMapper;
import com.example.aquaflow.service.impl.StationAdjustmentServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Preview and create share station binding; denied requests never read assets or consume lots. */
class StationAdjustmentPreviewGuardTest {
    private final StationAdjustmentServiceImpl service = new StationAdjustmentServiceImpl();
    private final CustomerStationConfigMapper bindings = mock(CustomerStationConfigMapper.class);
    private final CustomerDepositAccountMapper deposits = mock(CustomerDepositAccountMapper.class);
    private final BarrelLedgerService barrels = mock(BarrelLedgerService.class);
    private final TicketAccountService tickets = mock(TicketAccountService.class);
    private final StationAdjustmentMapper adjustments = mock(StationAdjustmentMapper.class);
    @BeforeEach void setup() {
        AuthContext.set(new AuthContext.AuthUser(11L, "staff", "STATION_MANAGER", 2L));
        ReflectionTestUtils.setField(service, "customerStationConfigMapper", bindings);
        ReflectionTestUtils.setField(service, "depositAccountMapper", deposits);
        ReflectionTestUtils.setField(service, "barrelLedgerService", barrels);
        ReflectionTestUtils.setField(service, "ticketAccountService", tickets);
        ReflectionTestUtils.setField(service, "adjustmentMapper", adjustments);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(inv -> mock(TransactionStatus.class));
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
    }
    @AfterEach void cleanup() { AuthContext.clear(); }
    @Test void foreignUnknownAndOrderOnlyCustomersAreRejectedBeforeAnyAssetReadOrDryRun() {
        // All three have no binding in manager B; order presence cannot grant adjustment rights.
        for (Long customer : List.of(7L, 99999999L, 8L)) {
            assertThrows(BusinessException.class, () -> service.preview(customer, AdjustType.BARREL_REVOKE,
                    42L, 1, null, null));
            assertThrows(BusinessException.class, () -> service.create(customer, AdjustType.BARREL_REVOKE,
                    42L, 1, null, null, "synthetic correction", null, "case-" + customer));
            verify(bindings, times(2)).getByCustomerAndStation(customer, 2L);
        }
        verifyNoInteractions(deposits, barrels, tickets, adjustments);
    }
    @Test void nullCustomerIsRejectedBeforeReadingAnything() {
        assertThrows(BusinessException.class, () -> service.preview(null, AdjustType.DEPOSIT_GRANT,
                null, null, BigDecimal.TEN, null));
        verifyNoInteractions(bindings, deposits, barrels, tickets, adjustments);
    }
    @Test void missingStationIsRejectedBeforeBindingOrAssets() {
        AuthContext.set(new AuthContext.AuthUser(11L, "staff", "STATION_MANAGER", null));
        assertThrows(BusinessException.class, () -> service.preview(7L, AdjustType.DEPOSIT_GRANT,
                null, null, BigDecimal.TEN, null));
        verifyNoInteractions(bindings, deposits, barrels, tickets, adjustments);
    }
    @Test void boundCustomerWithoutOrdersCanPreviewAndCreateWithoutChangingBalance() {
        when(bindings.getByCustomerAndStation(7L, 2L)).thenReturn(new CustomerStationConfig());
        CustomerDepositAccount account = new CustomerDepositAccount(); account.setBalance(new BigDecimal("30"));
        when(deposits.getByCustomerAndStation(7L, 2L)).thenReturn(account);
        var result = service.preview(7L, AdjustType.DEPOSIT_GRANT, null, null, BigDecimal.TEN, null);
        assertEquals(new BigDecimal("30"), ((Map<?, ?>) result.get("before")).get("deposit"));
        assertEquals(new BigDecimal("40"), ((Map<?, ?>) result.get("after")).get("deposit"));
        verifyNoInteractions(adjustments);
        doAnswer(inv -> { ((StationAdjustment) inv.getArgument(0)).setId(55L); return 1; })
                .when(adjustments).insert(any(StationAdjustment.class));
        var created = service.create(7L, AdjustType.DEPOSIT_GRANT, null, null, BigDecimal.TEN,
                null, "synthetic correction", null, "bound-case");
        assertEquals(2L, created.getStationId()); assertEquals("PENDING", created.getStatus());
        assertEquals(new BigDecimal("30"), account.getBalance());
        verify(bindings, times(2)).getByCustomerAndStation(7L, 2L);
        verifyNoInteractions(barrels, tickets);
    }
    @Test void previewHttpRoleAdviceRejectsCustomerDeliveryAndUnselectedBeforeService() {
        var controller = new ManagerAdjustmentController();
        var target = mock(StationAdjustmentService.class);
        ReflectionTestUtils.setField(controller, "adjustmentService", target);
        var factory = new AspectJProxyFactory(controller); factory.addAspect(new RequireRoleAspect());
        ManagerAdjustmentController proxy = factory.getProxy();
        for (String role : List.of("customer", "DELIVERY", "UNSELECTED")) {
            AuthContext.set(new AuthContext.AuthUser(9L, role.equals("customer") ? "customer" : "staff", role, 2L));
            assertThrows(BusinessException.class, () -> proxy.preview(new AdjustmentPreviewDTO()));
        }
        verifyNoInteractions(target);
    }
}
