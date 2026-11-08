package com.example.aquaflow.service;

import com.example.aquaflow.entity.CustomerBarrelLot;
import com.example.aquaflow.entity.CustomerBarrelOver;
import com.example.aquaflow.mapper.CustomerBarrelLotMapper;
import com.example.aquaflow.mapper.CustomerBarrelOverMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Execution-only audit reader takes current locks but never invents a barrel account. */
class BarrelLedgerCurrentBalanceTest {
    @Test void currentSnapshotReadsOverBeforeAvailableLotsAndPreservesNegativeOver() {
        var over=mock(CustomerBarrelOverMapper.class); var lots=mock(CustomerBarrelLotMapper.class);
        var service=new BarrelBalanceReader(over,lots);
        var row=new CustomerBarrelOver(); row.setOverQty(-2); when(over.getForUpdate(7L,2L,42L)).thenReturn(row);
        var first=new CustomerBarrelLot(); first.setRemainQty(3); var second=new CustomerBarrelLot(); second.setRemainQty(4);
        when(lots.listAvailableForUpdate(7L,2L,42L)).thenReturn(List.of(first,second));
        assertEquals(new BarrelBalanceReader.CurrentBalance(7,-2),service.currentBalanceForUpdate(7L,2L,42L));
        var order=inOrder(over,lots); order.verify(over).getForUpdate(7L,2L,42L); order.verify(lots).listAvailableForUpdate(7L,2L,42L);
        verify(over,never()).lockOrCreate(anyLong(),anyLong(),anyLong()); verify(over,never()).get(anyLong(),anyLong(),anyLong());
    }
    @Test void missingBarrelStateReadsAsZeroWithoutAnyInsertOrAdjustment() {
        var over=mock(CustomerBarrelOverMapper.class); var lots=mock(CustomerBarrelLotMapper.class);
        var service=new BarrelBalanceReader(over,lots);
        when(lots.listAvailableForUpdate(7L,2L,42L)).thenReturn(List.of());
        assertEquals(new BarrelBalanceReader.CurrentBalance(0,0),service.currentBalanceForUpdate(7L,2L,42L));
        verify(over,never()).lockOrCreate(anyLong(),anyLong(),anyLong()); verify(over,never()).adjustOver(anyLong(),anyLong(),anyLong(),anyInt());
    }
}
