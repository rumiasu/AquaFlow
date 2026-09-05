package com.example.aquaflow.service.impl;

import com.example.aquaflow.service.StationExceptionConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点异常处理配置服务实现
 * 简易实现：内存默认配置 + 后续可扩展数据库持久化
 */
@Service
@Slf4j
public class StationExceptionConfigServiceImpl implements StationExceptionConfigService {

    // 默认配置缓存 — 使用ConcurrentHashMap保证线程安全
    private final Map<Long, Map<String, Object>> configCache = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> getConfig(Long stationId) {
        return configCache.computeIfAbsent(stationId, k -> defaultConfig());
    }

    @Override
    public void updateConfig(Long stationId, Map<String, Object> config) {
        Map<String, Object> merged = new HashMap<>(defaultConfig());
        if (config != null) {
            merged.putAll(config);
        }
        configCache.put(stationId, merged);
    }

    private Map<String, Object> defaultConfig() {
        Map<String, Object> config = new HashMap<>();
        // 补偿优先级：水票优先，其次现金，最后减免押金
        config.put("compensationPriority", List.of("REFUND_TICKET", "REFUND_CASH", "WAIVE_DEPOSIT"));
        
        // 自动建议规则
        Map<String, Object> autoRules = new HashMap<>();
        Map<String, Object> shortRule = new HashMap<>();
        shortRule.put("perBarrel", Map.of("ticket", 1, "cash", 0));
        autoRules.put("RETURN_SHORT", shortRule);
        
        Map<String, Object> overRule = new HashMap<>();
        overRule.put("perBarrel", Map.of("ticket", 0, "cash", 1));
        autoRules.put("RETURN_OVER", overRule);
        
        config.put("autoSuggestRules", autoRules);
        
        // 通知模板
        Map<String, String> templates = new HashMap<>();
        templates.put("exception_created", "订单 #{orderId} 发生桶异常：#{category}，差异 #{discrepancy} 桶，请及时处理");
        templates.put("exception_approved", "订单 #{orderId} 异常已处理：#{managerAction}");
        config.put("notifyTemplates", templates);
        
        return config;
    }
}