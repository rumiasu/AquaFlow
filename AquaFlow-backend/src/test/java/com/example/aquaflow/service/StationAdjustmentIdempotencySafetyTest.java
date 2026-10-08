package com.example.aquaflow.service;

import com.example.aquaflow.constant.AdjustType;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerDepositAccount;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.entity.StationAdjustment;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.StationAdjustmentMapper;
import com.example.aquaflow.service.impl.StationAdjustmentServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service commands with mocked persistence; SQL locks/rollback are verified separately over HTTP/MySQL. */
class StationAdjustmentIdempotencySafetyTest {
    private final StationAdjustmentServiceImpl service = new StationAdjustmentServiceImpl();
    private final StationAdjustmentMapper adjustments = mock(StationAdjustmentMapper.class);
    private final CustomerStationConfigMapper bindings = mock(CustomerStationConfigMapper.class);
    private final CustomerMapper customers = mock(CustomerMapper.class);
    private final CustomerDepositAccountMapper accounts = mock(CustomerDepositAccountMapper.class);
    private final DepositRecordService deposits = mock(DepositRecordService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    @BeforeEach void setup() {
        AuthContext.set(new AuthContext.AuthUser(11L, "staff", "STATION_MANAGER", 2L));
        ReflectionTestUtils.setField(service, "adjustmentMapper", adjustments);
        ReflectionTestUtils.setField(service, "customerStationConfigMapper", bindings);
        ReflectionTestUtils.setField(service, "customerMapper", customers);
        ReflectionTestUtils.setField(service, "depositAccountMapper", accounts);
        ReflectionTestUtils.setField(service, "depositRecordService", deposits);
        ReflectionTestUtils.setField(service, "auditLogService", audit);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        when(transactions.getTransaction(any())).thenAnswer(inv -> mock(TransactionStatus.class));
        when(bindings.getByCustomerAndStation(anyLong(), eq(2L))).thenReturn(new CustomerStationConfig());
    }

    @AfterEach void cleanup() { AuthContext.clear(); TransactionSynchronizationManager.clear(); }

    @Test void otherStationSameKeyCannotReturnItsAdjustmentOrCustomerProfile() {
        StationAdjustment existing = request(); existing.setStationId(1L);
        when(adjustments.findByClientToken("shared-key")).thenReturn(existing);
        assertThrows(BusinessException.class, () -> create(request()));
        verifyNoInteractions(customers, deposits, accounts);
        verify(adjustments, never()).insert(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer", "type", "product", "quantity", "amount", "price", "reason", "evidence"})
    void changedBusinessContentCannotReplayAnExistingKey(String changed) {
        when(adjustments.findByClientToken("shared-key")).thenReturn(request());
        StationAdjustment incoming = request();
        switch (changed) {
            case "customer" -> incoming.setCustomerId(8L);
            case "type" -> incoming.setAdjustType(AdjustType.DEPOSIT_DEDUCT);
            case "product" -> incoming.setProductId(42L);
            case "quantity" -> incoming.setQty(1);
            case "amount" -> incoming.setAmount(new BigDecimal("11.00"));
            case "price" -> incoming.setUnitPrice(BigDecimal.ONE);
            case "reason" -> incoming.setReason("different correction");
            case "evidence" -> incoming.setEvidence("different proof");
        }
        assertThrows(BusinessException.class, () -> create(incoming));
        verify(adjustments, never()).insert(any());
        verifyNoInteractions(deposits);
    }

    @Test void equivalentDecimalScaleReplaysWithoutCreatingOrCreditingAgain() {
        StationAdjustment existing = request(); existing.setAmount(new BigDecimal("10.00"));
        when(adjustments.findByClientToken("shared-key")).thenReturn(existing);
        assertSame(existing, create(request()));
        verify(adjustments, never()).insert(any());
        verifyNoInteractions(deposits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.001", "100000000.00", "-100000000.00"})
    void unrepresentableMoneyNeverReachesInsertOrLedger(String number) {
        StationAdjustment incoming = request(); incoming.setAmount(new BigDecimal(number));
        assertThrows(BusinessException.class, () -> create(incoming));
        incoming.setAmount(BigDecimal.TEN); incoming.setUnitPrice(new BigDecimal(number));
        assertThrows(BusinessException.class, () -> create(incoming));
        verifyNoInteractions(adjustments, deposits, accounts);
    }

    @Test void concurrentInsertConflictUsesCurrentWinnerAndChecksItsContents() {
        StationAdjustment winner = request();
        doThrow(new DuplicateKeyException("synthetic unique-key race")).when(adjustments).insert(any());
        when(adjustments.findByClientTokenForShare("shared-key")).thenReturn(winner);
        assertSame(winner, create(request()));
        winner.setAmount(new BigDecimal("12.00"));
        assertThrows(BusinessException.class, () -> create(request()));
        verify(adjustments, never()).setAdjustNo(anyLong(), anyString());
        verifyNoInteractions(deposits);
    }

    @Test void deadlockRollsBackBeforeOpeningANewAttemptWithTheSameReceiptKey() {
        doThrow(new DeadlockLoserDataAccessException("synthetic deadlock", null))
                .doAnswer(inv -> { ((StationAdjustment) inv.getArgument(0)).setId(101L); return null; })
                .when(adjustments).insert(any());
        StationAdjustment result = create(request());
        assertEquals("shared-key", result.getClientToken()); assertEquals(101L, result.getId());
        var order = inOrder(transactions, adjustments);
        order.verify(transactions).getTransaction(any()); order.verify(adjustments).insert(any());
        order.verify(transactions).rollback(any()); order.verify(transactions).getTransaction(any());
        order.verify(adjustments).insert(any()); order.verify(transactions).commit(any());
        verifyNoInteractions(deposits);
    }

    @Test void repeatedDeadlockIsBoundedAndNeverReportsSuccessOrCreditsAssets() {
        doThrow(new DeadlockLoserDataAccessException("synthetic deadlock", null)).when(adjustments).insert(any());
        assertThrows(BusinessException.class, () -> create(request()));
        verify(adjustments, times(5)).insert(any()); verify(transactions, times(5)).rollback(any());
        verify(transactions, never()).commit(any()); verifyNoInteractions(deposits);
    }

    @Test void anExistingOuterTransactionDoesNotRetryWithinARolledBackTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        doThrow(new DeadlockLoserDataAccessException("synthetic deadlock", null)).when(adjustments).insert(any());
        assertThrows(DeadlockLoserDataAccessException.class, () -> create(request()));
        verify(adjustments, times(1)).insert(any()); verifyNoInteractions(transactions, deposits);
    }

    @Test void reversalKeyCannotBeReinterpretedAsAStandaloneCreate() {
        StationAdjustment existing = request(); existing.setReverses(44L);
        when(adjustments.findByClientToken("shared-key")).thenReturn(existing);
        assertThrows(BusinessException.class, () -> create(request()));
        verify(adjustments, never()).insert(any());
    }

    @Test void unrelatedPendingCreateKeyCannotBecomeAReversalOrChangeMoney() {
        StationAdjustment original = original();
        StationAdjustment pending = request(); pending.setAmount(BigDecimal.ONE);
        when(adjustments.getByIdForUpdate(44L)).thenReturn(original);
        when(adjustments.findByClientToken("shared-key")).thenReturn(pending);
        assertThrows(BusinessException.class, () -> service.reverse(44L, "correct error", "shared-key"));
        assertEquals("EFFECTIVE", original.getStatus());
        assertNull(original.getReversedBy()); assertNull(pending.getReverses());
        verify(adjustments, never()).setReverses(anyLong(), anyLong());
        verify(adjustments, never()).markReversedIf(anyLong(), anyString(), anyString(), anyLong());
        verifyNoInteractions(deposits, accounts);
    }

    @Test void samePayloadButDifferentOriginalCannotReuseAReversalKey() {
        StationAdjustment original = original();
        StationAdjustment previous = request(); previous.setAdjustType(AdjustType.DEPOSIT_DEDUCT);
        previous.setReason("撤销 ADJ-SYNTHETIC：correct error"); previous.setEvidence(null); previous.setReverses(999L);
        when(adjustments.getByIdForUpdate(44L)).thenReturn(original);
        when(adjustments.findByClientToken("shared-key")).thenReturn(previous);
        assertThrows(BusinessException.class, () -> service.reverse(44L, "correct error", "shared-key"));
        verifyNoInteractions(deposits);
        verify(adjustments, never()).insert(any());
    }

    @Test void completedReversalRetryReturnsTheSameReceiptAndCreditsNothingTwice() {
        StationAdjustment original = original();
        when(adjustments.getByIdForUpdate(44L)).thenReturn(original);
        AtomicReference<StationAdjustment> reverse = new AtomicReference<>();
        doAnswer(inv -> { StationAdjustment row = inv.getArgument(0); row.setId(55L); reverse.set(row); return null; })
                .when(adjustments).insert(any());
        when(adjustments.getById(55L)).thenAnswer(inv -> reverse.get());
        when(adjustments.markEffectiveIf(eq(55L), eq("PENDING"), eq("EFFECTIVE"), anyLong(), anyString(), anyString()))
                .thenReturn(1);
        when(adjustments.markReversedIf(44L, "EFFECTIVE", "REVERSED", 55L)).thenAnswer(inv -> {
            original.setStatus("REVERSED"); original.setReversedBy(55L); return 1;
        });
        CustomerDepositAccount account = new CustomerDepositAccount(); account.setBalance(BigDecimal.TEN);
        when(accounts.getByCustomerAndStation(7L, 2L)).thenReturn(account);
        StationAdjustment receipt = service.reverse(44L, "correct error", "reverse-key");
        assertEquals(44L, receipt.getReverses()); assertEquals("EFFECTIVE", receipt.getStatus());
        when(adjustments.findByClientToken("reverse-key")).thenReturn(receipt);
        assertSame(receipt, service.reverse(44L, "correct error", "reverse-key"));
        assertThrows(BusinessException.class, () -> service.reverse(44L, "correct error", "another-key"));
        assertThrows(BusinessException.class, () -> service.reverse(44L, "different reason", "reverse-key"));
        verify(adjustments, times(1)).insert(any());
        verify(deposits, times(1)).add(argThat(r -> r.getAmount().compareTo(BigDecimal.TEN) == 0
                && r.getType() == 4 && r.getAdjustmentId().equals(55L)), eq(2L));
    }

    @Test void foreignDetailDecorationRejectsBeforeReadingCustomerProfile() {
        StationAdjustment foreign = request(); foreign.setStationId(1L);
        assertThrows(BusinessException.class, () -> service.decorate(foreign));
        verifyNoInteractions(customers);
    }

    @Test void ownDecorationPreservesCustomerAndAdjustmentPresentationFields() {
        Customer c = new Customer(); c.setName("synthetic customer"); c.setPhone("synthetic phone");
        when(customers.getById(7L)).thenReturn(c);
        var view = service.decorate(request());
        assertEquals("synthetic customer", view.get("customerName"));
        assertEquals("synthetic phone", view.get("customerPhone"));
        assertEquals("待执行", view.get("statusText"));
        assertTrue(view.containsKey("beforeSnapshot")); assertTrue(view.containsKey("reversedBy"));
    }

    private StationAdjustment create(StationAdjustment a) {
        return service.create(a.getCustomerId(), a.getAdjustType(), a.getProductId(), a.getQty(),
                a.getAmount(), a.getUnitPrice(), a.getReason(), a.getEvidence(), "shared-key");
    }

    private StationAdjustment request() {
        StationAdjustment a = new StationAdjustment(); a.setId(99L); a.setStationId(2L); a.setCustomerId(7L);
        a.setAdjustType(AdjustType.DEPOSIT_GRANT); a.setAmount(BigDecimal.TEN);
        a.setReason("synthetic correction"); a.setEvidence("synthetic proof"); a.setStatus("PENDING");
        return a;
    }

    private StationAdjustment original() {
        StationAdjustment a = request(); a.setId(44L); a.setAdjustNo("ADJ-SYNTHETIC"); a.setStatus("EFFECTIVE");
        return a;
    }
}
