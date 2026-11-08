package com.example.aquaflow.service;

import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.TicketLot;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.TicketAccountServiceImpl;
import com.example.aquaflow.service.impl.TicketLotServiceImpl;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests use no database; they guard current-read wiring and additive SQL, not InnoDB scheduling. */
class TicketAdjustmentCurrentAccountTest {
    private final TicketAccountServiceImpl service=new TicketAccountServiceImpl();
    private final TicketAccountMapper accounts=mock(TicketAccountMapper.class);
    private final TicketLotService lots=mock(TicketLotService.class);
    private final TicketRecordMapper records=mock(TicketRecordMapper.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"ticketAccountMapper",accounts);
        ReflectionTestUtils.setField(service,"ticketLotService",lots);
        ReflectionTestUtils.setField(service,"ticketRecordMapper",records);
        ReflectionTestUtils.setField(service,"productMapper",mock(ProductMapper.class));
        ReflectionTestUtils.setField(service,"inventoryMapper",mock(InventoryMapper.class));
    }
    @Test void grantUsesNewlyVisibleAccountAndDoesNotInsertFromAStaleNullSnapshot() {
        TicketAccount account=new TicketAccount(); account.setId(20L); account.setRemainQuantity(8);
        when(accounts.lockAccount(7L,2L,42L)).thenReturn(account);
        TicketLot lot=new TicketLot(); lot.setId(30L); lot.setUnitPrice(BigDecimal.ONE);
        when(lots.createLot(eq(7L),eq(2L),eq(42L),any(),eq(3),anyInt(),anyInt(),eq(true),isNull(),anyString())).thenReturn(lot);
        service.adjustTicket(7L,42L,3,2L,55L);
        var order=inOrder(accounts,lots,records);
        order.verify(accounts).lockOrCreate(7L,2L,42L); order.verify(accounts).lockAccount(7L,2L,42L);
        order.verify(lots).createLot(eq(7L),eq(2L),eq(42L),any(),eq(3),anyInt(),anyInt(),eq(true),isNull(),anyString());
        order.verify(accounts).incrementQuantity(20L,3);
        order.verify(records).insert(argThat(r -> r.getAdjustmentId().equals(55L) && r.getIncreaseQty()==3 && r.getTicketLotId().equals(30L)));
        verify(accounts,never()).getByCustomerProductStation(anyLong(),anyLong(),anyLong());
        verify(accounts,never()).insert(any()); verify(accounts,never()).updateQuantity(anyLong(),anyInt());
    }
    @Test void missingAccountOnDeductionNeverCreatesAnEmptyAccountOrWritesALot() {
        assertThrows(BusinessException.class,()->service.adjustTicket(7L,42L,-1,2L,55L));
        verify(accounts).lockAccount(7L,2L,42L); verify(accounts,never()).lockOrCreate(anyLong(),anyLong(),anyLong());
        verifyNoInteractions(lots,records);
    }
    @Test void batchFailurePropagatesBeforeQuantityOrRecordWrites() {
        TicketAccount account=new TicketAccount(); account.setId(20L);
        when(accounts.lockAccount(7L,2L,42L)).thenReturn(account);
        doThrow(new BusinessException("synthetic failure")).when(lots).createLot(anyLong(),anyLong(),anyLong(),any(),anyInt(),anyInt(),anyInt(),anyBoolean(),isNull(),anyString());
        assertThrows(BusinessException.class,()->service.adjustTicket(7L,42L,3,2L,55L));
        verify(accounts,never()).incrementQuantity(anyLong(),anyInt()); verifyNoInteractions(records);
    }
    @Test void amountRefreshFindsAccountFromCurrentReadEvenIfOrdinarySnapshotWouldBeNull() {
        var service=new TicketLotServiceImpl(); var mapper=mock(TicketLotMapper.class);
        ReflectionTestUtils.setField(service,"ticketAccountMapper",accounts); ReflectionTestUtils.setField(service,"ticketLotMapper",mapper);
        TicketAccount account=new TicketAccount(); account.setId(20L); when(accounts.lockAccount(7L,2L,42L)).thenReturn(account);
        when(mapper.currentRightAmount(7L,2L,42L)).thenReturn(new BigDecimal("27.00"));
        service.refreshRightAmount(7L,2L,42L);
        verify(accounts).setRightAmount(20L,new BigDecimal("27.00"));
        verify(accounts,never()).getByCustomerProductStation(anyLong(),anyLong(),anyLong());
    }
    @Test void accountFenceIsANonDestructiveUpsertRatherThanBalanceReplacement() throws Exception {
        var method=TicketAccountMapper.class.getMethod("lockOrCreate",Long.class,Long.class,Long.class);
        String sql=String.join(" ",method.getAnnotation(Insert.class).value());
        assertTrue(sql.contains("on duplicate key update remain_quantity=remain_quantity"));
        assertFalse(sql.substring(sql.indexOf("on duplicate")).contains("update_time"));
        assertTrue(sql.contains("#{customer},#{product},#{station},0,NOW()"));
    }
}
