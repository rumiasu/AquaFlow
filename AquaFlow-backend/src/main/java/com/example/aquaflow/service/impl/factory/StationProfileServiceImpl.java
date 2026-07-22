package com.example.aquaflow.service.impl.factory;

import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.factory.StationProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StationProfileServiceImpl implements StationProfileService {

    @Autowired
    private StationMapper stationMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private CustomerMapper customerMapper;

    @Override
    public Map<String, Object> profile(Integer stationId) {
        Map<String, Object> data = new HashMap<>();

        Station station = stationMapper.getById(stationId);
        data.put("station", station);

        // 核心指标
        data.put("todayOrders", orderMapper.countTodayByStationId(stationId));
        data.put("todayQty", orderMapper.sumTodayQtyByStationId(stationId));
        data.put("totalOrders", orderMapper.countTotalByStationId(stationId));
        data.put("totalCustomers", customerMapper.countByStationId(stationId));
        data.put("activeCustomers", customerMapper.activeCountLast30Days(stationId));
        data.put("churnedCustomers", customerMapper.churnedCount(stationId));

        // 库存
        data.put("inventory", inventoryMapper.listByStationId(stationId));

        // 近期趋势
        data.put("recentTrend", orderMapper.dailyTrendByStation(stationId));

        return data;
    }

    @Override
    public List<Map<String, Object>> salesTrend(Integer stationId, String period) {
        if ("year".equals(period)) {
            return orderMapper.yearlyTrendByStation(stationId);
        }
        return orderMapper.dailyTrendByStation(stationId);
    }

    @Override
    public Map<String, Object> customerStats(Integer stationId) {
        Map<String, Object> data = new HashMap<>();
        data.put("totalCustomers", customerMapper.countByStationId(stationId));
        data.put("activeCustomers", customerMapper.activeCountLast30Days(stationId));
        data.put("churnedCustomers", customerMapper.churnedCount(stationId));

        // 新增客户（近30天首次下单）
        data.put("newCustomers", orderMapper.countNewCustomersByStationId(stationId));

        return data;
    }

    @Override
    public List<Map<String, Object>> inventoryTurnover(Integer stationId) {
        // 简化版：返回当前库存和近30天销量，计算周转率
        List<Map<String, Object>> result = new ArrayList<>();
        var inventory = inventoryMapper.listByStationId(stationId);

        for (var item : inventory) {
            Map<String, Object> m = new HashMap<>();
            m.put("waterTypeName", item.getWaterTypeName());
            m.put("currentStock", item.getQuantity());
            // 近30天该水类型的销量（简化：用总量/水类型数近似）
            int totalSales = orderMapper.sumTodayQtyByStationId(stationId);
            m.put("avgDailySales", totalSales > 0 ? Math.round((double) totalSales / 30) : 0);
            m.put("turnoverDays", item.getQuantity() > 0 && totalSales > 0 ?
                    Math.round((double) item.getQuantity() / (totalSales / 30.0)) : -1);
            result.add(m);
        }

        return result;
    }

    @Override
    public Map<String, Object> paymentSpeed(Integer stationId) {
        Map<String, Object> data = new HashMap<>();
        // 简化版：返回基础数据
        data.put("totalOrders", orderMapper.countTotalByStationId(stationId));
        data.put("avgCycleDays", 7); // 默认7天，后续可从数据库计算
        return data;
    }
}
