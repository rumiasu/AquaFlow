package com.example.aquaflow.service;

import com.example.aquaflow.dto.DeliveryConfigSaveDTO;
import com.example.aquaflow.entity.StationDeliveryConfig;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StationDeliveryConfigMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StationDeliveryConfigServiceTest {
    private final StationDeliveryConfigMapper mapper = mock(StationDeliveryConfigMapper.class);
    private final StationDeliveryConfigService service = new StationDeliveryConfigService(mapper);

    @Test
    void missingSnapshotCannotBypassConcurrencyGuard() {
        for (String snapshot : new String[] {null, "", "  "}) {
            var error = assertThrows(BusinessException.class, () -> service.save(1L, body(snapshot)));
            assertEquals("请更新员工端小程序至最新版本后再保存配送计费规则", error.getMessage());
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void absentSnapshotCannotOverwriteAConcurrentlyCreatedConfiguration() {
        var body = body(StationDeliveryConfigService.snapshot(1L, null));
        when(mapper.lockStation(1L)).thenReturn(1L);
        when(mapper.getByStationIdForUpdate(1L)).thenReturn(StationDeliveryConfig.defaults(1L));
        var error = assertThrows(BusinessException.class, () -> service.save(1L, body));
        assertTrue(error.getMessage().contains("其他会话"));
        assertTrue(error.getMessage().contains("重新加载"), "有效但过期的快照仍通过刷新恢复");
        assertFalse(error.getMessage().contains("更新员工端"));
        verify(mapper, never()).upsert(any());
    }

    @Test
    void changedSnapshotDoesNotWriteEvenWithinTheSameTimestamp() {
        var original = StationDeliveryConfig.defaults(1L);
        var body = body(StationDeliveryConfigService.snapshot(1L, original));
        original.setBaseDeliveryFee(new BigDecimal("3.00"));
        when(mapper.lockStation(1L)).thenReturn(1L);
        when(mapper.getByStationIdForUpdate(1L)).thenReturn(original);
        assertThrows(BusinessException.class, () -> service.save(1L, body));
        verify(mapper, never()).upsert(any());
    }

    @Test
    void firstSaveLocksParentBeforeReadingAndKeepsNormalizationAndStationScope() {
        var body = body(StationDeliveryConfigService.snapshot(1L, null));
        body.setStationId(999L);
        body.setBaseDeliveryFee(new BigDecimal("-5"));
        body.setMinOrderMode("INVALID");
        var persisted = StationDeliveryConfig.defaults(1L);
        when(mapper.lockStation(1L)).thenReturn(1L);
        when(mapper.getByStationIdForUpdate(1L)).thenReturn(null, persisted);
        var saved = service.save(1L, body);
        var order = inOrder(mapper);
        order.verify(mapper).lockStation(1L);
        order.verify(mapper).getByStationIdForUpdate(1L);
        order.verify(mapper).upsert(argThat(c -> c.getStationId().equals(1L)
                && c.getBaseDeliveryFee().compareTo(BigDecimal.ZERO) == 0
                && c.getMinOrderMode().equals("WARN") && c.getFloorFreeLevel() == 1));
        order.verify(mapper).getByStationIdForUpdate(1L);
        assertTrue(saved.configured());
        assertNotEquals(body.getExpectedSnapshot(), saved.snapshot());
    }

    @Test
    void unchangedConfigurationCanBeSavedAgain() {
        var current = StationDeliveryConfig.defaults(1L);
        when(mapper.lockStation(1L)).thenReturn(1L);
        when(mapper.getByStationIdForUpdate(1L)).thenReturn(current);
        String snapshot = StationDeliveryConfigService.snapshot(1L, current);
        assertEquals(snapshot, service.save(1L, body(snapshot)).snapshot());
        verify(mapper).upsert(any());
    }

    @Test
    void snapshotSeparatesStationPresenceNullZeroAndEveryEditableField() {
        var base = StationDeliveryConfig.defaults(1L);
        String expected = StationDeliveryConfigService.snapshot(1L, base);
        assertNotEquals(expected, StationDeliveryConfigService.snapshot(2L, base));
        assertNotEquals(expected, StationDeliveryConfigService.snapshot(1L, null));
        List<Consumer<StationDeliveryConfig>> changes = List.of(
                c -> c.setMinOrderBuckets(0), c -> c.setMinOrderAmount(BigDecimal.ZERO),
                c -> c.setMinOrderMode("FEE"), c -> c.setMinOrderFee(BigDecimal.ONE),
                c -> c.setDeliveryRadiusM(100), c -> c.setOverRadiusMode("REJECT"),
                c -> c.setRemoteFee(BigDecimal.ONE), c -> c.setBaseDeliveryFee(BigDecimal.ONE),
                c -> c.setFreeDeliveryBuckets(0), c -> c.setFreeDeliveryAmount(BigDecimal.ZERO),
                c -> c.setFloorFreeLevel(2), c -> c.setFloorFeePerLevel(BigDecimal.ONE),
                c -> c.setFloorFeeMode("PER_BUCKET"));
        for (int index = 0; index < changes.size(); index++) {
            var changed = StationDeliveryConfig.defaults(1L);
            changes.get(index).accept(changed);
            assertNotEquals(expected, StationDeliveryConfigService.snapshot(1L, changed), "field " + index);
        }
        base.setBaseDeliveryFee(new BigDecimal("0.00"));
        assertEquals(expected, StationDeliveryConfigService.snapshot(1L, base));
    }

    private DeliveryConfigSaveDTO body(String snapshot) {
        var body = new DeliveryConfigSaveDTO();
        body.setExpectedSnapshot(snapshot);
        return body;
    }
}
