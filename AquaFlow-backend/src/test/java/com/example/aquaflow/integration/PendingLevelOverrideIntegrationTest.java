package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「待办汇总」的**级别可运行时覆盖** —— {@code aquaflow.pending.level-overrides}。
 *
 * <p>2026-09-19 新增。产品要求"分级归属尽量别写死"，实现方式两层：条目目录 {@code constant/PendingItem}
 * 写默认级别，配置可覆盖。本类专门证明**第二层真的生效** —— 否则那个配置键就是又一个"看起来能配、
 * 实际没人读"的假开关（本仓刚清理过一个：设置页的「新订单提醒」）。</p>
 *
 * <p>单独的类而不是往 {@code ManagerPendingSummaryIntegrationTest} 里加：级别覆盖是**启动期**属性，
 * 只影响本类的应用上下文，混进主用例类会污染其它用例的默认级别。</p>
 */
@TestPropertySource(properties = {
        // 把"转单请求"从 P0 降级到 P2：这类改动产品可能随时提，不该需要改代码 + 发版。
        // 格式是「key:LEVEL,key:LEVEL」—— 见 ManagerPendingSummaryController 里
        // levelOverridesRaw 的注释（试过 Map 注入，实测静默不生效）
        "aquaflow.pending.level-overrides=pendingTransfer:P2,barrelReturn:NOT_A_LEVEL,noSuchKey:P0"
})
class PendingLevelOverrideIntegrationTest extends AbstractIntegrationTest {

    private static com.fasterxml.jackson.databind.JsonNode itemOf(Api res, String key) {
        for (com.fasterxml.jackson.databind.JsonNode it : res.data().path("items")) {
            if (key.equals(it.path("key").asText())) {
                return it;
            }
        }
        return null;
    }

    @Test
    @DisplayName("级别可被配置覆盖：合法值生效、非法值与未知 key 静默退回默认")
    void levelOverrides() {
        long station = createStation("覆盖测试站");
        long manager = createStaff("覆盖测试站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api res = get("/api/manager/pending-summary", mgr);
        assertEquals(0, res.code(), "配置了覆盖值也必须能正常返回: " + res);

        // ① 合法覆盖生效
        com.fasterxml.jackson.databind.JsonNode transfer = itemOf(res, "pendingTransfer");
        assertNotNull(transfer, "必须仍下发 pendingTransfer（覆盖级别不改条目集合）");
        assertEquals("P2", transfer.path("level").asText(),
                "配置把 pendingTransfer 覆盖成 P2，响应必须跟着变 —— 否则这个配置键是假的");

        // ② 非法值退回默认（barrelReturn 默认 P0）
        com.fasterxml.jackson.databind.JsonNode barrel = itemOf(res, "barrelReturn");
        assertNotNull(barrel);
        assertEquals("P0", barrel.path("level").asText(),
                "非法级别值必须静默退回默认，不能把待办卡打成空白、也不能抛错");

        // ③ 未知 key 被忽略且不影响其它条目
        assertTrue(res.data().path("items").size() >= 15,
                "未知 key 不该减少条目数（目录有 15 条）");

        // ④ 级别覆盖不能把"条目集合"改掉 —— 它只改级别
        for (com.fasterxml.jackson.databind.JsonNode it : res.data().path("items")) {
            assertNotNull(it.path("label").asText());
            assertTrue(it.path("count").isInt(), "count 必须是数字: " + it.path("key").asText());
        }
    }
}
