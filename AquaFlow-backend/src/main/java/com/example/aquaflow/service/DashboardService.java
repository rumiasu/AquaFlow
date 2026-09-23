package com.example.aquaflow.service;

import java.util.Map;

public interface DashboardService {

    /**
     * 站长数据看板报表。
     *
     * @param stationId 登录站长所属水站（唯一数据边界）
     * @param range     today / 7d / 30d
     * @return 报表数据，含当前周期、上一周期（环比）、趋势与多维度分布
     */
    Map<String, Object> report(Long stationId, String range);
}
