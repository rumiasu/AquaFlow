package com.example.aquaflow.service;

import com.example.aquaflow.constant.AdjustType;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure orchestration tests; MySQL tests separately exercise gap locks, rollback, and exact ledger effects. */
class StationAdjustmentExecutionRetryTest {
    private final StationAdjustmentServiceImpl service=new StationAdjustmentServiceImpl();
    private final StationAdjustmentMapper adjustments=mock(StationAdjustmentMapper.class);
    private final CustomerDepositAccountMapper accounts=mock(CustomerDepositAccountMapper.class);
    private final DepositRecordService deposits=mock(DepositRecordService.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    @BeforeEach void setup() {
        AuthContext.set(new AuthContext.AuthUser(11L,"staff","STATION_MANAGER",2L));
        ReflectionTestUtils.setField(service,"adjustmentMapper",adjustments);
        ReflectionTestUtils.setField(service,"depositAccountMapper",accounts);
        ReflectionTestUtils.setField(service,"depositRecordService",deposits);
        ReflectionTestUtils.setField(service,"transactionManager",transactions);
        ReflectionTestUtils.setField(service,"auditLogService",mock(AuditLogService.class));
        var bindings=mock(CustomerStationConfigMapper.class);
        ReflectionTestUtils.setField(service,"customerStationConfigMapper",bindings);
        when(bindings.getByCustomerAndStation(7L,2L)).thenReturn(new CustomerStationConfig());
        when(transactions.getTransaction(any())).thenAnswer(inv -> mock(TransactionStatus.class));
        var account=new CustomerDepositAccount(); account.setBalance(new BigDecimal("11.00"));
        when(accounts.getForUpdate(7L,2L)).thenReturn(account);
        var a=new StationAdjustment(); a.setId(55L); a.setStationId(2L); a.setCustomerId(7L);
        a.setAdjustType(AdjustType.DEPOSIT_GRANT); a.setAmount(new BigDecimal("3.00"));
        a.setStatus("PENDING"); a.setAdjustNo("ADJ-SYNTHETIC"); a.setReason("synthetic correction");
        when(adjustments.getById(55L)).thenReturn(a);
        when(adjustments.markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),anyString(),anyString())).thenReturn(1);
    }
    @AfterEach void clear() { AuthContext.clear(); TransactionSynchronizationManager.clear(); }
    static Stream<PessimisticLockingFailureException> lockConflicts() {
        return Stream.of(new PessimisticLockingFailureException("synthetic lock conflict"),
                new CannotAcquireLockException("synthetic lock timeout"),
                new DeadlockLoserDataAccessException("synthetic deadlock",null));
    }
    @ParameterizedTest @MethodSource("lockConflicts")
    void standaloneExecutionRollsBackBeforeRetryingTheSameAdjustment(PessimisticLockingFailureException conflict) {
        when(adjustments.markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),anyString(),anyString()))
                .thenThrow(conflict).thenReturn(1);
        service.execute(55L);
        var order=inOrder(transactions,adjustments,deposits);
        order.verify(transactions).getTransaction(any()); order.verify(adjustments).getById(55L);
        order.verify(adjustments).markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),anyString(),anyString());
        order.verify(transactions).rollback(any()); order.verify(transactions).getTransaction(any());
        order.verify(adjustments).getById(55L);
        order.verify(adjustments).markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),anyString(),anyString());
        order.verify(deposits).add(argThat(r -> r.getAdjustmentId().equals(55L) && r.getAmount().compareTo(new BigDecimal("3.00"))==0),eq(2L));
        order.verify(transactions).commit(any());
        verify(transactions,times(2)).getTransaction(any()); verify(deposits,times(1)).add(any(),anyLong());
    }
    @Test void exhaustedLockRetriesAreBoundedAndNeverClaimSuccess() {
        when(accounts.getForUpdate(7L,2L)).thenThrow(new CannotAcquireLockException("synthetic repeated timeout"));
        var failure=assertThrows(BusinessException.class,()->service.execute(55L));
        assertTrue(failure.getMessage().contains("重试"));
        verify(transactions,times(5)).getTransaction(any()); verify(transactions,times(5)).rollback(any());
        verify(transactions,never()).commit(any()); verifyNoInteractions(deposits);
    }
    @ParameterizedTest @MethodSource("lockConflicts")
    void anExistingOuterTransactionReceivesTheOriginalConflictWithoutNestedRetry(PessimisticLockingFailureException conflict) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(accounts.getForUpdate(7L,2L)).thenThrow(conflict);
        assertSame(conflict,assertThrows(PessimisticLockingFailureException.class,()->service.execute(55L)));
        verify(accounts,times(1)).getForUpdate(7L,2L); verifyNoInteractions(transactions,deposits);
    }
    static Stream<RuntimeException> nonLockFailures() {
        return Stream.of(new BusinessException("synthetic business denial"),
                new DataIntegrityViolationException("synthetic invalid data"),
                new UnexpectedRollbackException("synthetic rollback-only"),new IllegalStateException("synthetic bug"));
    }
    @ParameterizedTest @MethodSource("nonLockFailures")
    void nonLockFailuresAreRolledBackOnceAndNeverHiddenByRetry(RuntimeException failure) {
        when(accounts.getForUpdate(7L,2L)).thenThrow(failure);
        assertSame(failure,assertThrows(RuntimeException.class,()->service.execute(55L)));
        verify(transactions,times(1)).getTransaction(any()); verify(transactions,times(1)).rollback(any());
        verify(transactions,never()).commit(any()); verifyNoInteractions(deposits);
    }
    @Test void wrapperHasNoTransactionalAdviceThatWouldTrapEveryRetryInOneTransaction() throws Exception {
        assertNull(StationAdjustmentServiceImpl.class.getMethod("execute",Long.class).getAnnotation(Transactional.class));
        assertNull(StationAdjustmentServiceImpl.class.getAnnotation(Transactional.class));
    }
}
