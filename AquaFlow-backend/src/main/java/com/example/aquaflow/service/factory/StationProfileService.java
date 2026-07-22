package com.example.aquaflow.service.factory;

import java.util.List;
import java.util.Map;

public interface StationProfileService {

    /** 水站画像总览 */
    Map<String, Object> profile(Integer stationId);

    /** 销量趋势 */
    List<Map<String, Object>> salesTrend(Integer stationId, String period);

    /** 客户统计 */
    Map<String, Object> customerStats(Integer stationId);

    /** 库存周转分析 */
    List<Map<String, Object>> inventoryTurnover(Integer stationId);

    /** 回款速度分析 */
    Map<String, Object> paymentSpeed(Integer stationId);
}
