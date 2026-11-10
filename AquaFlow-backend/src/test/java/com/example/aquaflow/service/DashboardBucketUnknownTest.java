package com.example.aquaflow.service;

import com.example.aquaflow.mapper.DashboardMapper;
import com.example.aquaflow.service.impl.DashboardServiceImpl;
import com.example.aquaflow.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardBucketUnknownTest {
    private Map<String,Object> row(Long qty, long missing) {
        Map<String,Object> row = new LinkedHashMap<>();
        if (qty != null) row.put("deliveryBucketQty", qty); // MyBatis omits null map values by default.
        row.put("deliveryBucketUnknownOrders", missing);
        return row;
    }
    private Map<String,Object> report(Long current, Long previous, long missing) {
        int[] calls={0};
        DashboardMapper mapper=(DashboardMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DashboardMapper.class},(proxy,method,args)-> {
            if(method.getName().equals("summary")) return ++calls[0]==1 ? row(current,missing) : row(previous,previous==null?1:0);
            if(method.getName().equals("staffPerformance")) return new ArrayList<>(List.of(row(current,missing)));
            return method.getReturnType()==int.class ? 0 : List.of();
        });
        DashboardServiceImpl service=new DashboardServiceImpl();
        ReflectionTestUtils.setField(service,"dashboardMapper",mapper);
        ReflectionTestUtils.setField(service,"businessTime",new BusinessTime(Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"),ZoneId.of("Asia/Shanghai"))));
        return service.report(73L,"today");
    }
    private Map<?,?> comparison(Map<String,Object> r){return (Map<?,?>)((Map<?,?>)r.get("compare")).get("deliveryBucketQty");}
    @Test void absentSnapshotStaysUnknownInSummaryStaffAndComparison(){
        var r=report(null,4L,1); var summary=(Map<?,?>)r.get("summary");
        assertTrue(summary.containsKey("deliveryBucketQty")); assertNull(summary.get("deliveryBucketQty"));
        assertEquals(false,summary.get("deliveryBucketQtyKnown")); assertEquals(1L,summary.get("deliveryBucketUnknownOrders"));
        var staff=(Map<?,?>)((List<?>)r.get("staffPerformance")).get(0); assertNull(staff.get("deliveryBucketQty")); assertEquals(false,staff.get("deliveryBucketQtyKnown"));
        var c=comparison(r); assertNull(c.get("current")); assertEquals(4L,c.get("previous")); assertEquals(false,c.get("known")); assertEquals("flat",c.get("deltaDir")); assertNull(c.get("good")); assertFalse(c.containsKey("deltaPct"));
    }
    @Test void previousUnknownDoesNotHideKnownCurrent(){var c=comparison(report(5L,null,0));assertEquals(5L,c.get("current"));assertNull(c.get("previous"));assertEquals(false,c.get("known"));assertNull(c.get("good"));}
    @Test void explicitZeroIsKnown(){var r=report(0L,0L,0);assertEquals(0L,((Map<?,?>)r.get("summary")).get("deliveryBucketQty"));assertEquals(true,comparison(r).get("known"));assertEquals("持平",comparison(r).get("deltaText"));}
    @Test void partialSumCannotMasqueradeAsComplete(){var r=report(5L,4L,2);assertNull(((Map<?,?>)r.get("summary")).get("deliveryBucketQty"));assertNull(comparison(r).get("current"));}
}
