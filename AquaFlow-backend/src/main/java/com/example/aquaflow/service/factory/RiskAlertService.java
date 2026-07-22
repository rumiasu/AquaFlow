package com.example.aquaflow.service.factory;

import com.example.aquaflow.entity.RiskAlert;

import java.util.List;
import java.util.Map;

public interface RiskAlertService {

    /** 预警列表 */
    List<RiskAlert> list(Integer status);

    /** 预警统计 */
    Map<String, Object> stats();

    /** 最近预警 */
    List<RiskAlert> recentAlerts();

    /** 标记已读 */
    void markRead(Integer id);

    /** 处理预警 */
    void handle(Integer id, String handleNote);

    /** 执行风险检测 */
    void check();
}
