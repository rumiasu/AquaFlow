package com.example.aquaflow.service;

import com.example.aquaflow.controller.CustomerAssetStationController;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerAssetStationMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.CustomerAssetStationVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerAssetStationServiceTest {
    final CustomerAssetStationMapper mapper = mock(CustomerAssetStationMapper.class);
    final CustomerAssetStationService service = new CustomerAssetStationService(mapper);

    @BeforeEach void customer() {
        AuthContext.set(new AuthContext.AuthUser(7L, "customer", null, null));
        when(mapper.countCustomer(7L)).thenReturn(1);
    }
    @AfterEach void clear() { AuthContext.clear(); }

    CustomerAssetStationVO station(long id, int status) {
        var row = new CustomerAssetStationVO(); row.setId(id); row.setName("站" + id); row.setStatus(status); return row;
    }

    @Test void cursorUsesExtraRowAndNeverDropsStoppedStations() {
        when(mapper.listOwned(7L, 0L, 3)).thenReturn(List.of(station(11,1), station(22,2), station(33,1)));
        var first = service.list(7L, 0L, 2);
        assertEquals(List.of(11L,22L), first.stations().stream().map(CustomerAssetStationVO::getId).toList());
        assertTrue(first.hasMore()); assertEquals(22L, first.nextStationId());
        assertEquals("停业 · 可查看历史资产", first.stations().get(1).getStatusText());
        when(mapper.listOwned(7L, 22L, 3)).thenReturn(List.of(station(33,1)));
        var last = service.list(7L, 22L, 2);
        assertFalse(last.hasMore()); assertNull(last.nextStationId()); assertEquals(33L, last.stations().get(0).getId());
    }

    @Test void emptyListMeansNoRelationshipAndHasNoInventedCursor() {
        when(mapper.listOwned(7L, 0L, 21)).thenReturn(List.of());
        var result = service.list(7L, 0L, 20);
        assertTrue(result.stations().isEmpty()); assertFalse(result.hasMore()); assertNull(result.nextStationId());
    }

    @Test void invalidCursorAndLimitsNeverReachListQuery() {
        assertThrows(BusinessException.class, () -> service.list(7L, -1L, 20));
        assertThrows(BusinessException.class, () -> service.list(7L, null, 20));
        assertThrows(BusinessException.class, () -> service.list(7L, 0L, 0));
        assertThrows(BusinessException.class, () -> service.list(7L, 0L, 51));
        verify(mapper, never()).listOwned(any(), any(), anyInt());
    }

    @Test void missingCustomerCannotEnumerateStations() {
        assertThrows(BusinessException.class, () -> service.list(88L, 0L, 20));
        assertThrows(BusinessException.class, () -> service.get(88L, 11L));
        verify(mapper, never()).listOwned(any(), any(), anyInt());
        verify(mapper, never()).getOwned(any(), any());
    }

    @Test void unrelatedStationDetailIsRejectedInsteadOfReturningPublicMetadata() {
        assertThrows(BusinessException.class, () -> service.get(7L, 22L));
        when(mapper.getOwned(7L,11L)).thenReturn(station(11,2));
        assertEquals(11L, service.get(7L,11L).getId());
        assertThrows(BusinessException.class, () -> service.get(7L,0L));
    }

    @Test void controllerAlwaysUsesJwtCustomerAndRejectsStaffOrAnonymous() {
        var controller = new CustomerAssetStationController(service);
        when(mapper.listOwned(7L,0L,21)).thenReturn(List.of());
        controller.list(0L,20); verify(mapper).listOwned(7L,0L,21);
        clear(); assertThrows(BusinessException.class, () -> controller.list(0L,20));
        AuthContext.set(new AuthContext.AuthUser(7L,"staff","STATION_MANAGER",11L));
        assertThrows(BusinessException.class, () -> controller.list(0L,20));
        assertThrows(BusinessException.class, () -> controller.get(11L));
        verify(mapper, times(1)).listOwned(any(), any(), anyInt());
    }
}
