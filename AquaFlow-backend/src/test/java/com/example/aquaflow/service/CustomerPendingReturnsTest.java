package com.example.aquaflow.service;

import com.example.aquaflow.entity.BarrelReturnDetail;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.BarrelReturnDetailMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerPendingReturnsTest {
    private BarrelReturnDetail row(long id, boolean required, String fee, Integer confirmed) {
        var d=new BarrelReturnDetail(); d.setRecordId(id); d.setStationId(12L); d.setStationName("测试站");
        d.setArrangementVersion(2); d.setArrangementRequiresConfirmation(required); d.setPickupFee(new BigDecimal(fee));
        d.setCustomerConfirmedVersion(confirmed); if (confirmed!=null) d.setCustomerConfirmedTime(LocalDateTime.now());
        return d;
    }
    @Test void derivesSameRuleAsDetailAndScopesMapperToCustomer() {
        var policy=mock(BarrelBusinessPolicy.class); var mapper=mock(BarrelReturnDetailMapper.class);
        when(policy.hasSchema()).thenReturn(true);
        when(mapper.approvedForCustomer(7L)).thenReturn(List.of(row(1,false,"0",null),row(2,false,"5",null),
            row(3,true,"0",1),row(4,true,"0",2)));
        var service=new ApprovedBarrelReturnService(); ReflectionTestUtils.setField(service,"policy",policy);
        ReflectionTestUtils.setField(service,"detailMapper",mapper);
        var result=service.customerPendingConfirmations(7L);
        assertEquals(List.of(2L,3L),result.stream().map(x -> x.get("recordId")).toList());
        assertEquals(12L,result.get(0).get("stationId")); assertEquals("测试站",result.get(0).get("stationName"));
        verify(mapper).approvedForCustomer(7L);
    }
    @Test void missingSchemaIsUnknownNotAnEmptyPendingList() {
        var service=new ApprovedBarrelReturnService(); var policy=mock(BarrelBusinessPolicy.class);
        ReflectionTestUtils.setField(service,"policy",policy);
        assertThrows(BusinessException.class,() -> service.customerPendingConfirmations(7L));
    }
}
