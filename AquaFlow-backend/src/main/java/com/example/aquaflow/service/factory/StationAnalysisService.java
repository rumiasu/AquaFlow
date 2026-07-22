package com.example.aquaflow.service.factory;

import java.util.List;
import java.util.Map;

public interface StationAnalysisService {

    /** 销量下降分析 */
    List<Map<String, Object>> salesDecline();

    /** 客户流失分析 */
    List<Map<String, Object>> customerChurn();

    /** 库存压力分析 */
    List<Map<String, Object>> inventoryPressure();

    /** 智能建议 */
    List<Map<String, Object>> suggestions();
}
