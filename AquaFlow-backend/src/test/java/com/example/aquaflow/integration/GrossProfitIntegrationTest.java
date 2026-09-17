package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进货成本与毛利（v39，Phase 3）。
 *
 * <p>盯三件事：成本能落到**站级**、毛利算得对（且排除取消单）、
 * <b>没填成本时必须显示"算不出来"而不是全额毛利</b>。</p>
 *
 * <p>最后一条是本用例里最重要的：把缺失成本当 0 相减，站长会以为自己赚了整整一个售价 ——
 * 那是最坏的一种"看起来正确"。</p>
 */
class GrossProfitIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("毛利 = 收入 − 销量×成本，且只算本站、排除取消单")
    void profitComputedForStationOnly() {
        long station = createStation("毛利站");
        long otherStation = createStation("毛利他站");
        long manager = createStaff("毛利站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("毛利客户", "gp-openid");
        long address = createAddress(customer, "毛利小区 1 号");
        // 售价 20，成本 12
        long product = createProduct("毛利水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        createInventoryFull(otherStation, product, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        assertEquals(0, put("/api/manager/gross-profit/cost", mgr,
                "{\"productId\":" + product + ",\"costPrice\":12.00}").code(), "设置成本价");
        assertEquals(0, new BigDecimal("12.00").compareTo(
                        decimalOf("SELECT cost_price FROM inventory WHERE station_id=? AND product_id=?",
                                station, product)), "成本必须落在**本站**的 inventory 行上");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=? AND cost_price IS NULL",
                        otherStation, product),
                "成本是站级的：他站的同一商品不该被写入成本");

        // 下一单：2 桶 × 20 = 40 收入
        assertEquals(0, order(customerToken(customer), station, address, product, 2, "gp-1").code());

        String today = java.time.LocalDate.now().toString();
        Api report = get("/api/manager/gross-profit?from=" + today + "&to=" + today, mgr);
        assertEquals(0, report.code(), "毛利报表: " + report);
        assertEquals(0, new BigDecimal("40.00").compareTo(
                        new BigDecimal(report.data().path("totalRevenue").asText())), "收入 2 × 20 = 40");
        assertEquals(0, new BigDecimal("24.00").compareTo(
                        new BigDecimal(report.data().path("totalCost").asText())), "成本 2 × 12 = 24");
        assertEquals(0, new BigDecimal("16.00").compareTo(
                        new BigDecimal(report.data().path("totalProfit").asText())), "毛利 40 − 24 = 16");
        assertEquals(0, report.data().path("missingCostKinds").asInt(), "本商品已填成本");

        // 取消该单 → 收入/成本/毛利都应归零（取消单不进营收）
        long orderId = longOf("SELECT id FROM orders WHERE station_id=? ORDER BY id DESC LIMIT 1", station);
        assertEquals(0, put("/api/orders/" + orderId + "/customer-cancel", customerToken(customer),
                "{\"reason\":\"测试取消\"}").code(), "取消订单");
        Api after = get("/api/manager/gross-profit?from=" + today + "&to=" + today, mgr);
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        new BigDecimal(after.data().path("totalRevenue").asText())),
                "已取消(5)的订单不该计入营收");
    }

    @Test
    @DisplayName("没填成本的商品：毛利显示「算不出来」而不是全额，合计毛利给 null + 提示")
    void missingCostIsNotTreatedAsZero() {
        long station = createStation("缺成本站");
        long manager = createStaff("缺成本站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("缺成本客户", "gp-missing-openid");
        long address = createAddress(customer, "缺成本小区 1 号");
        long product = createProduct("缺成本水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        assertEquals(0, order(customerToken(customer), station, address, product, 2, "gp-2").code());
        String today = java.time.LocalDate.now().toString();

        Api report = get("/api/manager/gross-profit?from=" + today + "&to=" + today, mgr);
        assertEquals(0, report.code(), "毛利报表: " + report);
        assertEquals(0, new BigDecimal("40.00").compareTo(
                        new BigDecimal(report.data().path("totalRevenue").asText())), "收入仍要照实显示");
        assertEquals(1, report.data().path("missingCostKinds").asInt(), "应标出 1 个商品没填成本");
        // ⚠️ 核心断言：合计毛利必须是 null，不能是 40（那是"把成本当 0"算出来的假毛利）
        assertTrue(report.data().path("totalProfit").isNull(),
                "有商品没填成本时，合计毛利不得给出数字（把成本当 0 会显示成赚了整个售价）");
        assertTrue(report.data().path("missingCostHint").asText().contains("没填进货成本"),
                "必须给出可读提示: " + report.data().path("missingCostHint").asText());

        // 单商品行也不得给出毛利数字
        assertEquals("未填成本，无法计算",
                report.data().path("items").get(0).path("profitText").asText(),
                "缺成本的行必须明确写「算不出来」");

        // 欠成本清单要能列出它
        assertEquals(1, get("/api/manager/gross-profit/missing-cost", mgr).data().size(),
                "未填成本清单应列出该商品");
    }

    @Test
    @DisplayName("护栏：负成本/缺参数/未上架都被拒，且「清除」必须显式声明")
    void costGuards() {
        long station = createStation("成本护栏站");
        long otherStation = createStation("成本护栏他站");
        long manager = createStaff("成本护栏站长", "STATION_MANAGER", station, 1);
        long product = createProduct("成本护栏水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        assertNotEquals(0, put("/api/manager/gross-profit/cost", mgr,
                "{\"productId\":" + product + ",\"costPrice\":-1.00}").code(), "负成本必须被拒");
        assertNotEquals(0, put("/api/manager/gross-profit/cost", mgr,
                "{\"costPrice\":12.00}").code(), "缺 productId 必须被拒");
        // ⚠️ 这条是关键：不给 costPrice 又不声明 clear，必须报错而不是"静默清空"
        // （客户端把键名拼错时就会走到这里）
        assertNotEquals(0, put("/api/manager/gross-profit/cost", mgr,
                "{\"productId\":" + product + ",\"cost\":9.90}").code(),
                "键名拼错时必须报错 —— 不能变成一次静默清空成本价");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=? AND cost_price IS NULL",
                station, product), "被拒的请求不得改动库里的成本价");

        // 他站没有该商品的上架配置 → 不得静默成功
        // （他站本来就没有 inventory 行，这里不必先断言行数 —— 直接验证"没有行时不得返回成功"）
        String otherMgr = staffToken(createStaff("成本护栏他站长", "STATION_MANAGER", otherStation, 1),
                "STATION_MANAGER", otherStation);
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=?",
                otherStation, product), "他站本来就没有该商品的上架行");
        assertNotEquals(0, put("/api/manager/gross-profit/cost", otherMgr,
                "{\"productId\":" + product + ",\"costPrice\":9.00}").code(),
                "本站没有该商品的上架配置时必须报错，不能静默成功");

        // 显式清除
        assertEquals(0, put("/api/manager/gross-profit/cost", mgr,
                "{\"productId\":" + product + ",\"costPrice\":12.00}").code());
        assertEquals(0, put("/api/manager/gross-profit/cost", mgr,
                "{\"productId\":" + product + ",\"clear\":true}").code(), "显式清除应成功");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=? AND cost_price IS NULL",
                station, product), "清除后成本应回到 NULL");
    }

    @Test
    @DisplayName("时间窗含当天：结束日传今天时，今天下的单必须被统计到")
    void reportIncludesToday() {
        long station = createStation("当日毛利站");
        long manager = createStaff("当日毛利站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("当日毛利客户", "gp-today-openid");
        long address = createAddress(customer, "当日毛利小区 1 号");
        long product = createProduct("当日毛利水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        assertEquals(0, put("/api/manager/gross-profit/cost", mgr,
                "{\"productId\":" + product + ",\"costPrice\":12.00}").code());
        assertEquals(0, order(customerToken(customer), station, address, product, 1, "gp-today").code());

        String today = java.time.LocalDate.now().toString();
        // ⚠️ 时间上界若写成 <= 结束日，今天的单一条都统计不到（AGENTS §8.19）——
        // 站长看到的会是"今天没卖出去"，而这最容易被误读成"确实没卖"
        Api report = get("/api/manager/gross-profit?from=" + today + "&to=" + today, mgr);
        assertEquals(0, new BigDecimal("20.00").compareTo(
                        new BigDecimal(report.data().path("totalRevenue").asText())),
                "结束日传今天时，今天下的单必须被统计到");
    }

    private Api order(String customerToken, long stationId, long addressId, long productId,
                      int qty, String key) {
        return post("/api/orders/create", customerToken,
                "{\"addressId\":" + addressId + ",\"stationId\":" + stationId + ",\"paymentMethod\":1,"
                        + "\"idempotencyKey\":\"" + key + "\","
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }
}
