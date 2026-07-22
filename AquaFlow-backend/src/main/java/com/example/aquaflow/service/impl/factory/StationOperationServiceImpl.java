package com.example.aquaflow.service.impl.factory;

import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.mapper.factory.RiskAlertMapper;
import com.example.aquaflow.service.StationService;
import com.example.aquaflow.service.factory.StationOperationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StationOperationServiceImpl implements StationOperationService {

    @Autowired
    private StationMapper stationMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private RiskAlertMapper riskAlertMapper;

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> data = new HashMap<>();

        List<Station> stations = stationMapper.listAll();
        data.put("stationCount", stations.size());

        // 今日总订单
        List<Map<String, Object>> todayOrders = orderMapper.countTodayByStation();
        int totalToday = todayOrders.stream()
                .mapToInt(m -> m.get("count") != null ? ((Number) m.get("count")).intValue() : 0)
                .sum();
        data.put("todayOrders", totalToday);

        // 今日总销量
        List<Map<String, Object>> todayQty = orderMapper.sumTodayQtyByStation();
        int totalQty = todayQty.stream()
                .mapToInt(m -> m.get("totalQuantity") != null ? ((Number) m.get("totalQuantity")).intValue() : 0)
                .sum();
        data.put("todayRevenue", totalQty);

        // 低库存水站数
        int lowStockStations = inventoryMapper.countLowStockByStation();
        data.put("lowStockStations", lowStockStations);

        // 客户总数
        data.put("totalCustomers", customerMapper.countAll());

        // 风险预警数
        List<Map<String, Object>> alertStats = riskAlertMapper.countByLevel();
        int totalAlerts = alertStats.stream()
                .mapToInt(m -> m.get("count") != null ? ((Number) m.get("count")).intValue() : 0)
                .sum();
        data.put("riskAlerts", totalAlerts);

        return data;
    }

    @Override
    public List<Map<String, Object>> stationRanking(String sortBy) {
        List<Map<String, Object>> ranking = orderMapper.salesRankingByStation();

        // 补充水站名称
        List<Station> stations = stationMapper.listAll();
        Map<Integer, String> stationMap = stations.stream()
                .collect(Collectors.toMap(Station::getId, Station::getName));

        ranking.forEach(m -> {
            Object sid = m.get("stationId");
            if (sid != null) {
                m.put("stationName", stationMap.getOrDefault(((Number) sid).intValue(), "未知水站"));
            }
        });

        return ranking;
    }

    @Override
    public List<Map<String, Object>> stationTrend() {
        List<Map<String, Object>> trend = orderMapper.trendLast7DaysByStation();
        // 补充水站名称
        List<Station> stations = stationMapper.listAll();
        Map<Integer, String> stationMap = stations.stream()
                .collect(Collectors.toMap(Station::getId, Station::getName));
        trend.forEach(m -> {
            Object sid = m.get("stationId");
            if (sid != null) {
                m.put("stationName", stationMap.getOrDefault(((Number) sid).intValue(), "未知水站"));
            }
        });
        return trend;
    }

    @Override
    public List<Map<String, Object>> inventoryOverview() {
        return inventoryMapper.listWithStation().stream()
                .map(i -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("stationId", i.getStationId());
                    m.put("stationName", i.getStationName());
                    m.put("waterTypeName", i.getWaterTypeName());
                    m.put("quantity", i.getQuantity());
                    return m;
                })
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> stationDetail(Integer stationId) {
        Map<String, Object> data = new HashMap<>();

        Station station = stationMapper.getById(stationId);
        data.put("station", station);

        // 今日订单
        data.put("todayOrders", orderMapper.countTodayByStationId(stationId));
        data.put("todayQty", orderMapper.sumTodayQtyByStationId(stationId));
        data.put("totalOrders", orderMapper.countTotalByStationId(stationId));
        data.put("totalCustomers", customerMapper.countByStationId(stationId));
        data.put("activeCustomers", customerMapper.activeCountLast30Days(stationId));
        data.put("churnedCustomers", customerMapper.churnedCount(stationId));

        // 库存
        data.put("inventory", inventoryMapper.listByStationId(stationId));

        return data;
    }
}
