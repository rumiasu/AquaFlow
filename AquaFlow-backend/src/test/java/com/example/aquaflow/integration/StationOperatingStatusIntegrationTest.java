package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        // 默认：正常运营、无提示
        Api before = get("/api/manager/station-status", mgr);
        assertTrue(before.isSuccess(), "读营业状态应成功，实际=" + before);
        assertEquals(1, before.data().path("operatingStatus").asInt(), "默认应为 1 正常运营");
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
