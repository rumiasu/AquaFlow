package com.example.aquaflow.service;

import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.StationDeliveryConfig;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationDeliveryConfigMapper;
import com.example.aquaflow.service.impl.DeliveryConfigGuideService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 建议值只读在售商品的本站有效售价，不改变保存配置或本单计价。 */
class DeliveryConfigGuideServiceTest {
    private final InventoryMapper inventories = mock(InventoryMapper.class);
    private final ProductMapper products = mock(ProductMapper.class);
    private final StationDeliveryConfigMapper configs = mock(StationDeliveryConfigMapper.class);
    private final DeliveryConfigGuideService service = new DeliveryConfigGuideService();

    DeliveryConfigGuideServiceTest() {
        ReflectionTestUtils.setField(service, "inventoryMapper", inventories);
        ReflectionTestUtils.setField(service, "productMapper", products);
        ReflectionTestUtils.setField(service, "stationDeliveryConfigMapper", configs);
        var config = StationDeliveryConfig.defaults(3L);
        config.setMinOrderBuckets(2); config.setFreeDeliveryBuckets(3);
        when(configs.getByStationId(3L)).thenReturn(config);
    }

    private Inventory inventory(long id, int enabled, String price) {
        var inv = new Inventory(); inv.setStationId(3L); inv.setProductId(id); inv.setEnabled(enabled);
        inv.setSalePrice(new BigDecimal(price)); return inv;
    }
    private void product(long id, int status, int category) {
        var product = new Product(); product.setId(id); product.setStatus(status); product.setCategory(category);
        product.setPrice(new BigDecimal("50.00")); when(products.getById(id)).thenReturn(product);
    }

    @Test void stoppedOrDisabledInventoryCannotLowerActiveBarrelSuggestions() {
        product(1L, 0, 1); product(2L, 2, 1); product(3L, 1, 1); product(4L, 1, 1);
        when(inventories.listByStationId(3L)).thenReturn(List.of(inventory(1L, 1, "1"),
            inventory(2L, 1, "2"), inventory(3L, 0, "3"), inventory(4L, 1, "10")));
        var guide = service.guidance(3L);
        assertEquals(new BigDecimal("10"), guide.get("cheapestWaterPrice"));
        assertEquals(new BigDecimal("20"), guide.get("suggestedMinOrderAmount"));
        assertEquals(new BigDecimal("30"), guide.get("suggestedFreeDeliveryAmount"));
    }

    @Test void onlyActiveNonBarrelProductsSupplyFallbackWhenAllBarrelsStopped() {
        product(1L, 0, 1); product(2L, 2, 2); product(3L, 1, 2);
        when(inventories.listByStationId(3L)).thenReturn(List.of(inventory(1L, 1, "1"),
            inventory(2L, 1, "2"), inventory(3L, 1, "8")));
        var guide = service.guidance(3L);
        assertNull(guide.get("cheapestWaterPrice")); assertEquals(new BigDecimal("8"), guide.get("cheapestPrice"));
        assertEquals(new BigDecimal("16"), guide.get("suggestedMinOrderAmount"));
        assertEquals(Boolean.TRUE, guide.get("nonBarrelOnShelf"));
    }

    @Test void noActiveProductsMeansNoSuggestedPrice() {
        product(1L, 0, 1);
        when(inventories.listByStationId(3L)).thenReturn(List.of(inventory(1L, 1, "1")));
        var guide = service.guidance(3L);
        assertNull(guide.get("cheapestPrice")); assertNull(guide.get("suggestedMinOrderAmount"));
        assertNull(guide.get("suggestedFreeDeliveryAmount"));
    }
}
