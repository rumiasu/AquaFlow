package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.StationExceptionConfig;
import com.example.aquaflow.mapper.StationExceptionConfigMapper;
import com.example.aquaflow.service.StationExceptionConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点异常处理配置服务实现：读写 {@code station_exception_config}（一站一行）。
 *
 * <p><b>[2026-09-15 修复]</b> 本类此前是纯内存实现（{@code ConcurrentHashMap} 缓存，
 * 类注释还写着"后续可扩展数据库持久化"），而 {@code station_exception_config} 表建了却
 * 在 Java 侧零读写。后果：站长调过的补偿优先级<b>一重启就回到默认值</b>，
 * 多实例部署时更是各实例各持一份互不相同的配置。
 * 现在默认值只在「该站从未配置过」或「列被置空」时生效，其余一律以库中值为准。</p>
 *
 * <p>读路径刻意<b>不写库</b>：一次 GET 不该产生写操作（无谓的行锁占用 + 主从复制流量）。</p>
 */
@Service
@Slf4j
public class StationExceptionConfigServiceImpl implements StationExceptionConfigService {

    /**
     * 允许落库的键。写库前按此白名单过滤——json 列没有 schema 约束，
     * 不过滤的话任意键值都会被安静地存下来，日后没人知道里面到底有什么。
     */
    private static final List<String> CONFIG_KEYS =
            List.of("compensationPriority", "autoSuggestRules", "notifyTemplates");

    /** Jackson 2（与 WeChatLoginService / WeChatNotifyService 同款用法）；配置完成后线程安全 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private StationExceptionConfigMapper mapper;

    @Override
    public Map<String, Object> getConfig(Long stationId) {
        Map<String, Object> config = defaultConfig();
        StationExceptionConfig row = mapper.getByStationId(stationId);
        if (row == null) {
            return config;
        }
        fillFromJson(config, "compensationPriority", row.getCompensationPriority());
        fillFromJson(config, "autoSuggestRules", row.getAutoSuggestRules());
        fillFromJson(config, "notifyTemplates", row.getNotifyTemplates());
        return config;
    }

    @Override
    public void updateConfig(Long stationId, Map<String, Object> config) {
        // 以默认值为底再覆盖：调用方只想改一个键时，另外两个键也必须是完整可用的值，
        // 否则读方拿到 null 会比拿到默认值更容易出错（此处行为与旧的内存实现一致）。
        Map<String, Object> merged = defaultConfig();
        if (config != null) {
            for (String key : CONFIG_KEYS) {
                Object value = config.get(key);
                if (value != null) {
                    merged.put(key, value);
                }
            }
        }

        StationExceptionConfig row = new StationExceptionConfig();
        row.setStationId(stationId);
        row.setCompensationPriority(toJson(merged.get("compensationPriority")));
        row.setAutoSuggestRules(toJson(merged.get("autoSuggestRules")));
        row.setNotifyTemplates(toJson(merged.get("notifyTemplates")));
        mapper.upsert(row);

        log.info("站点异常配置已保存: stationId={}", stationId);
    }

    /**
     * 用库里的 JSON 覆盖单项配置。
     * <p>列值为空或解析失败时<b>保留默认值</b>并记 warn —— 一行脏数据不该让站长的整个配置页 500，
     * 但也不能悄悄吞掉，否则"配置不生效"会变成无法定位的悬案。</p>
     */
    private void fillFromJson(Map<String, Object> config, String key, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            config.put(key, objectMapper.readValue(raw, new TypeReference<Object>() {}));
        } catch (JsonProcessingException e) {
            log.warn("站点异常配置解析失败，已回落到默认值: key={}, raw={}", key, raw, e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 能序列化的 Map/List/String 才会走到这里之外的路径；失败说明调用方传了
            // 无法序列化的对象图（如自引用），属编程错误而非业务拒绝，故不用 BusinessException。
            throw new IllegalStateException("站点异常配置无法序列化为 JSON: " + value, e);
        }
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
