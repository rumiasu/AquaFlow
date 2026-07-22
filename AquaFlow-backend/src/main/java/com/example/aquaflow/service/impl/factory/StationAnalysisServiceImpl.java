package com.example.aquaflow.service.impl.factory;

import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.factory.StationAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StationAnalysisServiceImpl implements StationAnalysisService {

    @Autowired
    private StationMapper stationMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private CustomerMapper customerMapper;

    @Override
    public List<Map<String, Object>> salesDecline() {
        List<Station> stations = stationMapper.listAll();
        Map<Integer, String> stationMap = stations.stream()
                .collect(Collectors.toMap(Station::getId, Station::getName));

        List<Map<String, Object>> result = new ArrayList<>();

        for (Station station : stations) {
            int currentWeek = orderMapper.countInPeriod(station.getId(), 7, 0);
            int prevWeek = orderMapper.countInPeriod(station.getId(), 14, 7);

            if (prevWeek == 0) continue;

            double declineRate = (double)(prevWeek - currentWeek) / prevWeek * 100;
            if (declineRate > 5) {
                Map<String, Object> item = new HashMap<>();
                item.put("stationId", station.getId());
                item.put("stationName", station.getName());
                item.put("currentWeek", currentWeek);
                item.put("prevWeek", prevWeek);
                item.put("declineRate", Math.round(declineRate * 10.0) / 10.0);

                // 连续下降天数
                int consecutiveDays = 0;
                for (int i = 0; i < 30; i++) {
                    int today = orderMapper.countInPeriod(station.getId(), i + 1, i);
                    int yesterday = orderMapper.countInPeriod(station.getId(), i + 2, i + 1);
                    if (yesterday > 0 && today < yesterday) {
                        consecutiveDays++;
                    } else {
                        break;
                    }
                }
                item.put("consecutiveDays", consecutiveDays);

                // 原因分析
                List<String> reasons = new ArrayList<>();
                if (consecutiveDays >= 7) reasons.add("连续" + consecutiveDays + "天下降");
                if (declineRate > 30) reasons.add("降幅超过30%");
                int churned = customerMapper.churnedCount(station.getId());
                if (churned > 0) reasons.add(churned + "位客户流失");
                item.put("reasons", reasons.isEmpty() ? Arrays.asList("需进一步分析") : reasons);

                // 建议
                String suggestion = declineRate > 30 ? "建议立即联系站长了解情况" :
                        consecutiveDays >= 7 ? "建议关注客户流失情况" : "持续观察";
                item.put("suggestion", suggestion);

                result.add(item);
            }
        }

        result.sort((a, b) -> Double.compare((Double) b.get("declineRate"), (Double) a.get("declineRate")));
        return result;
    }

    @Override
    public List<Map<String, Object>> customerChurn() {
        List<Station> stations = stationMapper.listAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Station station : stations) {
            int total = customerMapper.countByStationId(station.getId());
            int active = customerMapper.activeCountLast30Days(station.getId());
            int churned = customerMapper.churnedCount(station.getId());

            if (total == 0) continue;

            Map<String, Object> item = new HashMap<>();
            item.put("stationId", station.getId());
            item.put("stationName", station.getName());
            item.put("totalCustomers", total);
            item.put("activeCustomers", active);
            item.put("churnedCustomers", churned);
            item.put("churnRate", Math.round((double) churned / total * 1000.0) / 10.0);
            result.add(item);
        }

        result.sort((a, b) -> Double.compare((Double) b.get("churnRate"), (Double) a.get("churnRate")));
        return result;
    }

    @Override
    public List<Map<String, Object>> inventoryPressure() {
        List<Map<String, Object>> stationInventory = inventoryMapper.sumQuantityByStation();
        List<Map<String, Object>> stationSales = orderMapper.salesRankingByStation();

        Map<Integer, Long> salesMap = stationSales.stream()
                .collect(Collectors.toMap(
                        m -> ((Number) m.get("stationId")).intValue(),
                        m -> ((Number) m.get("totalQuantity")).longValue()
                ));

        List<Station> stations = stationMapper.listAll();
        Map<Integer, String> stationMap = stations.stream()
                .collect(Collectors.toMap(Station::getId, Station::getName));

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> inv : stationInventory) {
            int stationId = ((Number) inv.get("stationId")).intValue();
            long totalQty = ((Number) inv.get("totalQuantity")).longValue();
            long sales = salesMap.getOrDefault(stationId, 0L);

            Map<String, Object> item = new HashMap<>();
            item.put("stationId", stationId);
            item.put("stationName", stationMap.getOrDefault(stationId, "未知"));
            item.put("totalInventory", totalQty);
            item.put("totalSales", sales);
            item.put("turnoverRate", sales > 0 ? Math.round((double) totalQty / sales * 10.0) / 10.0 : -1);

            // 判断压力等级
            String pressure = "正常";
            if (sales == 0 && totalQty > 0) {
                pressure = "积压";
            } else if (totalQty > sales * 3) {
                pressure = "偏高";
            }
            item.put("pressure", pressure);

            result.add(item);
        }

        return result;
    }

    @Override
    public List<Map<String, Object>> suggestions() {
        List<Map<String, Object>> result = new ArrayList<>();

        // 销量下降的水站
        List<Map<String, Object>> declines = salesDecline();
        for (Map<String, Object> d : declines) {
            Map<String, Object> s = new HashMap<>();
            s.put("type", "ORDER_DECLINE");
            s.put("stationName", d.get("stationName"));
            s.put("message", "订单量下降" + d.get("declineRate") + "%，" + d.get("suggestion"));
            s.put("priority", (Double) d.get("declineRate") > 30 ? "high" : "medium");
            result.add(s);
        }

        // 库存积压的水站
        List<Map<String, Object>> pressures = inventoryPressure();
        for (Map<String, Object> p : pressures) {
            if ("积压".equals(p.get("pressure"))) {
                Map<String, Object> s = new HashMap<>();
                s.put("type", "INVENTORY_BACKLOG");
                s.put("stationName", p.get("stationName"));
                s.put("message", "库存积压，有进无出，建议核查");
                s.put("priority", "high");
                result.add(s);
            }
        }

        // 客户流失的水站
        List<Map<String, Object>> churns = customerChurn();
        for (Map<String, Object> c : churns) {
            if ((Double) c.get("churnRate") > 10) {
                Map<String, Object> s = new HashMap<>();
                s.put("type", "CUSTOMER_LOSS");
                s.put("stationName", c.get("stationName"));
                s.put("message", "客户流失率" + c.get("churnRate") + "%，建议排查原因");
                s.put("priority", "high");
                result.add(s);
            }
        }

        // 按优先级排序
        result.sort((a, b) -> {
            String pa = (String) a.get("priority");
            String pb = (String) b.get("priority");
            if ("high".equals(pa) && !"high".equals(pb)) return -1;
            if (!"high".equals(pa) && "high".equals(pb)) return 1;
            return 0;
        });

        return result;
    }
}
