package com.example.aquaflow.service;

import com.example.aquaflow.constant.AdjustType;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.StationAdjustmentServiceImpl;
import com.example.aquaflow.util.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.apache.ibatis.annotations.Insert;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure command tests: enforce current-read audit inputs; real SQL isolation requires MySQL coverage. */
class StationAdjustmentCurrentSnapshotTest {
    private final StationAdjustmentServiceImpl service = new StationAdjustmentServiceImpl();
    private final StationAdjustmentMapper adjustments = mock(StationAdjustmentMapper.class);
    private final CustomerStationConfigMapper bindings = mock(CustomerStationConfigMapper.class);
    private final ProductMapper products = mock(ProductMapper.class);
    private final CustomerDepositAccountMapper deposits = mock(CustomerDepositAccountMapper.class);
    private final TicketAccountMapper ticketAccounts = mock(TicketAccountMapper.class);
    private final BarrelLedgerService barrels = mock(BarrelLedgerService.class);
    private final BarrelBalanceReader barrelBalances = mock(BarrelBalanceReader.class);
    private final TicketAccountService tickets = mock(TicketAccountService.class);
    private final DepositRecordService depositService = mock(DepositRecordService.class);
    private final BarrelRecordMapper records = mock(BarrelRecordMapper.class);
    private final BarrelRecordLotMapper details = mock(BarrelRecordLotMapper.class);

    @BeforeEach void setup() {
        AuthContext.set(new AuthContext.AuthUser(11L,"staff","STATION_MANAGER",2L));
        ReflectionTestUtils.setField(service,"adjustmentMapper",adjustments);
        ReflectionTestUtils.setField(service,"customerStationConfigMapper",bindings);
        ReflectionTestUtils.setField(service,"productMapper",products);
        ReflectionTestUtils.setField(service,"depositAccountMapper",deposits);
        ReflectionTestUtils.setField(service,"ticketAccountMapper",ticketAccounts);
        ReflectionTestUtils.setField(service,"barrelLedgerService",barrels);
        ReflectionTestUtils.setField(service,"barrelBalanceReader",barrelBalances);
        ReflectionTestUtils.setField(service,"ticketAccountService",tickets);
        ReflectionTestUtils.setField(service,"depositRecordService",depositService);
        ReflectionTestUtils.setField(service,"barrelRecordMapper",records);
        ReflectionTestUtils.setField(service,"barrelRecordLotMapper",details);
        ReflectionTestUtils.setField(service,"auditLogService",mock(AuditLogService.class));
        var transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(inv -> mock(TransactionStatus.class));
        ReflectionTestUtils.setField(service,"transactionManager",transactions);
        when(bindings.getByCustomerAndStation(7L,2L)).thenReturn(new CustomerStationConfig());
        Product product=new Product(); product.setId(42L); product.setCategory(1);
        when(products.getById(42L)).thenReturn(product);
        when(barrelBalances.currentBalanceForUpdate(7L,2L,42L)).thenReturn(new BarrelBalanceReader.CurrentBalance(7,-2));
        TicketAccount ticket=new TicketAccount(); ticket.setId(22L); ticket.setRemainQuantity(5);
        when(ticketAccounts.lockAccount(7L,2L,42L)).thenReturn(ticket);
        CustomerDepositAccount deposit=new CustomerDepositAccount(); deposit.setBalance(new BigDecimal("11.00"));
        when(deposits.getForUpdate(7L,2L)).thenReturn(deposit);
        when(adjustments.markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),anyString(),anyString())).thenReturn(1);
    }
    @AfterEach void clear() { AuthContext.clear(); }
    private StationAdjustment request(String type) {
        StationAdjustment a=new StationAdjustment(); a.setId(55L); a.setStationId(2L); a.setCustomerId(7L);
        a.setProductId(42L); a.setAdjustType(type); a.setQty(2); a.setUnitPrice(new BigDecimal("30.00"));
        a.setStatus("PENDING"); a.setAdjustNo("ADJ-SYNTHETIC"); a.setReason("synthetic correction");
        when(adjustments.getById(55L)).thenReturn(a); return a;
    }
    @Test void barrelGrantUsesCurrentBalancesAndKeepsOverInTheAuditRecord() throws Exception {
        request(AdjustType.BARREL_GRANT);
        CustomerBarrelLot lot=new CustomerBarrelLot(); lot.setId(77L); lot.setUnitPrice(new BigDecimal("30.00"));
        when(barrels.createLot(eq(7L),eq(2L),eq(42L),eq(new BigDecimal("30.00")),eq(2),isNull(),eq(11L),any())).thenReturn(lot);
        doAnswer(inv -> { ((BarrelRecord)inv.getArgument(0)).setId(88L); return null; }).when(records).insert(any());
        when(details.insert(any())).thenReturn(1);
        service.execute(55L);
        var before=org.mockito.ArgumentCaptor.forClass(String.class);
        var after=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(adjustments).markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),before.capture(),after.capture());
        var json=new ObjectMapper(); var b=json.readTree(before.getValue()); var a=json.readTree(after.getValue());
        assertEquals(7,b.path("right").asInt()); assertEquals(-2,b.path("over").asInt()); assertEquals(5,b.path("occupied").asInt());
        assertEquals(9,a.path("right").asInt()); assertEquals(-2,a.path("over").asInt()); assertEquals(7,a.path("occupied").asInt());
        assertEquals(5,b.path("ticket").asInt()); assertEquals(0,new BigDecimal("11.00").compareTo(b.path("deposit").decimalValue()));
        verify(records).insert(argThat(r -> r.getOverBefore()==-2 && r.getOverAfter()==-2));
        verify(barrels,never()).rightQty(anyLong(),anyLong(),anyLong()); verify(barrels,never()).overQty(anyLong(),anyLong(),anyLong());
        verify(deposits,never()).getByCustomerAndStation(anyLong(),anyLong()); verifyNoInteractions(tickets);
        var order=inOrder(ticketAccounts,deposits,adjustments);
        order.verify(ticketAccounts).lockOrCreate(7L,2L,42L);
        order.verify(ticketAccounts).lockAccount(7L,2L,42L);
        order.verify(deposits).lockOrCreate(7L,2L);
        order.verify(deposits).getForUpdate(7L,2L);
        order.verify(adjustments).markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),anyString(),anyString());
    }
    @Test void ticketGrantLocksOrCreatesBeforeCurrentReadWithoutCreatingBarrelState() {
        request(AdjustType.TICKET_GRANT); service.execute(55L);
        var order=inOrder(ticketAccounts,adjustments,tickets);
        order.verify(ticketAccounts).lockOrCreate(7L,2L,42L);
        order.verify(ticketAccounts).lockAccount(7L,2L,42L);
        order.verify(adjustments).markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),anyString(),anyString());
        order.verify(tickets).adjustTicket(7L,42L,2,2L,55L);
        verify(barrels,never()).lockRights(anyLong(),anyLong(),anyLong());
    }
    @Test void depositOnlySnapshotUsesCurrentBalanceWithoutTouchingProductLedgers() {
        var a=request(AdjustType.DEPOSIT_GRANT); a.setProductId(null); a.setQty(null); a.setAmount(new BigDecimal("3.00"));
        service.execute(55L);
        verify(adjustments).markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),
                contains("\"deposit\":11.00"),contains("\"deposit\":14.00"));
        verifyNoInteractions(barrels,barrelBalances,ticketAccounts,tickets);
        var order=inOrder(deposits,adjustments);
        order.verify(deposits).lockOrCreate(7L,2L); order.verify(deposits).getForUpdate(7L,2L);
        order.verify(adjustments).markEffectiveIf(eq(55L),eq("PENDING"),eq("EFFECTIVE"),eq(11L),anyString(),anyString());
    }
    @Test void previewUsesOrdinaryReadAndNeverCreatesAccountsOrTakesWriteLocks() {
        when(barrels.rightQty(7L,2L,42L)).thenReturn(4); when(barrels.overQty(7L,2L,42L)).thenReturn(1);
        when(tickets.listByCustomerAndStation(7L,2L)).thenReturn(List.of());
        var result=service.preview(7L,AdjustType.BARREL_GRANT,42L,2,null,new BigDecimal("30.00"));
        assertEquals(5,((Map<?,?>)result.get("before")).get("occupied"));
        verify(barrelBalances,never()).currentBalanceForUpdate(anyLong(),anyLong(),anyLong());
        verify(barrels,never()).lockRights(anyLong(),anyLong(),anyLong());
        verify(deposits,never()).getForUpdate(anyLong(),anyLong());
        verify(deposits,never()).lockOrCreate(anyLong(),anyLong()); verifyNoInteractions(ticketAccounts,adjustments);
    }
    @Test void depositAccountFenceDoesNotChangeExistingBalanceOrTimestamp() throws Exception {
        var method=CustomerDepositAccountMapper.class.getMethod("lockOrCreate",Long.class,Long.class);
        String sql=String.join(" ",method.getAnnotation(Insert.class).value());
        assertTrue(sql.contains("values(#{customerId},#{stationId},0,NOW())"));
        assertEquals("on duplicate key update balance=balance",sql.substring(sql.indexOf("on duplicate")));
    }
}
