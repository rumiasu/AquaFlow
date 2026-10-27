package com.example.aquaflow.service;

import com.example.aquaflow.entity.StationDeliveryConfig;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerPrivilegeMapper;
import com.example.aquaflow.mapper.StationDeliveryConfigMapper;
import com.example.aquaflow.service.impl.DeliveryFeeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryFeeReadinessTest {
    @Test
    void quoteRequiresSavedConfigurationAndAppliesPersistedRulesToTheCurrentOrder() {
        var mapper = mock(StationDeliveryConfigMapper.class);
        var privileges = mock(CustomerPrivilegeMapper.class);
        var service = new DeliveryFeeServiceImpl();
        ReflectionTestUtils.setField(service, "stationDeliveryConfigMapper", mapper);
        ReflectionTestUtils.setField(service, "customerPrivilegeMapper", privileges);

        assertThrows(BusinessException.class, () -> service.calcForOrder(1L, 2L, null, 0, BigDecimal.TEN));
        var saved = StationDeliveryConfig.defaults(2L);
        saved.setMinOrderBuckets(2); saved.setFreeDeliveryBuckets(3); saved.setBaseDeliveryFee(new BigDecimal("5.00"));
        when(mapper.getByStationId(2L)).thenReturn(saved);
        assertEquals(new BigDecimal("5.00"),service.calcForOrder(1L,2L,null,0,BigDecimal.TEN).getDeliveryFee());
        var barrel=service.calcForOrder(1L,2L,null,1,BigDecimal.TEN);
        assertFalse(barrel.isBlocked()); assertFalse(barrel.getWarnings().isEmpty());
        assertEquals(new BigDecimal("5.00"),barrel.getDeliveryFee());
        assertEquals(0,BigDecimal.ZERO.compareTo(service.calcForOrder(1L,2L,null,3,BigDecimal.TEN).getDeliveryFee()));
        saved.setMinOrderMode("FEE"); saved.setMinOrderFee(new BigDecimal("2.00"));
        assertEquals(new BigDecimal("7.00"),service.calcForOrder(1L,2L,null,1,BigDecimal.TEN).getDeliveryFee());
        assertEquals(new BigDecimal("5.00"),service.calcForOrder(1L,2L,null,0,BigDecimal.TEN).getDeliveryFee());
        saved.setFreeDeliveryAmount(BigDecimal.ZERO);
        assertEquals(0,BigDecimal.ZERO.compareTo(service.calcForOrder(1L,2L,null,0,BigDecimal.TEN).getDeliveryFee()));
    }
}
