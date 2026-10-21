package com.example.aquaflow.service;

import com.example.aquaflow.controller.BarrelController;
import com.example.aquaflow.dto.BarrelRefundDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.BarrelServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 离线协议/事务边界测试：使用真实事务拦截器与内存 mock，不启动 Spring/HTTP/MySQL。 */
class CashRefundRecoveryTest {
    @AfterEach void clearIdentity() { AuthContext.set(null); }

    private static BarrelRefundDTO request() {
        BarrelRefundDTO dto = new BarrelRefundDTO(); dto.setStatus(3); dto.setRefundChannel("CASH");
        dto.setCashDeliveryConfirmed(true); dto.setExpectedRefundAmount(new BigDecimal("100.00")); return dto;
    }
    private static BarrelController controller(BarrelService service) {
        AuthContext.set(new AuthContext.AuthUser(1L,"staff","STATION_MANAGER",1L));
        BarrelController controller = new BarrelController(); ReflectionTestUtils.setField(controller,"barrelService",service); return controller;
    }
    private static <T> T transactional(T target, PlatformTransactionManager manager) {
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T proxy = (T) factory.getProxy(); return proxy;
    }
    @Test void notDeliveredOrIncompleteCashAttestationCannotReachTheWriter() {
        BarrelService writer=mock(BarrelService.class); BarrelController controller=controller(writer);
        for (int variant=0; variant<5; variant++) {
            BarrelRefundDTO dto=request();
            if(variant==0)dto.setCashDeliveryConfirmed(false);
            if(variant==1)dto.setCashDeliveryConfirmed(null);
            if(variant==2)dto.setExpectedRefundAmount(null);
            if(variant==3)dto.setRefundChannel("ONLINE");
            if(variant==4)dto.setStatus(2);
            assertEquals(1,controller.handleReturn(77L,dto).getCode());
        }
        verifyNoInteractions(writer);
    }
    @Test void confirmedCashUsesTheExistingRecordAndAmountFenceWhileOrdinaryClientsKeepTheirContract() {
        BarrelService writer=mock(BarrelService.class); BarrelController controller=controller(writer);
        assertEquals(0,controller.handleReturn(77L,request()).getCode());
        verify(writer).handleBarrelReturn(77L,1L,3,"",1L,"CASH",null,new BigDecimal("100.00"));
        reset(writer); BarrelRefundDTO ordinary=new BarrelRefundDTO();ordinary.setStatus(3);ordinary.setRefundChannel("CASH");
        assertEquals(0,controller.handleReturn(77L,ordinary).getCode());
        verify(writer).handleBarrelReturn(77L,1L,3,"",1L,"CASH",null);
    }
    @Test void serviceRejectsInvalidAmountOrChannelBeforeAnyRefundReadOrWrite() {
        BarrelServiceImpl service=new BarrelServiceImpl(); ApprovedBarrelReturnService approved=mock(ApprovedBarrelReturnService.class);
        ReflectionTestUtils.setField(service,"approvedReturnService",approved);
        for(BigDecimal amount:List.of(new BigDecimal("-1"),new BigDecimal("1.001")))
            assertThrows(BusinessException.class,()->service.handleBarrelReturn(77L,1L,3,"",1L,"CASH",null,amount));
        assertThrows(BusinessException.class,()->service.handleBarrelReturn(77L,1L,3,"",1L,"ONLINE",null,BigDecimal.ONE));
        assertThrows(BusinessException.class,()->service.handleBarrelReturn(77L,1L,2,"",1L,"CASH",null,BigDecimal.ONE));
        verifyNoInteractions(approved);
    }
    private static class Approved {
        final ApprovedBarrelReturnService service=new ApprovedBarrelReturnService();
        final BarrelRecordMapper records=mock(BarrelRecordMapper.class);
        final BarrelReturnDetailMapper details=mock(BarrelReturnDetailMapper.class);
        final BarrelLedgerService ledger=mock(BarrelLedgerService.class);
        final DepositRecordService deposits=mock(DepositRecordService.class);
        final BarrelRecordLotMapper recordLots=mock(BarrelRecordLotMapper.class);
        final BarrelReturnDetail detail=new BarrelReturnDetail();
        Approved() {
            BarrelRecord record=record();detail.setRecordId(77L);detail.setStatus("RECEIVED");
            Staff staff=new Staff();staff.setId(1L);staff.setStationId(1L);StaffMapper staffMapper=mock(StaffMapper.class);
            when(staffMapper.getById(1L)).thenReturn(staff);
            Map<String,Object> fields=Map.of("recordMapper",records,"detailMapper",details,"ledger",ledger,"depositService",deposits,
                    "recordLotMapper",recordLots,"staffMapper",staffMapper,"risk",mock(CustomerRiskService.class),
                    "orderPurchases",mock(OrderBarrelPurchaseService.class),"businessMapper",mock(BarrelBusinessMapper.class));
            fields.forEach((name,value)->ReflectionTestUtils.setField(service,name,value));
            ScopedRefusalRefundMapper scoped=mock(ScopedRefusalRefundMapper.class);
            ScopedDisputeService disputes=mock(ScopedDisputeService.class);
            ReflectionTestUtils.setField(service,"scopedRefunds",scoped);ReflectionTestUtils.setField(service,"disputes",disputes);
            when(scoped.paid(77L)).thenReturn(BigDecimal.ZERO);
            when(disputes.quote(eq(7L),eq(1L),eq(10L),any())).thenAnswer(invocation->new ScopedDisputeService.Quote(BigDecimal.ZERO,new BigDecimal("100"),invocation.getArgument(3),false));

            when(records.getById(77L)).thenReturn(record);when(details.lock(77L)).thenReturn(detail);
            when(details.heldLots(77L)).thenReturn(List.of(Map.of("lotId",50L,"qty",2,"amount",new BigDecimal("100"))));
            when(ledger.refundHeldRights(7L,1L,10L,77L)).thenReturn(consumption());
            when(details.transition(77L,"RECEIVED","REFUNDED")).thenAnswer(invocation->{detail.setStatus("REFUNDED");return 1;});
            when(records.finishRefund(eq(77L),anyString(),any(),eq(1L))).thenAnswer(invocation->{record.setStatus(3);record.setRefundPaidTime(LocalDateTime.now());return 1;});
        }
    }
    private static BarrelRecord record() {
        BarrelRecord record=new BarrelRecord();record.setId(77L);record.setStationId(1L);record.setCustomerId(7L);
        record.setProductId(10L);record.setQuantity(2);record.setType(2);record.setStatus(2);record.setDepositRefund(new BigDecimal("100"));return record;
    }
    private static BarrelLedgerService.LotConsumption consumption() {
        BarrelLedgerService.LotConsumption result=new BarrelLedgerService.LotConsumption();result.setAmount(new BigDecimal("100"));return result;
    }
    @Test void approvedAmountChangeRollsBackInsteadOfRecordingAnotherCashAmount() {
        Approved fixture=new Approved();PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        ApprovedBarrelReturnService service=transactional(fixture.service,manager);
        assertThrows(BusinessException.class,()->service.handle(77L,1L,3,"",1L,"CASH",null,new BigDecimal("99")));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
        verifyNoInteractions(fixture.deposits,fixture.recordLots);
        verify(fixture.records,never()).finishRefund(anyLong(),anyString(),any(),anyLong());
        verify(fixture.details,never()).transition(anyLong(),anyString(),anyString());
    }
    @Test void approvedRepeatUsesCurrentLockedTerminalAndCannotConsumeOrDebitTwice() {
        Approved fixture=new Approved();fixture.service.handle(77L,1L,3,"",1L,"CASH",null,new BigDecimal("100.00"));
        assertThrows(BusinessException.class,()->fixture.service.handle(77L,1L,3,"",1L,"CASH",null,new BigDecimal("100.00")));
        verify(fixture.ledger,times(2)).lockRights(7L,1L,10L);verify(fixture.details,times(2)).lock(77L);
        verify(fixture.ledger,times(1)).refundHeldRights(7L,1L,10L,77L);verify(fixture.deposits,times(1)).add(any(),eq(1L));
        verify(fixture.records,times(1)).finishRefund(eq(77L),anyString(),any(),eq(1L));
    }
    @Test void historicalAmountChangeAlsoRollsBackBeforeAccountsOrPaidState() {
        BarrelServiceImpl target=new BarrelServiceImpl();ApprovedBarrelReturnService approved=mock(ApprovedBarrelReturnService.class);
        BarrelRecordMapper records=mock(BarrelRecordMapper.class);BarrelLedgerService ledger=mock(BarrelLedgerService.class);
        CustomerDepositAccountMapper accounts=mock(CustomerDepositAccountMapper.class);DepositRecordMapper deposits=mock(DepositRecordMapper.class);
        BarrelRecordLotMapper lots=mock(BarrelRecordLotMapper.class);
        Map.of("approvedReturnService",approved,"barrelRecordMapper",records,"barrelLedgerService",ledger,
                "customerDepositAccountMapper",accounts,"depositRecordMapper",deposits,"barrelRecordLotMapper",lots)
                .forEach((name,value)->ReflectionTestUtils.setField(target,name,value));
        when(records.getById(77L)).thenReturn(record());when(ledger.consumeLots(7L,1L,10L,2,null,false)).thenReturn(consumption());
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);BarrelServiceImpl service=transactional(target,manager);
        assertThrows(BusinessException.class,()->service.handleBarrelReturn(77L,1L,3,"",1L,"CASH",null,new BigDecimal("99")));
        verify(manager).rollback(any());verify(manager,never()).commit(any());verifyNoInteractions(accounts,deposits,lots);
        verify(records,never()).finishRefund(anyLong(),anyString(),any(),anyLong());
    }
    @Test void cashSynchronizationStillRejectsAnotherStationBeforeLocksOrLedger() {
        Approved fixture=new Approved();assertThrows(BusinessException.class,()->fixture.service.handle(77L,2L,3,"",1L,"CASH",null,new BigDecimal("100")));
        verifyNoInteractions(fixture.ledger,fixture.deposits);verify(fixture.details,never()).lock(anyLong());
    }
}
