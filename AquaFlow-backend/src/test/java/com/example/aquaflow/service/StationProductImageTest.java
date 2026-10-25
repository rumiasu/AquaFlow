package com.example.aquaflow.service;

import com.example.aquaflow.dto.ProductWithInventoryVO;
import com.example.aquaflow.dto.StationCatalogSettingDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.impl.CatalogServiceImpl;
import com.example.aquaflow.util.StationProductImage;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StationProductImageTest {
    private record Fixture(CatalogServiceImpl service, ProductMapper products, InventoryMapper inventory,
                           InventoryService stock, ProductWithInventoryVO product) {}
    private Fixture fixture(long station) {
        var products = mock(ProductMapper.class); var inventory = mock(InventoryMapper.class); var stock = mock(InventoryService.class);
        var service = new CatalogServiceImpl();
        ReflectionTestUtils.setField(service, "productMapper", products);
        ReflectionTestUtils.setField(service, "inventoryMapper", inventory);
        ReflectionTestUtils.setField(service, "inventoryService", stock);
        ReflectionTestUtils.setField(service, "priceWarnRatio", 0.5);
        var product = new ProductWithInventoryVO(); product.setId(17L); product.setCategory(1); product.setStatus(1);
        product.setPrice(new BigDecimal("12")); product.setDeposit(new BigDecimal("50")); product.setInventoryId(3L);
        when(products.getByIdWithInventory(17L, station)).thenReturn(product);
        var current = new Inventory(); current.setStationId(station); current.setProductId(17L); current.setQuantity(10);
        when(inventory.getByStationAndProduct(station, 17L)).thenReturn(current);
        when(inventory.updateStationImage(eq(station), eq(17L), nullable(String.class))).thenReturn(1);
        return new Fixture(service, products, inventory, stock, product);
    }
    @Test void replacingOnlyWritesTheSelectedStationImageAndNeverPublicProduct() {
        var f=fixture(11);var dto=new StationCatalogSettingDTO();dto.setImageObjectName("public/products/confirmed.jpg");
        f.service.saveStationSetting(11L,17L,dto,false);
        verify(f.inventory).updateStationImage(11L,17L,"public/products/confirmed.jpg");
        verify(f.products).getByIdWithInventory(17L,11L);verifyNoMoreInteractions(f.products);
        verify(f.inventory,never()).updateStationImage(eq(22L),anyLong(),any());
    }
    @Test void restoringUsesNullAndOmittingImageDoesNotClearAnExistingOverride() {
        var f=fixture(11);var dto=new StationCatalogSettingDTO();dto.setImageObjectName("");
        f.service.saveStationSetting(11L,17L,dto,false);verify(f.inventory).updateStationImage(11L,17L,null);
        var untouched=fixture(22);untouched.service.saveStationSetting(22L,17L,new StationCatalogSettingDTO(),false);
        verify(untouched.inventory,never()).updateStationImage(anyLong(),anyLong(),any());
    }
    @Test void unavailableOrForeignProductsCannotReachAnyImageWrite() {
        var f=fixture(11);when(f.products.getByIdWithInventory(17L,11L)).thenReturn(null);
        var dto=new StationCatalogSettingDTO();dto.setImageObjectName("public/products/a.jpg");
        assertThrows(BusinessException.class,()->f.service.saveStationSetting(11L,17L,dto,false));verifyNoInteractions(f.stock);
        verify(f.inventory,never()).updateStationImage(anyLong(),anyLong(),any());
    }
    @Test void unconfirmedTemporaryAndSignedReferencesAreRejectedBeforeSettingsWrite() {
        for(String value:new String[]{"wxfile://tmp/a.jpg","http://site/a.jpg","https://site/a.jpg?q-signature=secret","../a.jpg","/tmp/a.jpg","public/a.png#token","private/order/1/a.jpg","tmp/a.jpg","https://tmp/a.jpg","https://127.0.0.1/a.jpg","https://192.168.1.2/a.jpg"}) {
            var f=fixture(11);var dto=new StationCatalogSettingDTO();dto.setImageObjectName(value);
            assertThrows(BusinessException.class,()->f.service.saveStationSetting(11L,17L,dto,false),value);verifyNoInteractions(f.stock);
        }
    }
    @Test void validKeysPresetsAndPermanentHttpsImagesSurviveWithoutPreviewTokens() {
        for(String value:new String[]{"public/products/a.jpg","/assets/product/barrel.webp","https://example.com/a.png"})assertEquals(value,StationProductImage.normalize(value));
        assertNull(StationProductImage.normalize(""));assertThrows(BusinessException.class,()->StationProductImage.normalize("a".repeat(513)));
    }
    @Test void anUnconfirmedUpdateCannotBeReportedAsSaved() {
        var f=fixture(11);when(f.inventory.updateStationImage(anyLong(),anyLong(),any())).thenReturn(0);
        var dto=new StationCatalogSettingDTO();dto.setImageObjectName("public/products/a.jpg");
        assertThrows(BusinessException.class,()->f.service.saveStationSetting(11L,17L,dto,false));
    }
    @Test void repeatedSameImageCanConfirmTheExistingScopedValue() {
        var f=fixture(11);when(f.inventory.updateStationImage(anyLong(),anyLong(),any())).thenReturn(0);
        var current=new Inventory();current.setImageObjectNameOverride("public/products/a.jpg");
        when(f.inventory.getByStationAndProductForUpdate(11L,17L)).thenReturn(current);
        var dto=new StationCatalogSettingDTO();dto.setImageObjectName("public/products/a.jpg");
        assertDoesNotThrow(()->f.service.saveStationSetting(11L,17L,dto,false));
    }
    @Test void sqlWriteIsScopedAndCannotChangeInventoryOrPublicProduct() throws Exception {
        String sql=String.join(" ",InventoryMapper.class.getMethod("updateStationImage",Long.class,Long.class,String.class).getAnnotation(Update.class).value());
        assertTrue(sql.contains("station_id=#{stationId}"));assertTrue(sql.contains("product_id=#{productId}"));
        assertFalse(sql.contains("quantity="));assertFalse(sql.contains("update product "));
        var vo=new ProductWithInventoryVO();vo.setInventoryId(1L);assertTrue(vo.getStationImageEditable());vo.setOwnerStationId(11L);assertFalse(vo.getStationImageEditable());
    }
    @Test void saveReceiptReadsActualStationValueAndNeverEchoesTheRequest() {
        var f=fixture(11);var current=new Inventory();current.setImageObjectNameOverride("public/common/persisted.jpg");
        when(f.inventory.getByStationAndProduct(11L,17L)).thenReturn(current);
        assertEquals("public/common/persisted.jpg",f.service.getStationImage(11L,17L));
        assertThrows(BusinessException.class,()->f.service.getStationImage(22L,17L));
        verify(f.inventory).getByStationAndProduct(22L,17L);
    }
    @Test void stationSettingsCannotOverwriteConcurrentStockOrClearImageOverrides() throws Exception {
        String sql=String.join(" ",InventoryMapper.class.getMethod("upsertSettings",Inventory.class).getAnnotation(org.apache.ibatis.annotations.Insert.class).value());
        String duplicate=sql.substring(sql.indexOf("on duplicate key update"));
        assertFalse(duplicate.contains("quantity"));assertFalse(duplicate.contains("image_object_name_override"));
        assertTrue(sql.contains("#{quantity}"));
    }

}
