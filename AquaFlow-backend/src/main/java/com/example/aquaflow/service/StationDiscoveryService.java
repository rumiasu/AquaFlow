package com.example.aquaflow.service;

import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.util.GeoUtil;
import com.example.aquaflow.util.StationDiscoveryPolicy;
import com.example.aquaflow.vo.StationDiscoveryVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 客户找新站的独立只读入口；历史服务站、资产站与员工申请绑站不经此服务。 */
@Service
public class StationDiscoveryService {
    private final StationMapper mapper;

    public StationDiscoveryService(StationMapper mapper) { this.mapper = mapper; }

    /** 匿名客户选站，坐标与行政区均只用于发现，不产生绑定或改写订单归属。 */
    public List<StationDiscoveryVO> discover(BigDecimal lat, BigDecimal lng, String province, String city) {
        boolean location = StationDiscoveryPolicy.validCoordinates(lat, lng);
        // 显式传了残缺/越界坐标时不悄悄放宽成城市匹配；无定位则允许完整省市兜底。
        if ((lat != null || lng != null) && !location) return List.of();
        if (!location && !StationDiscoveryPolicy.validRegion(province, city)) return List.of();

        List<LocatedStation> selected = new ArrayList<>();
        for (StationDiscoveryVO station : mapper.listDiscoveryCandidates()) {
            Double meters = location && StationDiscoveryPolicy.validCoordinates(station.getLat(), station.getLng())
                    ? GeoUtil.distanceMeters(lat, lng, station.getLat(), station.getLng()) : null;
            if (meters != null && !Double.isFinite(meters)) meters = null;
            boolean sameCity = StationDiscoveryPolicy.sameCity(station.getAddress(), province, city);
            if (!StationDiscoveryPolicy.visible(station.getDeliveryRadiusM(), meters, sameCity)) continue;
            station.setDistanceKm(meters == null ? null : BigDecimal.valueOf(Math.round(meters / 100d) / 10d));
            selected.add(new LocatedStation(station, meters));
        }
        // 先按未舍入距离排序，再展示一位小数；同城但无法计算距离的站排后，不先截断候选。
        selected.sort(Comparator.comparing(LocatedStation::meters, Comparator.nullsLast(Double::compareTo))
                .thenComparing(row -> row.station().getId()));
        return selected.stream().map(LocatedStation::station).toList();
    }

    private record LocatedStation(StationDiscoveryVO station, Double meters) { }
}
