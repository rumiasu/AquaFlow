package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水站营业状态（**软状态**）+ 站长留言 —— 2026-09-17 新增。
 *
 * <p>产品口径：「正常运营 / 休息…」，<b>不阻断下单</b>，只弹提示。因此这批用例要同时锁住两件事：</p>
 * <ol>
 *   <li>状态能被站长设置、顾客能看到（公开端点 + 下单响应 warnings）；</li>
 *   <li><b>设置了"休息中"之后，客户照样能下单成功</b> —— 这是与硬状态 {@code station.status=2 停业}
 *       的关键区别，谁把软状态接进下单校验，这条用例就会红。</li>
 * </ol>
 */
@DisplayName("水站营业状态（软状态）：站长设置 → 顾客可见 → 不阻断下单")
class StationOperatingStatusIntegrationTest extends AbstractIntegrationTest {

    private static final String RESTING = "\u4f11\u606f\u4e2d";

    @Test
    @DisplayName("站长设置营业状态与留言：落库 + 回读 + 顾客公开端可见")
    void managerCanSetOperatingStatusAndCustomerCanSeeIt() {
        long station = createStation("状态站");
        long manager = createStaff("状态站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 夹具建的站**显式**是正常运营（见 AbstractIntegrationTest.createStation 的注释）：
        // 这里验的是"正常运营时不给顾客提示"；"新注册站默认待上线"由下面
        // newlyCreatedStationDefaultsToPendingLaunch 单独验。
        Api before = get("/api/manager/station-status", mgr);
        assertTrue(before.isSuccess(), "读营业状态应成功，实际=" + before);
        assertEquals(1, before.data().path("operatingStatus").asInt(), "夹具建的站应为 1 正常运营");
        assertTrue(before.data().path("customerHint").isNull(), "正常运营时不应有顾客提示");

        Api set = put("/api/manager/station-status", mgr,
                "{\"operatingStatus\":2,\"note\":\"今天休息，明早8点正常送水\"}");
        assertTrue(set.isSuccess(), "设置营业状态应成功，实际=" + set);
        assertEquals(2, intOf("SELECT operating_status FROM station WHERE id=?", station), "营业状态应落库");
        assertEquals("今天休息，明早8点正常送水",
                jdbc.queryForObject("SELECT status_note FROM station WHERE id=?", String.class, station));
        assertEquals(1, intOf("SELECT COUNT(*) FROM station WHERE id=? AND status_update_time IS NOT NULL", station),
                "状态更新时间应写入");

        // 顾客侧公开端点（无需登录）
        Api pub = get("/api/stations/" + station + "/status", null);
        assertTrue(pub.isSuccess(), "公开营业状态应可读，实际=" + pub);
        assertEquals(2, pub.data().path("operatingStatus").asInt());
        assertTrue(pub.data().path("customerHint").asText().contains(RESTING),
                "顾客提示里应包含状态文案：" + pub);
        assertTrue(pub.data().path("customerHint").asText().contains("今天休息"),
                "顾客提示里应包含站长留言：" + pub);
        assertEquals(1, pub.data().path("hardStatus").asInt(),
                "硬状态仍是 1 营业（软状态不允许改动硬状态）");

        // 公开选站列表也带上（实体自动带出）
        Api publicList = get("/api/stations/public", null);
        assertTrue(publicList.isSuccess(), "公开选站列表应可读，实际=" + publicList);

        // 非法取值被拒
        assertNotEquals(0, put("/api/manager/station-status", mgr, "{\"operatingStatus\":99}").code(),
                "非法营业状态应被拒");
        assertNotEquals(0, put("/api/manager/station-status", mgr,
                "{\"operatingStatus\":2,\"note\":\"" + "x".repeat(101) + "\"}").code(),
                "留言超 100 字应被拒");
    }

    @Test
    @DisplayName("新注册水站默认「待上线」(3)：不写 operating_status 就吃列默认值，且顾客会看到提示")
    void newlyCreatedStationDefaultsToPendingLaunch() {
        // 刻意**不写 operating_status** —— 验的就是列 DEFAULT 3
        // （正本 sql/migration_v61_station_pending_launch.sql；夹具那条路径显式写 1，验不到这里）
        long station = insert("INSERT INTO station(name, status) VALUES (?, 1)", "新注册站");
        assertEquals(3, intOf("SELECT operating_status FROM station WHERE id=?", station),
                "新注册水站默认应为 3 待上线");

        long manager = createStaff("新站站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api view = get("/api/manager/station-status", mgr);
        assertTrue(view.isSuccess(), "读营业状态应成功，实际=" + view);
        assertEquals(3, view.data().path("operatingStatus").asInt(), "读回来的也该是 3");
        assertTrue(view.data().path("statusText").asText().contains("待上线"),
                "状态文案由后端下发，应为「待上线」：" + view);

        // 选择器选项也必须由后端下发（前端原先自写了一份 1..4 映射表，含已废弃的「配送延迟」）。
        // ⚠️ 2026-09-24 起是 **3 个**：「待上线」是系统状态，不是站长能选的（见下一个用例）。
        var options = view.data().path("options");
        assertEquals(3, options.size(), "站长可选状态是 3 个（待上线不在其中）：" + view);
        for (var o : options) {
            assertNotEquals(3, o.path("value").asInt(),
                    "「待上线」不该出现在选择器里 —— 出现了就等于告诉站长他能设，而后端会拒：" + o);
        }

        // 顾客公开端：待上线要给出提示（语义是"这家站还没正式营业"，与"休息中"是两句话）
        Api pub = get("/api/stations/" + station + "/status", null);
        assertTrue(pub.isSuccess(), "公开营业状态应可读，实际=" + pub);
        assertTrue(!pub.data().path("customerHint").isNull(), "待上线必须给顾客提示：" + pub);
        assertTrue(pub.data().path("customerHint").asText().contains("尚未正式营业"),
                "待上线给顾客的应是「尚未正式营业」口径，而不是「水站当前：待上线」：" + pub);
    }

    /**
     * 「待上线」的全部语义（2026-09-24 产品裁定，一条用例锁住五条规则）。
     *
     * <p>产品原话：「先做成不能被发现，正常上线后才能发现。不做成可选状态了，只有刚刚注册的
     * 才能是这个状态，然后全部填完才能转正、正常设置营业状态。无法变回这个状态。」</p>
     */
    @Test
    @DisplayName("待上线：不可选 / 客户发现不了 / 填完才能转正 / 转正后回不去")
    void pendingLaunchIsOneWayGate() {
        long station = insert("INSERT INTO station(name, status) VALUES (?, 1)", "待上线站");
        long manager = createStaff("待上线站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // ① 客户端**发现不了**：选站列表与配送员搜索都不该有它
        assertFalse(publicStationIds().contains(station),
                "待上线的站不该出现在客户选站列表（/api/stations/public）里");
        Api search = get("/api/stations/search?keyword=" + "待上线站", null);
        assertTrue(search.isSuccess(), "搜索应可读，实际=" + search);
        for (var s : search.data()) {
            assertNotEquals(station, s.path("id").asLong(), "待上线的站也不该被搜到：" + search);
        }

        // ② 站长不能自己设成「待上线」
        assertNotEquals(0, put("/api/manager/station-status", mgr, "{\"operatingStatus\":3}").code(),
                "「待上线」是系统状态，不能手动设置");

        // ③ 还没转正时，除了「正常运营」别的状态都不许
        assertNotEquals(0, put("/api/manager/station-status", mgr, "{\"operatingStatus\":2}").code(),
                "还没转正不该能设成「休息中」—— 它还没开始营业，谈不上休息");

        // ④ 资料没配齐 → 转正被拒，且要说清还差几项（只说"不能设置"等于让站长干瞪眼）
        Api blocked = put("/api/manager/station-status", mgr, "{\"operatingStatus\":1}");
        assertNotEquals(0, blocked.code(), "资料没配齐不许转正，实际=" + blocked);
        assertTrue(blocked.message().contains("项必填资料"),
                "拒绝理由要说清还差几项必填，实际=" + blocked.message());

        // ⑤ 配齐**必填项**（电话 / 地址+坐标 / 上架商品 / 配送计费）
        jdbc.update("UPDATE station SET phone=?, address=?, lat=36.65, lng=117.12 WHERE id=?",
                "0531-00000000", "待上线站地址", station);
        long water = createProduct("待上线站的水", 1, "12.00", "50.00", 0, "0.00");
        createInventoryFull(station, water, 100, 0, "0.00");
        assertEquals(0, put("/api/manager/delivery-config", mgr,
                "{\"baseDeliveryFee\":0,\"minOrderMode\":\"WARN\"}").code(), "保存一次计费配置");

        // ⑤' **建议项（P1）不该挡住转正**（2026-09-24 产品裁定："水票不该强制"）。
        //     这里刻意造一个 P1 未完成：商品开了水票却没配档位。
        //     ⚠️ 判据必须是 p0PendingCount 而不是 pendingCount —— 谁把门槛改回"全部"，
        //        这条断言就会红（这正是它存在的意义）。
        jdbc.update("UPDATE inventory SET ticket_enabled = 1 WHERE station_id = ? AND product_id = ?",
                station, water);
        Api goLive = put("/api/manager/station-status", mgr, "{\"operatingStatus\":1}");
        assertEquals(0, goLive.code(),
                "只剩建议项（水票档位）没配时**必须允许转正**，实际=" + goLive);
        // 但那一项仍要出现在清单里 ——"不强制"不等于"不提醒"
        Api afterGoLive = get("/api/manager/setup-guide", mgr);
        assertEquals(0, afterGoLive.data().path("p0PendingCount").asInt(),
                "必填项应为 0（否则上面那次转正根本不该成功），实际=" + afterGoLive);
        assertNotEquals(0, afterGoLive.data().path("pendingCount").asInt(),
                "建议项没配好仍要在清单里提醒，实际=" + afterGoLive);

        // ⑥ 转正后：按普通软状态随意设置，且**再也回不去待上线**
        assertEquals(0, put("/api/manager/station-status", mgr,
                "{\"operatingStatus\":2,\"note\":\"休息\"}").code(), "转正后应能正常设置营业状态");
        assertNotEquals(0, put("/api/manager/station-status", mgr, "{\"operatingStatus\":3}").code(),
                "转正后不能再变回待上线");
        assertEquals(2, intOf("SELECT operating_status FROM station WHERE id=?", station),
                "被拒的那次写入不能偷偷落库");

        // ⑦ 转正后客户就能发现它了
        assertTrue(publicStationIds().contains(station),
                "转正后应出现在客户选站列表里（这正是「上线后才能被发现」）");
    }

    /** 客户选站列表（`/api/stations/public`，无需登录）里的站 id 集合。 */
    private java.util.Set<Long> publicStationIds() {
        Api res = get("/api/stations/public", null);
        assertTrue(res.isSuccess(), "公开选站列表应可读，实际=" + res);
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (var s : res.data()) {
            ids.add(s.path("id").asLong());
        }
        return ids;
    }

    @Test
    @DisplayName("休息中**不阻断下单**，但下单响应必须带提示（软状态与硬状态的分界）")
    void restingDoesNotBlockOrderButWarns() {
        long station = createStation("休息站");
        long manager = createStaff("休息站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("休息客户", "rest-openid");
        long product = createProduct("休息水", 1, "20.00", "30.00", 0, "0.00");
        createInventory(station, product, 100);
        long addr = createAddress(customer, "某小区1号");

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        assertEquals(0, put("/api/manager/station-status", mgr,
                "{\"operatingStatus\":4,\"note\":\"今天不送了，订单明天统一配送\"}").code(),
                "设置「暂停配送可预约」应成功");

        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":1,\"idempotencyKey\":\"rest-k1\","
                + "\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}";
        Api res = post("/api/orders/create", customerToken(customer), body);
        assertTrue(res.isSuccess(), "休息中**必须仍可下单**（软状态不阻断），实际=" + res);

        var warnings = res.data().path("warnings");
        assertTrue(warnings.isArray() && warnings.size() > 0, "下单响应应带营业状态提示：" + res);
        boolean hasHint = false;
        for (var w : warnings) {
            if (w.asText().contains("今天不送了")) hasHint = true;
        }
        assertTrue(hasHint, "提示里应包含站长留言：" + res);
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE idempotency_key='rest-k1'"),
                "订单必须真的落库（不是被拒绝）");
    }

    @Test
    @DisplayName("硬状态「停业」仍然阻断下单（软状态不得削弱它）")
    void hardStatusStillBlocksOrder() {
        long station = createStation("停业站");
        long manager = createStaff("停业站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("停业客户", "closed-openid");
        long product = createProduct("停业水", 1, "20.00", "30.00", 0, "0.00");
        createInventory(station, product, 100);
        long addr = createAddress(customer, "某小区2号");

        jdbc.update("UPDATE station SET status = 2 WHERE id = ?", station);
        // 软状态仍设成"正常运营"：证明拦截来自硬状态，而不是软状态
        assertEquals(0, put("/api/manager/station-status", mgrTokenOf(manager, station),
                "{\"operatingStatus\":1}").code(), "软状态设置不应失败");

        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":1,\"idempotencyKey\":\"closed-k1\","
                + "\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}";
        Api res = post("/api/orders/create", customerToken(customer), body);
        assertNotEquals(0, res.code(), "停业（硬状态=2）必须拒绝下单，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE idempotency_key='closed-k1'"));
    }

    private String mgrTokenOf(long staffId, long stationId) {
        return staffToken(staffId, "STATION_MANAGER", stationId);
    }
}
