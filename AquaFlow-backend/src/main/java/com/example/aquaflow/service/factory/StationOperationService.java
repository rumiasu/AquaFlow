package com.example.aquaflow.service.factory;

import java.util.List;
import java.util.Map;

public interface StationOperationService {

    /** 水厂首页概览 */
    Map<String, Object> overview();

    /** 各水站销量排行 */
    List<Map<String, Object>> stationRanking(String sortBy);

    /** 各水站近7天销量趋势 */
    List<Map<String, Object>> stationTrend();

    /** 各站库存概览 */
    List<Map<String, Object>> inventoryOverview();

    /** 单站运营详情 */
    Map<String, Object> stationDetail(Integer stationId);
}
