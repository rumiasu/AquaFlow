package com.example.aquaflow.util;

import com.example.aquaflow.entity.Orders;

/**
 * 订单履约站/归属站语义工具。
 * orders.station_id = 订单归属站
 * orders.delivery_station_id = 实际履约站
 */
public class StationUtil {

    /**
     * 订单履约站：优先 delivery_station_id，回退 station_id (订单归属站)。
     * 用于库存扣减、配送判站、站端列表过滤等一切"谁履约"的判断。
     */
    public static Long deliveryStation(Orders o) {
        if (o == null) return null;
        return o.getDeliveryStationId() != null ? o.getDeliveryStationId() : o.getStationId();
    }

    /**
     * 订单归属站：直接返回 station_id (订单归属站)。
     * 用于"订单属于哪站"的判断（账务统计、站端列表过滤等）。
     */
    public static Long station(Orders o) {
        if (o == null) return null;
        return o.getStationId();
    }
}