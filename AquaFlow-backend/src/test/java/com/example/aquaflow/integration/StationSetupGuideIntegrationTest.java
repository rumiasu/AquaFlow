package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长「信息完善引导」（2026-09-19）：产品要求「所有需要/建议填写的字段，都加一层引导」。
 *
 * <p>规则目录的唯一实现在 {@code StationSetupGuideService}，本类逐项验证它的判定与文案：
 * ① 新站的必填项（坐标 / 商品 / 配送费）都算"未完善"，并给出**能感知的真实后果**；
 * ② 补一项就少一项（避免"提示长亮、站长当背景噪音"）；
 * ③ 有员工但没配计件单价 → 工资结构未完善（真实库正是这个状态：1 名在职配送员 + 0 行计件单价）；
 * ④ 开了水票却没档位 → 水票档位未完善（真实库也正是这个状态）；
 * ⑤ 电话必填在建站那一刻就挡住（另一批用例 {@code StationCreationPhoneIntegrationTest} 钉接口）。</p>
 */
@DisplayName("站长信息完善引导：规则目录逐项判定 + 文案下发给前端")
class StationSetupGuideIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long manager;
    private String mgrToken;

    private void seed() {
        station = createStation("引导目录站");
        manager = createStaff("引导目录站长", "STATION_MANAGER", station, 1);
        mgrToken = staffToken(manager, "STATION_MANAGER", station);
        // 电话是建站必填项之一（2026-09-19 起），夹具直接落库所以要自己给上
        jdbc.update("UPDATE station SET phone=? WHERE id=?", "0531-12345678", station);
    }

    private JsonNode guide() {
        Api res = get("/api/manager/setup-guide", mgrToken);
        assertEquals(0, res.code(), "读完善度应成功: " + res);
        return res.data();
    }

    /** 取某一项的 done；key 不存在直接失败（key 名是前后端契约）。 */
    private boolean done(JsonNode guide, String key) {
        for (JsonNode it : guide.path("items")) {
            if (key.equals(it.path("key").asText())) {
                return it.path("done").asBoolean();
            }
        }
        throw new AssertionError("完善度清单里没有这一项: " + key + "，实际=" + guide.path("items"));
    }

    private String why(JsonNode guide, String key) {
        for (JsonNode it : guide.path("items")) {
            if (key.equals(it.path("key").asText())) {
                return it.path("why").asText("");
            }
        }
        return "";
    }

    @Test
    @DisplayName("新站：坐标/商品/配送费三项 P0 未完善，且每项都给出能感知的后果与去处")
    void freshStationListsP0GapsWithReasons() {
        seed();
        JsonNode g = guide();

        assertEquals(3, g.path("p0PendingCount").asInt(),
                "新站应有 3 项 P0 未完善（坐标 / 上架商品 / 配送计费），实际清单=" + g.path("items"));
        assertTrue(g.path("summaryText").asText("").contains("3 项必填"),
                "汇总文案由后端给，实际=" + g.path("summaryText"));

        assertTrue(done(g, "stationPhone"), "夹具已填电话 → 该项应算完成");
        assertFalse(done(g, "stationLocation"), "没坐标 → 未完善");
        assertTrue(why(g, "stationLocation").contains("配送距离") || why(g, "stationLocation").contains("配送范围"),
                "未完善的后果要写清（范围校验失效），实际=" + why(g, "stationLocation"));
        assertFalse(done(g, "catalogOnShelf"), "没有在架商品 → 未完善");
        assertFalse(done(g, "deliveryFee"), "没保存过配送计费 → 未完善");
        // 没有配送员时，工资结构不该报未完善（不打扰没有员工的站）
        assertTrue(done(g, "staffPayroll"), "没有配送员 → 工资结构不该提示");
    }

    @Test
    @DisplayName("补一项就少一项：坐标 + 商品 + 配送费补齐后 P0 归零")
    void completedItemsDisappear() {
        seed();
        jdbc.update("UPDATE station SET lat=36.65, lng=117.12 WHERE id=?", station);
        long water = createProduct("引导目录桶装水", 1, "12.00", "50.00", 0, "0.00");
        createInventoryFull(station, water, 100, 0, "0.00");   // enabled=1 = 已上架
        assertEquals(0, put("/api/manager/delivery-config", mgrToken,
                "{\"baseDeliveryFee\":0,\"minOrderMode\":\"WARN\"}").code(), "保存一次计费配置（哪怕全 0）");

        JsonNode g = guide();
        assertEquals(0, g.path("p0PendingCount").asInt(),
                "三项都补了 → P0 应归零，实际清单=" + g.path("items"));
        assertTrue(g.path("summaryText").asText("").contains("都已配好"),
                "汇总文案要跟着变，实际=" + g.path("summaryText"));
    }

    @Test
    @DisplayName("有配送员但没配计件单价 → 工资结构未完善（真实库正是这个状态）")
    void payrollItemRequiresPieceRateWhenStaffExists() {
        seed();
        long delivery = createStaff("引导目录配送员", "DELIVERY", station, 1);
        assertTrue(delivery > 0);

        JsonNode before = guide();
        assertFalse(done(before, "staffPayroll"), "有员工 + 没单价 → 未完善");
        assertTrue(why(before, "staffPayroll").contains("0 元"),
                "后果要说清（送的水会按 0 元记工钱），实际=" + why(before, "staffPayroll"));

        assertEquals(0, put("/api/manager/piece-rate", mgrToken, "{\"perBucketAmount\":3.00}").code(),
                "配站级默认计件单价");
        assertTrue(done(guide(), "staffPayroll"), "配了单价 → 该项完成");
    }

    @Test
    @DisplayName("开了水票却没档位 → 水票档位未完善；配了档位就算完成")
    void ticketPackageItemTracksOnShelfPackages() {
        seed();
        long water = createProduct("引导目录票水", 1, "20.00", "50.00", 1, "20.00");
        createInventoryFull(station, water, 100, 1, "20.00");   // ticket_enabled=1

        JsonNode before = guide();
        assertFalse(done(before, "ticketPackage"), "开了票但没档位 → 未完善");
        assertTrue(why(before, "ticketPackage").contains("引导目录票水"),
                "要点名是哪个商品，实际=" + why(before, "ticketPackage"));

        jdbc.update("INSERT INTO ticket_package(station_id, product_id, qty, price, unit_price, title, status, sort, "
                + "create_time, update_time) VALUES(?,?,?,?,?,?,1,0,NOW(),NOW())",
                station, water, 10, new java.math.BigDecimal("180.00"),
                new java.math.BigDecimal("18.00"), "10 张超值装");
        assertTrue(done(guide(), "ticketPackage"), "配了上架档位 → 该项完成");
    }

    @Test
    @DisplayName("越权与隔离：站长只看本站的完善度（拿不到别站的电话/坐标）")
    void guideIsStationScoped() {
        seed();
        long otherStation = createStation("引导目录别站");
        long otherMgr = createStaff("引导目录别站站长", "STATION_MANAGER", otherStation, 1);
        jdbc.update("UPDATE station SET phone=?, lat=1.11, lng=2.22 WHERE id=?",
                "0531-99999999", otherStation);

        JsonNode other = get("/api/manager/setup-guide",
                staffToken(otherMgr, "STATION_MANAGER", otherStation)).data();
        assertTrue(done(other, "stationPhone"), "别站自己填了电话");
        assertTrue(done(other, "stationLocation"), "别站自己设了坐标 → 该项完成");
        // 本站在同一时刻仍是"没坐标"，两项互不影响
        assertFalse(done(guide(), "stationLocation"), "本站没坐标，不能被别站带成完成");
    }

    @Test
    @DisplayName("未登录/非站长拿不到完善度（站点取自登录态，不接受传参）")
    void requiresManagerRole() {
        seed();
        long customer = createCustomer("引导目录客户", "guide-scope-openid");
        Api res = get("/api/manager/setup-guide", customerToken(customer));
        assertNotEquals(0, res.code(), "客户不得读站长完善度，实际=" + res);
    }

}
