package com.example.aquaflow.service;

import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.vo.StationDiscoveryVO;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StationDiscoveryServiceTest {
    private final StationMapper mapper = mock(StationMapper.class);
    private final StationDiscoveryService service = new StationDiscoveryService(mapper);

    @Test void absentOrUnusableConditionsNeverReturnNationalListOrEvenReadCandidates() {
        assertTrue(service.discover(null, null, null, null).isEmpty());
        assertTrue(service.discover(null, null, "山东省", null).isEmpty());
        assertTrue(service.discover(null, null, null, "济南市").isEmpty());
        assertTrue(service.discover(null, null, "山东", "济南").isEmpty());
        verifyNoInteractions(mapper);
    }
    @Test void partialAndOutOfRangeCoordinatesCannotSilentlyFallBackToCity() {
        assertTrue(service.discover(bd("36"), null, "山东省", "济南市").isEmpty());
        assertTrue(service.discover(null, bd("117"), "山东省", "济南市").isEmpty());
        assertTrue(service.discover(bd("91"), bd("117"), "山东省", "济南市").isEmpty());
        assertTrue(service.discover(bd("36"), bd("181"), "山东省", "济南市").isEmpty());
        verifyNoInteractions(mapper);
    }
    @Test void configuredRadiusUsesDistanceAndDoesNotFallBackToSameCity() {
        var inside = row(1, 1000, "36.005", "117", "山东省济南市合成一号");
        var outside = row(2, 1000, "36.02", "117", "山东省济南市合成二号");
        var unknown = row(3, 1000, null, null, "山东省济南市合成三号");
        var corrupt = row(4, 1000, "999", "117", "山东省济南市合成四号");
        when(mapper.listDiscoveryCandidates()).thenReturn(List.of(outside, unknown, inside, corrupt));
        assertEquals(List.of(1L), ids(service.discover(bd("36"), bd("117"), "山东省", "济南市")));
        verify(mapper).listDiscoveryCandidates();
        verifyNoMoreInteractions(mapper);
    }
    @Test void noConfiguredRadiusUsesNearbyOrSameCityWithoutCrossProvinceLeak() {
        when(mapper.listDiscoveryCandidates()).thenReturn(List.of(
                row(1, null, "36.01", "117", "其他地址"),
                row(2, 0, "37", "117", "山东省济南市合成二号"),
                row(3, null, null, null, "山东省济南市合成三号"),
                row(4, 0, "37", "117", "河北省济南市合成四号"),
                row(5, null, null, null, "济南市缺省地址")));
        assertEquals(List.of(1L, 2L, 3L), ids(service.discover(bd("36"), bd("117"), "山东省", "济南市")));
    }
    @Test void cityOnlyKeepsUnrestrictedStationsButNotConfiguredRadius() {
        when(mapper.listDiscoveryCandidates()).thenReturn(List.of(
                row(1, null, null, null, "山东省济南市合成一号"),
                row(2, 0, "36", "117", "山东省济南市合成二号"),
                row(3, 1000, "36", "117", "山东省济南市合成三号"),
                row(4, null, null, null, "山东省青岛市合成四号")));
        var result = service.discover(null, null, "山东省", "济南市");
        assertEquals(List.of(1L, 2L), ids(result));
        assertTrue(result.stream().allMatch(s -> s.getDistanceKm() == null));
    }
    @Test void coordinateOnlyDoesNotTreatUnknownStationLocationAsZero() {
        when(mapper.listDiscoveryCandidates()).thenReturn(List.of(
                row(1, null, "0", "0", null), row(2, null, null, null, null),
                row(3, 0, "91", "0", null), row(4, 0, "0", "1", null)));
        var result = service.discover(BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        assertEquals(List.of(1L), ids(result));
        assertEquals(0, result.get(0).getDistanceKm().compareTo(BigDecimal.ZERO));
    }
    @Test void sortUsesUnroundedDistanceAndDoesNotTruncateBeforeFiltering() {
        var candidates = new java.util.ArrayList<StationDiscoveryVO>();
        for (long id = 1; id <= 60; id++) candidates.add(row(id, 1, "40", "117", null));
        candidates.add(row(61, null, "36.0002", "117", null));
        candidates.add(row(62, null, "36.0001", "117", null));
        when(mapper.listDiscoveryCandidates()).thenReturn(candidates);
        assertEquals(List.of(62L, 61L), ids(service.discover(bd("36"), bd("117"), null, null)));
    }
    @Test void discoverySqlIsOneLeftJoinAndKeepsExistingPublicStatusGuard() {
        var config = new org.apache.ibatis.session.Configuration();
        config.addMapper(StationMapper.class);
        String sql = config.getMappedStatement(StationMapper.class.getName() + ".listDiscoveryCandidates")
                .getBoundSql(null).getSql().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("left join station_delivery_config c on c.station_id = s.id"));
        assertTrue(sql.contains("s.status = 1"));
        assertTrue(sql.contains("s.operating_status is null or s.operating_status <> 3"));
        assertFalse(sql.contains(" limit "));
        assertFalse(sql.contains("over_radius_mode"), "发现范围不随下单 WARN/FEE 模式放宽");
    }
    private List<Long> ids(List<StationDiscoveryVO> stations) { return stations.stream().map(StationDiscoveryVO::getId).toList(); }
    private static BigDecimal bd(String value) { return value == null ? null : new BigDecimal(value); }
    private StationDiscoveryVO row(long id, Integer radius, String lat, String lng, String address) {
        var row = new StationDiscoveryVO();
        row.setId(id); row.setName("合成站" + id); row.setStatus(1); row.setAddress(address);
        row.setDeliveryRadiusM(radius); row.setLat(bd(lat)); row.setLng(bd(lng));
        return row;
    }
}
