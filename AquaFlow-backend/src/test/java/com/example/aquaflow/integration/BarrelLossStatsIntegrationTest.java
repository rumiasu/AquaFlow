package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桶损耗统计（只读，2026-09-18）。
 *
 * <p>盯三件事：</p>
 * <ol>
 *   <li><b>它真的统计 {@code barrel_record} 的 3 丢失 / 4 损坏</b>（此前这两类全仓 0 写入，
 *       但语义位与 E5 的 UNION 都在 —— 本用例直接造流水，验证读数是对的）；</li>
 *   <li><b>按站隔离</b>：别站的损耗不许出现在本站读数里；</li>
 *   <li><b>时间上界含结束日当天</b>：这是 AGENTS §8.19 那个反复踩的坑，
 *       写成 {@code <= 结束日} 会让当天整整漏掉。</li>
 * </ol>
 *
 * <p>⚠️ 本用例只验证<b>读</b>。桶损耗目前没有写入口（产品决定），所以响应里
 * {@code hasWriteEntry=false} + 一句口径说明必须一起下发 —— 否则站长会把
 * "没登记过"读成"没损耗"。这条也在用例里钉住。</p>
 */
@DisplayName("桶损耗统计 · 只读汇总 / 按站隔离 / 时间上界含当天")
class BarrelLossStatsIntegrationTest extends AbstractIntegrationTest {

    /** 直接造一条桶损耗流水（3 丢失 / 4 损坏）。quantity 存绝对值，方向由 type 决定。 */
    private void lossRecord(long stationId, long customerId, long productId, int type, int qty, String when) {
        jdbc.update("INSERT INTO barrel_record(customer_id, station_id, product_id, type, quantity, "
                        + "status, delivered_qty, returned_qty, create_time) VALUES (?,?,?,?,?,1,0,0,?)",
                customerId, stationId, productId, type, qty, when);
    }

    @Test
    @DisplayName("按商品分别汇总丢失与损坏，并给出合计与口径说明")
    void aggregatesLostAndDamagedByProduct() {
        long station = createStation("损耗站");
        long manager = createStaff("损耗站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("损耗客户", "loss-openid");
        long productA = createProduct("损耗水A", 1, "20.00", "30.00", 0, "0.00");
        long productB = createProduct("损耗水B", 1, "20.00", "30.00", 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String today = java.time.LocalDate.now().toString();

        lossRecord(station, customer, productA, 3, 2, today + " 09:00:00");  // A 丢失 2
        lossRecord(station, customer, productA, 4, 1, today + " 10:00:00");  // A 损坏 1
        lossRecord(station, customer, productB, 4, 5, today + " 11:00:00");  // B 损坏 5

        Api res = get("/api/manager/barrel-loss?from=" + today + "&to=" + today, mgr);
        assertEquals(0, res.code(), "读损耗统计: " + res);
        assertEquals(2, res.data().path("totalLost").asInt(), "丢失合计: " + res);
        assertEquals(6, res.data().path("totalDamaged").asInt(), "损坏合计: " + res);
        assertEquals(8, res.data().path("totalLoss").asInt(), "损耗合计: " + res);
        assertEquals(2, res.data().path("items").size(), "两个商品各一行");

        // 按损耗数倒序：B（5）在前
        assertEquals("损耗水B", res.data().path("items").get(0).path("productName").asText());
        assertEquals(5, res.data().path("items").get(0).path("damagedQty").asInt());
        // 类型文案由后端下发，前端不自建映射表
        assertEquals("损坏", res.data().path("items").get(0).path("damagedText").asText());
        assertEquals("丢失", res.data().path("items").get(1).path("lostText").asText());

        // ⚠️ 没写入口这件事必须明说：0 表示"没登记过"，不是"没损耗"
        assertTrue(!res.data().path("hasWriteEntry").asBoolean(),
                "当前没有损耗写入口，必须如实下发 hasWriteEntry=false: " + res);
        assertTrue(res.data().path("note").asText().contains("没有登记过"),
                "口径说明必须点明 0 的含义: " + res.data().path("note").asText());
    }

    @Test
    @DisplayName("按站隔离，且时间上界含结束日当天（写成 <= 结束日 会漏掉当天）")
    void stationScopedAndWindowIncludesEndDate() {
        long stationA = createStation("损耗A站");
        long stationB = createStation("损耗B站");
        long managerA = createStaff("损耗站长A", "STATION_MANAGER", stationA, 1);
        long customer = createCustomer("损耗客户2", "loss-openid2");
        long product = createProduct("损耗水C", 1, "20.00", "30.00", 0, "0.00");
        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        String today = java.time.LocalDate.now().toString();

        lossRecord(stationA, customer, product, 3, 3, today + " 08:30:00");   // 本站，今天
        lossRecord(stationB, customer, product, 3, 99, today + " 08:30:00");  // 他站，不能被算进来
        lossRecord(stationA, customer, product, 3, 7, "2020-01-01 08:00:00"); // 本站，窗口外

        Api res = get("/api/manager/barrel-loss?from=" + today + "&to=" + today, mgrA);
        assertEquals(0, res.code(), "读损耗统计: " + res);
        assertEquals(3, res.data().path("totalLost").asInt(),
                "只算本站 + 窗口内（含结束日当天），实际=" + res);
        assertEquals(1, res.data().path("items").size());

        // 不传日期 = 不限窗口，此时应当把本站 2020 那条也算进来（仍不含他站的 99）
        Api all = get("/api/manager/barrel-loss", mgrA);
        assertEquals(10, all.data().path("totalLost").asInt(), "不限窗口应含历史那条: " + all);

        // 起止颠倒必须报错，而不是给个空结果让人以为"没损耗"
        assertTrue(!get("/api/manager/barrel-loss?from=2026-01-02&to=2026-01-01", mgrA).isSuccess(),
                "起止颠倒应报错");
    }
}
