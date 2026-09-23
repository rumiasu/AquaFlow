package com.example.aquaflow.integration;

import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v29 · 欠桶台账（{@code owed_since} / 站长端列表 / 下单标红）。
 *
 * <p>被锁死的行为：</p>
 * <ul>
 *   <li>{@code owed_since} 只在 over 由 &le;0 变 &gt;0 时写入，还清即清空，
 *       <b>已是正数再增加不重置</b>（否则"欠了多少天"永远显示今天）。</li>
 *   <li>「欠了几天」必须由 {@code owed_since} 算，不能用会随每次变更刷新的 {@code update_time}。</li>
 *   <li>欠桶**只提醒、不阻断**：不论欠多少，下单都放行，warnings 里出现面向客户的欠桶文案。
 *       <!-- [2026-09-16 修正] 此处原写「欠桶达到阈值时仍然拒绝下单」并称这是既有风控 ——
 *            与同文件的 orderWarnsAndNeverBlocks 用例、以及 OrderServiceImpl 的注释直接矛盾：
 *            原 [AQ-030]/[DEF-3] 的硬拦 MAX_OWED_BUCKETS = 5 已于 2026-09-15 按产品决定移除。
 *            将来若要恢复硬拦，必须是有意的改动，并把 orderWarnsAndNeverBlocks 同步反转为
 *            「欠桶虽多仍可下单」的否命题，不能只改注释。 --></li>
 * </ul>
 */
@DisplayName("v29 · 欠桶台账（owed_since / 站长列表 / 下单标红）")
class BarrelOwedIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BarrelLedgerService barrelLedgerService;

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
    }

    private String owedSince() {
        return jdbc.queryForObject(
                "SELECT owed_since FROM customer_barrel_over WHERE customer_id=? AND station_id=? AND product_id=?",
                String.class, customer, station, product);
    }

    /** 把 owed_since 改成 N 天前，用于验证"天数"确实取自该列（而不是别的什么时间）。 */
    private void backdateOwedSince(int days) {
        jdbc.update("UPDATE customer_barrel_over SET owed_since = DATE_SUB(NOW(), INTERVAL ? DAY) "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", days, customer, station, product);
    }

    private Api createOrderApi(String idemKey, int qty) {
        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":3,\"idempotencyKey\":\"" + idemKey + "\","
                + "\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}";
        return post("/api/orders/create", customerToken(customer), body);
    }

    @Test
    @DisplayName("owed_since：产生时写入 / 正数再增不重置 / 还清清空 / 再欠重新计时")
    void owedSinceLifecycle() {
        seed();

        // 产生欠桶（人工补记 2 个）
        barrelLedgerService.adjustOver(customer, station, product, 2, mgr);
        String first = owedSince();
        assertNotNull(first, "over 由 0 变正后应写入 owed_since");

        // 已欠桶基础上再补记 1 个 —— 同一笔欠桶的延续，起始时间不得重置
        barrelLedgerService.adjustOver(customer, station, product, 1, mgr);
        assertEquals(3, intOf("SELECT over_qty FROM customer_barrel_over WHERE customer_id=? AND station_id=? AND product_id=?",
                customer, station, product));
        assertEquals(first, owedSince(), "已是正数再增加不应重置 owed_since（否则欠桶天数永远显示今天）");

        // 欠着的时候把起始时间人为改旧（合法状态：这笔欠桶确实从那天开始），
        // 验证"还在欠"时 owed_since 不会被任何后续操作刷新
        jdbc.update("UPDATE customer_barrel_over SET owed_since=? WHERE customer_id=? AND station_id=? AND product_id=?",
                "2020-01-01 00:00:00", customer, station, product);
        barrelLedgerService.adjustOver(customer, station, product, 1, mgr);
        assertEquals("2020-01-01 00:00:00", owedSince(), "仍欠桶时 owed_since 不应被刷新");

        // 还清 → 清空
        barrelLedgerService.adjustOver(customer, station, product, -4, mgr);
        assertNull(owedSince(), "欠桶还清后 owed_since 应清空");

        // 再欠 → 重新计时（必须写入新时间，不能沿用上一笔的 2020）
        barrelLedgerService.adjustOver(customer, station, product, 1, mgr);
        String second = owedSince();
        assertNotNull(second, "重新欠桶应重新写入 owed_since");
        assertNotEquals("2020-01-01 00:00:00", second, "清空后再欠必须重新计时，不能沿用上一笔");
    }

    @Test
    @DisplayName("欠桶天数取自 owed_since（不是 update_time），并按阈值标记 urgent")
    void owedDaysComeFromOwedSince() {
        seed();
        barrelLedgerService.adjustOver(customer, station, product, 2, mgr);

        backdateOwedSince(3);
        Api res = get("/api/manager/owed-barrels", staffToken(mgr, "STATION_MANAGER", station));
        assertTrue(res.isSuccess(), "台账接口应成功，实际=" + res);
        assertEquals(1, res.data().size(), "应返回 1 行欠桶");
        assertEquals(3, res.data().get(0).path("owedDays").asInt(), "天数应取自 owed_since");
        assertEquals(2, res.data().get(0).path("overQty").asInt());
        assertEquals("桶装水18.9L", res.data().get(0).path("productName").asText(), "台账要能看出欠的是哪个桶型");
        assertFalse(res.data().get(0).path("urgent").asBoolean(), "3 天不应标记为需催收");

        backdateOwedSince(10);
        Api res2 = get("/api/manager/owed-barrels?minDays=7", staffToken(mgr, "STATION_MANAGER", station));
        assertEquals(1, res2.data().size(), "欠 10 天应被 minDays=7 命中");
        assertTrue(res2.data().get(0).path("urgent").asBoolean(), "≥7 天应标记 urgent");

        assertTrue(get("/api/manager/owed-barrels?minDays=30", staffToken(mgr, "STATION_MANAGER", station))
                .data().isEmpty(), "欠 10 天不应被 minDays=30 命中");
    }

    @Test
    @DisplayName("欠桶只提醒不阻断：下单成功，且提醒面向客户（记得还桶）；幂等命中同样带提醒")
    void orderWarnsAndNeverBlocks() {
        seed();
        barrelLedgerService.adjustOver(customer, station, product, 2, mgr);
        backdateOwedSince(4);

        Api res = createOrderApi("idem-owed-warn", 1);
        assertTrue(res.isSuccess(), "欠桶不应阻断下单，实际=" + res);

        String joined = res.data().path("warnings").toString();
        assertTrue(joined.contains("欠桶"), "warnings 应含欠桶提醒，实际=" + joined);
        assertTrue(joined.contains("记得还桶"), "提醒要面向客户（记得还桶），实际=" + joined);

        // 幂等命中走的是另一条返回分支，提醒也必须照发（客户可能只看到这一次响应）
        Api again = createOrderApi("idem-owed-warn", 1);
        assertTrue(again.isSuccess(), "同幂等键重复下单应正常返回既有订单，实际=" + again);
        assertTrue(again.data().path("warnings").toString().contains("欠桶"),
                "幂等命中也要带欠桶提醒，实际=" + again.data().path("warnings"));
    }

    @Test
    @DisplayName("欠桶远超旧硬拦阈值（12 > 5）依然可下单 —— 硬拦已按产品决定移除")
    void orderIsNotBlockedEvenWithManyOwedBarrels() {
        seed();
        barrelLedgerService.adjustOver(customer, station, product, 12, mgr);

        Api res = createOrderApi("idem-owed-many", 1);
        assertTrue(res.isSuccess(),
                "欠桶 12 个（旧阈值 5）不应再拒绝下单；若这里失败说明硬拦被加回来了，请同步更新本条用例与源注释，实际=" + res);
        assertTrue(res.data().path("warnings").toString().contains("12"),
                "提醒里应带上欠桶数量，实际=" + res.data().path("warnings"));
    }
}
