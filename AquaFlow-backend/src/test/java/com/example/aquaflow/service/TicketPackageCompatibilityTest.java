package com.example.aquaflow.service;

import com.example.aquaflow.entity.*;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.service.impl.TicketTierService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketPackageCompatibilityTest {
    TicketPackageMapper packages; InventoryMapper inventory; TicketPackageService service;
    TicketPackage one;
    TicketPackage tier(long id,int qty,int status) {
        TicketPackage p=new TicketPackage(); p.setId(id);p.setStationId(1L);p.setProductId(5L);
        p.setQty(qty);p.setPrice(new BigDecimal("8.00"));p.setUnitPrice(new BigDecimal("8.00"));p.setStatus(status);p.setSort(0);return p;
    }
    @BeforeEach void setup() {
        packages=mock(TicketPackageMapper.class);inventory=mock(InventoryMapper.class);service=new TicketPackageService(packages,inventory);
        when(inventory.getByStationAndProductForUpdate(1L,5L)).thenReturn(new Inventory());
        when(inventory.updateTicketPrice(eq(1L),eq(5L),any())).thenReturn(1);when(packages.update(any())).thenReturn(1);
        one=tier(77,1,1);when(packages.getById(77L)).thenReturn(one);when(packages.getByIdForUpdate(77L)).thenReturn(one);
    }
    @Test void deletionDisablesOneTierAndClearsCompatibilityWithoutDeletingHistorySource() {
        service.delete(1L,77L);assertEquals(0,one.getStatus());verify(packages).update(one);
        verify(inventory).updateTicketPrice(1L,5L,BigDecimal.ZERO);verify(packages,never()).delete(any(),any());
    }
    @Test void disablingOrEditingRealOneUpdatesCompatibilityInSameServiceTransaction() {
        TicketPackage body=tier(77,1,0);service.save(1L,body);
        verify(inventory).updateTicketPrice(1L,5L,BigDecimal.ZERO);
        reset(inventory);when(inventory.getByStationAndProductForUpdate(1L,5L)).thenReturn(new Inventory());when(inventory.updateTicketPrice(eq(1L),eq(5L),any())).thenReturn(1);
        body.setStatus(1);body.setPrice(new BigDecimal("9.25"));service.save(1L,body);
        verify(inventory).updateTicketPrice(1L,5L,new BigDecimal("9.25"));
    }
    @Test void changingOneTierQuantityKeepsIdAndLeavesDisabledOneTombstone() {
        TicketPackage body=tier(77,10,1);service.save(1L,body);
        ArgumentCaptor<TicketPackage> capture=ArgumentCaptor.forClass(TicketPackage.class);verify(packages).update(capture.capture());
        assertEquals(77L,capture.getValue().getId());assertEquals(10,capture.getValue().getQty());
        verify(packages).upsert(argThat(p->p.getQty()==1&&p.getStatus()==0&&p.getId()==null));
        verify(inventory).updateTicketPrice(1L,5L,BigDecimal.ZERO);
    }
    @Test void duplicateQtyCrossStationAndRoundedZeroAreRejectedWithoutTierWrites() {
        when(packages.getByStationProductQty(1L,5L,10)).thenReturn(tier(88,10,1));
        assertThrows(BusinessException.class,()->service.save(1L,tier(77,10,1)));
        assertThrows(BusinessException.class,()->service.delete(2L,77L));
        TicketPackage tiny=tier(77,1,1);tiny.setPrice(new BigDecimal("0.001"));assertThrows(BusinessException.class,()->service.save(1L,tiny));
        verify(packages,never()).update(any());verify(packages,never()).upsert(any());verify(inventory,never()).updateTicketPrice(any(),any(),any());
    }
    @Test void failedCompatibilityMirrorCannotReturnSuccess() throws Exception {
        when(inventory.updateTicketPrice(eq(1L),eq(5L),any())).thenReturn(0);
        assertThrows(BusinessException.class,()->service.save(1L,tier(77,1,1)));
        assertNotNull(TicketPackageService.class.getDeclaredMethod("save",Long.class,TicketPackage.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }
    @Test void customerRetailOptInIsStableAndSuppressedByDisabledRealOne() {
        ProductMapper products=mock(ProductMapper.class);StationTicketDiscountMapper discounts=mock(StationTicketDiscountMapper.class);
        TicketTierService tiers=new TicketTierService();ReflectionTestUtils.setField(tiers,"ticketPackageMapper",packages);
        ReflectionTestUtils.setField(tiers,"inventoryMapper",inventory);ReflectionTestUtils.setField(tiers,"productMapper",products);ReflectionTestUtils.setField(tiers,"stationTicketDiscountMapper",discounts);
        Product product=new Product();product.setId(5L);product.setCategory(2);Inventory inv=new Inventory();inv.setStationId(1L);inv.setEnabled(1);inv.setTicketEnabled(1);inv.setTicketPrice(new BigDecimal("8.00"));
        when(products.getById(5L)).thenReturn(product);when(inventory.getByStationAndProduct(1L,5L)).thenReturn(inv);when(packages.listOnShelf(1L,5L)).thenReturn(List.of());
        assertTrue(tiers.customerTiers(1L,5L).isEmpty());
        assertEquals("RETAIL",tiers.customerTiers(1L,5L,true).get(0).get("source"));
        one.setStatus(0);when(packages.getByStationProductQty(1L,5L,1)).thenReturn(one);
        assertTrue(tiers.customerTiers(1L,5L,true).isEmpty());
        inv.setTicketPrice(BigDecimal.ZERO);when(packages.listAll(1L,5L)).thenReturn(List.of(one));
        assertTrue(tiers.usesCustomTicket(product,inv));assertTrue(tiers.customerTiers(1L,5L,true).isEmpty());
    }
}
