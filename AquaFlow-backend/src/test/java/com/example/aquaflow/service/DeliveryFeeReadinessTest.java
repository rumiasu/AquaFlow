package com.example.aquaflow.service;

import com.example.aquaflow.entity.StationDeliveryConfig;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerPrivilegeMapper;
import com.example.aquaflow.mapper.StationDeliveryConfigMapper;
import com.example.aquaflow.service.impl.DeliveryConfigGuideService;
import com.example.aquaflow.service.impl.DeliveryFeeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryFeeReadinessTest {
    @Test
    void quoteRequiresSavedConfigurationAndApplicableAmountThresholds() {
        var mapper = mock(StationDeliveryConfigMapper.class);
        var guide = mock(DeliveryConfigGuideService.class);
        var privileges = mock(CustomerPrivilegeMapper.class);
        var service = new DeliveryFeeServiceImpl();
        ReflectionTestUtils.setField(service, "stationDeliveryConfigMapper", mapper);
        ReflectionTestUtils.setField(service, "deliveryConfigGuideService", guide);
        ReflectionTestUtils.setField(service, "customerPrivilegeMapper", privileges);

        assertThrows(BusinessException.class, () -> service.calcForOrder(1L, 2L, null, 0, BigDecimal.TEN));
        var saved = StationDeliveryConfig.defaults(2L);
        when(mapper.getByStationId(2L)).thenReturn(saved);
        when(guide.hasNonBarrelOnShelf(2L)).thenReturn(true);
        when(guide.missingAmountFields(saved)).thenReturn(List.of("minOrderAmount"));
        assertThrows(BusinessException.class, () -> service.calcForOrder(1L, 2L, null, 0, BigDecimal.TEN));
        when(guide.missingAmountFields(saved)).thenReturn(List.of());
        assertEquals(0, BigDecimal.ZERO.compareTo(service.calcForOrder(1L, 2L, null, 0, BigDecimal.TEN).getDeliveryFee()));
    }
}
