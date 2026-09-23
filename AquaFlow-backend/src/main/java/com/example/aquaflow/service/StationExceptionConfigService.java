package com.example.aquaflow.service;

import java.util.Map;

/**
 * 站点异常处理配置服务
 */
public interface StationExceptionConfigService {

    /**
     * 获取站点配置
     */
    Map<String, Object> getConfig(Long stationId);

    /**
     * 更新站点配置
     */
    void updateConfig(Long stationId, Map<String, Object> config);
}