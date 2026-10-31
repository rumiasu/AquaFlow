package com.example.aquaflow.service;

import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure mocked quote test: no Spring context, database, payment or network. */
class IndependentBarrelQuoteCapabilityTest {
    private Map<String, Object> quote(boolean mockEnabled) {
        IndependentBarrelService service = new IndependentBarrelService();
        BarrelBusinessPolicy policy = mock(BarrelBusinessPolicy.class);
        ProductMapper products = mock(ProductMapper.class);
        StationMapper stations = mock(StationMapper.class);
        InventoryMapper inventory = mock(InventoryMapper.class);
        BarrelLedgerService ledger = mock(BarrelLedgerService.class);
        ReflectionTestUtils.setField(service, "policy", policy);
        ReflectionTestUtils.setField(service, "productMapper", products);
        ReflectionTestUtils.setField(service, "stationMapper", stations);
        ReflectionTestUtils.setField(service, "inventoryMapper", inventory);
        ReflectionTestUtils.setField(service, "ledger", ledger);
        var mockPolicy = mock(com.example.aquaflow.payment.MockPaymentPolicy.class);
        when(mockPolicy.allows(7L)).thenReturn(mockEnabled);
        // The service forwards the policy's whole capability, including customer authorization.
        var channel = PayMethod.payChannel(PayMethod.WECHAT, mockEnabled);
        if (mockEnabled) channel.put("simulationAuthorization", Map.of("version", 1, "scope", "LOCAL_TEST", "customerId", 7L));
        when(mockPolicy.channel(7L)).thenReturn(channel);
        ReflectionTestUtils.setField(service, "mockPolicy", mockPolicy);
        Station station = new Station(); station.setStatus(1); station.setName("本站");
        Product product = new Product(); product.setCategory(1); product.setStatus(1);
        product.setName("桶装水"); product.setDeposit(new BigDecimal("60.00"));
        Inventory stock = new Inventory(); stock.setEnabled(1); stock.setDepositPrice(new BigDecimal("50.00"));
        when(policy.isEnabled()).thenReturn(true);
        when(stations.getById(1L)).thenReturn(station);
        when(products.getById(5L)).thenReturn(product);
        when(inventory.getByStationAndProduct(1L, 5L)).thenReturn(stock);
        when(ledger.availableRights(7L, 1L, 5L)).thenReturn(6);
        Map<String, Object> result = service.quote(7L, 1L, 5L, 2);
        assertEquals(new BigDecimal("100.00"), result.get("amount"));
        assertEquals(new BigDecimal("50.00"), result.get("unitPrice"));
        assertEquals(1L, result.get("stationId")); assertEquals(5L, result.get("productId"));
        assertEquals(6, result.get("availableRights")); assertEquals(mockEnabled, result.get("onlineAvailable"));
        assertSame(channel, result.get("wechatPay")); verify(mockPolicy).channel(7L);
        verify(ledger).availableRights(7L, 1L, 5L); verifyNoMoreInteractions(ledger);
        return result;
    }

    @Test
    void mockChannelIsExplicitlyLabelledAndQuoteDoesNotActivateAssets() {
        Map<?, ?> channel = assertInstanceOf(Map.class, quote(true).get("wechatPay"));
        assertEquals(1, channel.get("method"));
        assertEquals(true, channel.get("enabled")); assertEquals(true, channel.get("simulated"));
        assertTrue(((String) channel.get("label")).contains("模拟微信支付"));
        var authorization = assertInstanceOf(Map.class, channel.get("simulationAuthorization"));
        assertEquals(7L, authorization.get("customerId")); assertEquals("LOCAL_TEST", authorization.get("scope"));
    }

    @Test
    void disabledChannelRemainsUnavailableWithAnExplicitLabel() {
        Map<?, ?> channel = assertInstanceOf(Map.class, quote(false).get("wechatPay"));
        assertEquals(false, channel.get("enabled")); assertEquals(false, channel.get("simulated"));
        assertTrue(((String) channel.get("label")).contains("暂未开通"));
        assertFalse(channel.containsKey("simulationAuthorization"));
    }
}
