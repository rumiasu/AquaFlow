package com.example.aquaflow.service;

import com.example.aquaflow.dto.*;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerFeedbackRepairTest {
    private record Catalog(CatalogServiceImpl service,ProductMapper products,InventoryService inventory,Product product) {}
    private Catalog catalog(int status) {
        var service=new CatalogServiceImpl();var products=mock(ProductMapper.class);var mapper=mock(InventoryMapper.class);var inventory=mock(InventoryService.class);
        ReflectionTestUtils.setField(service,"productMapper",products);ReflectionTestUtils.setField(service,"inventoryMapper",mapper);ReflectionTestUtils.setField(service,"inventoryService",inventory);ReflectionTestUtils.setField(service,"priceWarnRatio",0.5);
        var p=new Product();p.setId(17L);p.setOwnerStationId(11L);p.setStatus(status);p.setCategory(2);p.setPrice(new BigDecimal("12"));p.setDeposit(BigDecimal.ZERO);
        when(products.getOwnedById(17L,11L)).thenReturn(p);when(products.updateOwned(any(),eq(11L))).thenReturn(1);
        var vo=new ProductWithInventoryVO();vo.setId(17L);vo.setOwnerStationId(11L);vo.setCategory(2);vo.setStatus(1);vo.setPrice(new BigDecimal("12"));when(products.getByIdWithInventory(17L,11L)).thenReturn(vo);
        var inv=new Inventory();inv.setStationId(11L);inv.setProductId(17L);inv.setQuantity(7);inv.setEnabled(1);when(mapper.getByStationAndProduct(11L,17L)).thenReturn(inv);
        return new Catalog(service,products,inventory,p);
    }
    @Test void inactiveProductCannotUseOrdinaryEditOrSilentlyChangeFields() {
        var c=catalog(0);var dto=new ManagerProductDTO.Update();dto.setName("must not apply");
        assertThrows(BusinessException.class,()->c.service.updateMyProduct(11L,17L,dto));assertNull(c.product.getName());verify(c.products,never()).updateOwned(any(),anyLong());verifyNoInteractions(c.inventory);
    }
    @Test void explicitRestoreIsOwnedAndAlwaysOffShelfWithoutChangingStockOrPrice() {
        var c=catalog(0);var dto=new ManagerProductDTO.Update();dto.setStatus(1);dto.setEnabled(0);
        c.service.updateMyProduct(11L,17L,dto);assertEquals(1,c.product.getStatus());assertEquals(new BigDecimal("12"),c.product.getPrice());
        verify(c.products).updateOwned(same(c.product),eq(11L));verify(c.inventory).saveStationSetting(argThat(i->Integer.valueOf(0).equals(i.getEnabled())));verify(c.inventory,never()).setStock(anyLong(),anyLong(),anyInt(),any(),any(),any());
    }
    @Test void restoreCannotPublishImmediatelyOrAddressAnotherStation() {
        var c=catalog(0);var dto=new ManagerProductDTO.Update();dto.setStatus(1);dto.setEnabled(1);assertThrows(BusinessException.class,()->c.service.updateMyProduct(11L,17L,dto));
        dto.setEnabled(0);assertThrows(BusinessException.class,()->c.service.updateMyProduct(22L,17L,dto));verify(c.products,never()).updateOwned(any(),anyLong());
    }
    @Test void invalidStatusCannotBypassExistingRemoveCommand() {
        var c=catalog(1);var dto=new ManagerProductDTO.Update();dto.setStatus(0);assertThrows(BusinessException.class,()->c.service.updateMyProduct(11L,17L,dto));verify(c.products,never()).updateOwned(any(),anyLong());
    }
    private record Guide(StationSetupGuideService service,Station station,Inventory inventory,StaffPieceRateMapper rates) {}
    private Guide guide() {
        var service=new StationSetupGuideService();var stations=mock(StationMapper.class);var inventories=mock(InventoryMapper.class);var staff=mock(StaffMapper.class);var rates=mock(StaffPieceRateMapper.class);var fees=mock(StationDeliveryConfigMapper.class);
        ReflectionTestUtils.setField(service,"stationMapper",stations);ReflectionTestUtils.setField(service,"inventoryMapper",inventories);ReflectionTestUtils.setField(service,"staffMapper",staff);ReflectionTestUtils.setField(service,"staffPieceRateMapper",rates);ReflectionTestUtils.setField(service,"stationDeliveryConfigMapper",fees);ReflectionTestUtils.setField(service,"productMapper",mock(ProductMapper.class));ReflectionTestUtils.setField(service,"ticketPackageMapper",mock(TicketPackageMapper.class));ReflectionTestUtils.setField(service,"stationTicketDiscountMapper",mock(StationTicketDiscountMapper.class));
        var s=new Station();s.setId(11L);s.setPhone("synthetic");s.setAddress("synthetic address");s.setLat(BigDecimal.ONE);s.setLng(BigDecimal.ONE);when(stations.getById(11L)).thenReturn(s);
        var i=new Inventory();i.setProductId(17L);i.setStatus(1);i.setEnabled(1);i.setTicketEnabled(0);when(inventories.listByStationId(11L)).thenReturn(List.of(i));when(fees.getByStationId(11L)).thenReturn(new StationDeliveryConfig());when(staff.listByStationIdAndRole(11L,"DELIVERY")).thenReturn(List.of(new Staff()));
        var rate=new StaffPieceRate();rate.setPerBucketAmount(new BigDecimal("2"));when(rates.getByStationAndProduct(11L,0L)).thenReturn(rate);return new Guide(service,s,i,rates);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> item(Guide g,String key) {return ((List<Map<String,Object>>)g.service.guide(11L).get("items")).stream().filter(i->key.equals(i.get("key"))).findFirst().orElseThrow();}
    @Test void completedLocationHasACompletedExplanation() {var g=guide();var i=item(g,"stationPosition");assertEquals(true,i.get("done"));assertTrue(i.get("why").toString().contains("均已保存"));assertFalse(i.get("why").toString().contains("没地址"));}
    @Test void eachIncompleteLocationExplainsOnlyTheMissingPart() {var g=guide();g.station.setLat(null);assertTrue(item(g,"stationPosition").get("why").toString().contains("有地址但没坐标"));g.station.setLat(BigDecimal.ONE);g.station.setAddress(null);assertTrue(item(g,"stationPosition").get("why").toString().contains("有坐标但没地址"));g.station.setLat(null);assertTrue(item(g,"stationPosition").get("why").toString().contains("地址和坐标都还没有"));}
    @Test void configuredPayrollNeverClaimsNoRateAndMissingPayrollRemainsPending() {var g=guide();var i=item(g,"staffPayroll");assertEquals(true,i.get("done"));assertTrue(i.get("why").toString().contains("已配置"));when(g.rates.getByStationAndProduct(11L,0L)).thenReturn(null);i=item(g,"staffPayroll");assertEquals(false,i.get("done"));assertTrue(i.get("why").toString().contains("没配计件单价"));}
    @Test void retiredInventoryDoesNotCountAsSellableForSetup() {var g=guide();g.inventory.setStatus(0);assertEquals(false,item(g,"catalogOnShelf").get("done"));}
}
