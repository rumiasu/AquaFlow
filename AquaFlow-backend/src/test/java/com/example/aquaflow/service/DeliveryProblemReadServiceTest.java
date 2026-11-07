package com.example.aquaflow.service;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.controller.DeliveryProblemReadController;
import com.example.aquaflow.entity.OrderBarrelException;
import com.example.aquaflow.mapper.OrderBarrelExceptionMapper;
import com.example.aquaflow.util.AuthContext;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryProblemReadServiceTest {
    @AfterEach void clear() { AuthContext.clear(); }
    @Test void projectsOnlyOwnLimitedFieldsAndUsesSessionIdentity() {
        var mapper = mock(OrderBarrelExceptionMapper.class);
        var row = new OrderBarrelException();
        row.setId(19L); row.setOrderId(22L); row.setStaffNote("客户不接电话");
        row.setStatus("VERIFIED"); row.setManagerNote("不应暴露"); row.setCustomerId(99L);
        when(mapper.listMyDeliveryProblems(7L, 2L)).thenReturn(List.of(row));
        AuthContext.set(new AuthContext.AuthUser(7L, "staff", "DELIVERY", 2L));
        var result = new DeliveryProblemReadService(mapper).listMine();
        assertEquals(1, result.size()); assertEquals("核实结案（无需补偿）", result.get(0).statusText());
        assertEquals("客户不接电话", result.get(0).staffNote());
        assertEquals(List.of("id", "orderId", "staffNote", "statusText", "createdAt", "decidedAt", "executedAt"),
                java.util.Arrays.stream(DeliveryProblemReadService.Receipt.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        verify(mapper).listMyDeliveryProblems(7L, 2L); verifyNoMoreInteractions(mapper);
    }
    @Test void refusesMissingStationAndNonStaff() {
        var mapper = mock(OrderBarrelExceptionMapper.class); var service = new DeliveryProblemReadService(mapper);
        AuthContext.set(new AuthContext.AuthUser(7L, "staff", "DELIVERY", null));
        assertThrows(RuntimeException.class, service::listMine);
        AuthContext.set(new AuthContext.AuthUser(7L, "customer", "DELIVERY", 2L));
        assertThrows(RuntimeException.class, service::listMine); verifyNoInteractions(mapper);
    }
    @Test void sqlAndControllerRetainBoundaries() throws Exception {
        var sql = String.join(" ", OrderBarrelExceptionMapper.class.getMethod("listMyDeliveryProblems", Long.class, Long.class).getAnnotation(Select.class).value());
        assertTrue(sql.contains("e.type='DELIVERY_PROBLEM'"));
        assertTrue(sql.contains("e.delivery_staff_id=#{staffId}"));
        assertTrue(sql.contains("e.station_id=#{stationId}"));
        assertTrue(sql.contains("coalesce(o.delivery_station_id,o.station_id)=#{stationId}"));
        assertFalse(sql.contains("manager_note")); assertFalse(sql.contains("customer_id"));
        var roles = DeliveryProblemReadController.class.getMethod("listMine").getAnnotation(RequireRole.class).value();
        assertEquals(java.util.Set.of("DELIVERY", "STATION_MANAGER"), java.util.Set.of(roles));
    }
}
