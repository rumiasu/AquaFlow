package com.example.aquaflow.service.impl.factory;

import com.example.aquaflow.entity.RiskAlert;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.mapper.factory.RiskAlertMapper;
import com.example.aquaflow.service.factory.RiskAlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class RiskAlertServiceImpl implements RiskAlertService {

    @Autowired
    private RiskAlertMapper alertMapper;
    @Autowired
    private StationMapper stationMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private CustomerMapper customerMapper;

    @Override
    public List<RiskAlert> list(Integer status) {
        if (status != null) {
            return alertMapper.listByStatus(status);
        }
        return alertMapper.listAll();
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> levelStats = alertMapper.countByLevel();
        int totalUnread = 0;
        for (Map<String, Object> s : levelStats) {
            int level = ((Number) s.get("alertLevel")).intValue();
            int count = ((Number) s.get("count")).intValue();
            if (level == 1) data.put("info", count);
            else if (level == 2) data.put("warning", count);
            else if (level == 3) data.put("critical", count);
            totalUnread += count;
        }
        data.put("total", totalUnread);
        return data;
    }

    @Override
    public List<RiskAlert> recentAlerts() {
        return alertMapper.recentAlerts();
    }

    @Override
    public void markRead(Integer id) {
        alertMapper.markRead(id);
    }

    @Override
    public void handle(Integer id, String handleNote) {
        alertMapper.handle(id, handleNote);
    }

    @Override
    @Scheduled(cron = "0 0 8 * * ?") // 每天早上8点执行
    public void check() {
        List<Station> stations = stationMapper.listAll();

        for (Station station : stations) {
            checkOrderDecline(station);
            checkInventoryBacklog(station);
            checkCustomerLoss(station);
            checkLowStock(station);
        }
    }

    private void checkOrderDecline(Station station) {
        int currentWeek = orderMapper.countInPeriod(station.getId(), 7, 0);
        int prevWeek = orderMapper.countInPeriod(station.getId(), 14, 7);

        if (prevWeek == 0) return;

        double declineRate = (double)(prevWeek - currentWeek) / prevWeek * 100;

        if (declineRate > 20) {
            int level = declineRate > 40 ? 3 : declineRate > 30 ? 2 : 1;
            // 检查是否已有相同预警
            int existing = alertMapper.countRecentByType(station.getId(), "ORDER_DECLINE", 7);
            if (existing == 0) {
                RiskAlert alert = new RiskAlert();
                alert.setStationId(station.getId());
                alert.setAlertType("ORDER_DECLINE");
                alert.setAlertLevel(level);
                alert.setTitle("订单量下降" + Math.round(declineRate) + "%");
                alert.setContent("近7天订单" + currentWeek + "单，上一周" + prevWeek + "单，下降" + Math.round(declineRate) + "%");
                alert.setSuggestion(level >= 2 ? "建议立即联系站长了解情况" : "持续关注");
                alert.setStatus(1);
                alert.setCreateTime(LocalDateTime.now());
                alert.setUpdateTime(LocalDateTime.now());
                alertMapper.insert(alert);
            }
        }
    }

    private void checkInventoryBacklog(Station station) {
        var inventory = inventoryMapper.listByStationId(station.getId());
        int totalStock = inventory.stream().mapToInt(i -> i.getQuantity()).sum();
        int recentSales = orderMapper.sumTodayQtyByStationId(station.getId()) * 30;

        if (recentSales == 0 && totalStock > 50) {
            int existing = alertMapper.countRecentByType(station.getId(), "INVENTORY_BACKLOG", 14);
            if (existing == 0) {
                RiskAlert alert = new RiskAlert();
                alert.setStationId(station.getId());
                alert.setAlertType("INVENTORY_BACKLOG");
                alert.setAlertLevel(3);
                alert.setTitle("库存积压预警");
                alert.setContent("库存" + totalStock + "件，近30天无销售记录");
                alert.setSuggestion("建议核查库存，可能需要促销或调拨");
                alert.setStatus(1);
                alert.setCreateTime(LocalDateTime.now());
                alert.setUpdateTime(LocalDateTime.now());
                alertMapper.insert(alert);
            }
        }
    }

    private void checkCustomerLoss(Station station) {
        int total = customerMapper.countByStationId(station.getId());
        int churned = customerMapper.churnedCount(station.getId());

        if (total > 0 && churned > 0) {
            double churnRate = (double) churned / total * 100;
            if (churnRate > 15) {
                int level = churnRate > 30 ? 3 : churnRate > 20 ? 2 : 1;
                int existing = alertMapper.countRecentByType(station.getId(), "CUSTOMER_LOSS", 14);
                if (existing == 0) {
                    RiskAlert alert = new RiskAlert();
                    alert.setStationId(station.getId());
                    alert.setAlertType("CUSTOMER_LOSS");
                    alert.setAlertLevel(level);
                    alert.setTitle("客户流失率" + Math.round(churnRate) + "%");
                    alert.setContent("总客户" + total + "人，流失" + churned + "人");
                    alert.setSuggestion("建议回访流失客户，了解流失原因");
                    alert.setStatus(1);
                    alert.setCreateTime(LocalDateTime.now());
                    alert.setUpdateTime(LocalDateTime.now());
                    alertMapper.insert(alert);
                }
            }
        }
    }

    private void checkLowStock(Station station) {
        var inventory = inventoryMapper.listByStationId(station.getId());
        long lowCount = inventory.stream().filter(i -> i.getQuantity() < 10).count();

        if (lowCount > 0) {
            int existing = alertMapper.countRecentByType(station.getId(), "LOW_STOCK", 3);
            if (existing == 0) {
                RiskAlert alert = new RiskAlert();
                alert.setStationId(station.getId());
                alert.setAlertType("LOW_STOCK");
                alert.setAlertLevel(2);
                alert.setTitle(lowCount + "种水类型库存不足");
                alert.setContent("库存低于10件的水类型有" + lowCount + "种");
                alert.setSuggestion("建议及时补货");
                alert.setStatus(1);
                alert.setCreateTime(LocalDateTime.now());
                alert.setUpdateTime(LocalDateTime.now());
                alertMapper.insert(alert);
            }
        }
    }
}
