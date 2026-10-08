package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.AccountClosureCheckMapper;
import com.example.aquaflow.mapper.AccountClosureCheckSql;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountClosureCheckServiceTest {
    AccountClosureCheckMapper mapper;
    PlatformTransactionManager manager;
    TransactionStatus status;
    AccountClosureCheckService service;
    @BeforeEach void setup() {
        mapper=mock(AccountClosureCheckMapper.class);manager=mock(PlatformTransactionManager.class);
        status=mock(TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(status);
        service=new AccountClosureCheckService(mapper,manager,Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"),ZoneOffset.UTC));
        AuthContext.set(new AuthContext.AuthUser(7L,"customer",null,null));
        when(mapper.exists(7L)).thenReturn(1);when(mapper.facts(7L)).thenReturn(List.of());
    }
    @AfterEach void clear() {AuthContext.clear();}
    @Test void emptyIsReadOnlySnapshotAndDoesNotAuthorizeDeletion() {
        var result=service.checkMyAccount();assertTrue(result.isComplete());assertTrue(result.isClear());
        assertEquals("仅为注销前检查，不会注销账户",result.getNotice());
        verify(manager).getTransaction(argThat(d->d.isReadOnly() && d.getIsolationLevel()==TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(manager).commit(status);verify(mapper).facts(7L);
    }
    @Test void staffCannotEvenOpenReadTransaction() {
        AuthContext.set(new AuthContext.AuthUser(7L,"staff","STATION_MANAGER",1L));
        assertThrows(BusinessException.class,()->service.checkMyAccount());verifyNoInteractions(mapper,manager);
    }
    @Test void invalidCustomerCannotOpenReadTransaction() {
        AuthContext.set(new AuthContext.AuthUser(-1L,"customer",null,null));
        assertThrows(BusinessException.class,()->service.checkMyAccount());verifyNoInteractions(mapper,manager);
    }
    @Test void queryFailureIsExplicitlyIncomplete() {
        when(mapper.facts(7L)).thenThrow(new IllegalStateException("database unavailable"));assertIncomplete();
        verify(manager).rollback(status);
    }
    @Test void transactionStartFailureIsIncomplete() {
        when(manager.getTransaction(any())).thenThrow(new IllegalStateException("database unavailable"));assertIncomplete();
        verifyNoInteractions(mapper);
    }
    @Test void commitFailureDoesNotLeakSuccessfulPartialResult() {
        doThrow(new IllegalStateException("connection lost")).when(manager).commit(status);assertIncomplete();
    }
    @Test void missingCustomerIsIncomplete() {when(mapper.exists(7L)).thenReturn(0);assertIncomplete();verify(mapper,never()).facts(anyLong());}
    @Test void unknownCategoryIsIncomplete() {
        when(mapper.facts(7L)).thenReturn(List.of(row("FACT"),row("UNRECOGNIZED")));assertIncomplete();
    }
    @Test void missingOrNegativeAggregateIsIncomplete() {
        Map<String,Object> broken=row("DEPOSIT");broken.put("amount",-1);
        when(mapper.facts(7L)).thenReturn(List.of(broken));assertIncomplete();
        broken.remove("amount");assertIncomplete();
    }
    @Test void missingStationIsManualReviewRatherThanHiddenOrClear() {
        Map<String,Object> missing=row("FACT");missing.remove("stationName");missing.remove("stationId");
        when(mapper.facts(7L)).thenReturn(List.of(missing));var result=service.checkMyAccount();
        assertFalse(result.isComplete());assertFalse(result.isClear());assertEquals("MANUAL_REVIEW",result.getBlockingItems().get(0).getCategory());
    }
    @Test void unknownStationStateWithOnlyHistoricalFactsIsIncomplete() {
        var unknown=row("FACT");unknown.put("stationStatus",99);
        when(mapper.facts(7L)).thenReturn(List.of(unknown));var result=service.checkMyAccount();
        assertFalse(result.isComplete());assertFalse(result.isClear());assertTrue(result.getMessage().contains("检查未完成"));
        assertEquals("MANUAL_REVIEW",result.getBlockingItems().get(0).getCategory());
    }
    @Test void sqlBindsEveryBranchAndNeverUsesDisplayLimitOrWrites() {
        String sql=AccountClosureCheckSql.facts().toLowerCase(Locale.ROOT);
        assertFalse(sql.contains(" limit "));assertFalse(sql.contains("for update"));
        assertFalse(sql.matches("(?s).*\\b(insert|update|delete|drop|alter|truncate)\\b.*"));
        assertTrue(sql.contains("left join station"));assertTrue(sql.contains("customer_id=#{customerid}"));
        assertTrue(sql.contains("payment_status=1 and status<>5"));assertTrue(sql.contains("ticket_lot"));
    }
    private Map<String,Object> row(String category) {
        Map<String,Object> row=new HashMap<>();row.put("stationId",1L);row.put("stationName","合成站");row.put("stationStatus",2);
        row.put("category",category);row.put("itemCount",1);row.put("quantity",0);row.put("amount",0);return row;
    }
    private void assertIncomplete() {
        var result=service.checkMyAccount();assertFalse(result.isComplete());assertFalse(result.isClear());
        assertTrue(result.getMessage().contains("检查未完成"));assertTrue(result.getBlockingItems().isEmpty());assertTrue(result.getStations().isEmpty());
    }
}
