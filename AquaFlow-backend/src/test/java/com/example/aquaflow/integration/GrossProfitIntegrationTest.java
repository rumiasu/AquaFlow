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

    @Test
    @DisplayName("净利 = 水费 + 配送费 + 楼层费 − 进货成本 − 计件工钱，且与毛利同一批订单")
    void netProfitAddsFeesAndSubtractsWages() {
        long station = createStation("净利站");
        long manager = createStaff("净利站长", "STATION_MANAGER", station, 1);
        long rider = createStaff("净利配送员", "DELIVERY", station, 1);
        long customer = createCustomer("净利客户", "np-openid");
        long address = createAddress(customer, "净利小区 1 号");
        long product = createProduct("净利水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        assertEquals(0, put("/api/manager/gross-profit/cost", mgr,
                "{\"productId\":" + product + ",\"costPrice\":12.00}").code(), "设成本价 12");
        // 计件单价必须在本站配置里，否则完成配送时静默跳过（"没配计件"是合法经营状态）
        assertEquals(0, put("/api/manager/piece-rate", mgr, "{\"perBucketAmount\":3.00}").code(), "设计件单价");

        // 直接造一张「配送中(2)」的现金单（下单→指派→接单三步已由 DeliveryCompleteIntegrationTest 覆盖）
        long orderId = createOrderFull(customer, address, station, product,
                2, 1, 2, "40.00", "0.00", "40.00", false, 2);
        // ⚠️ 必须有 order_item：毛利的水费收入与计件的送桶收益都是**按明细行**产生的
        insert("INSERT INTO order_item(order_id, product_id, product_name_snapshot, price, quantity, deposit, subtotal) "
                        + "VALUES (?,?,?,?,?,?,?)",
                orderId, product, "净利水", new BigDecimal("20.00"), 2,
                new BigDecimal("0.00"), new BigDecimal("40.00"));
        // 配送费/楼层费：下单链路的金额由计费配置决定，这里只验证净利的加法，直接写库
        jdbc.update("UPDATE orders SET delivery_staff_id=?, delivery_fee=5.00, floor_fee=2.00 WHERE id=?",
                rider, orderId);
        // 完成配送（走到「送达」这一步才会产生计件收益）
        assertEquals(0, post("/api/delivery/orders/" + orderId + "/complete", mgr,
                "{\"collected\":false,\"note\":\"净利用例\"}").code(), "完成配送");

        BigDecimal wage = decimalOf(
                "SELECT COALESCE(SUM(amount),0) FROM staff_earning WHERE order_id=?", orderId);
        assertTrue(wage.signum() > 0, "完成配送后必须有计件工钱，否则这个用例证明不了减法");

        String today = java.time.LocalDate.now().toString();
        Api report = get("/api/manager/gross-profit?from=" + today + "&to=" + today, mgr);
        assertEquals(0, report.code(), "净利报表: " + report);
        assertEquals(1, report.data().path("orderCount").asInt(), "单数按订单数，不按明细行数");
        assertEquals(0, new BigDecimal("40.00").compareTo(
                new BigDecimal(report.data().path("totalRevenue").asText())), "水费 2 × 20 = 40");
        assertEquals(0, new BigDecimal("5.00").compareTo(
                new BigDecimal(report.data().path("deliveryFee").asText())), "配送费 5");
        assertEquals(0, new BigDecimal("2.00").compareTo(
                new BigDecimal(report.data().path("floorFee").asText())), "楼层费 2");
        // 收入合计必须**包含**配送费与楼层费（毛利两项都没有），且不含押金
        assertEquals(0, new BigDecimal("47.00").compareTo(
                new BigDecimal(report.data().path("totalIncome").asText())), "收入合计 40 + 5 + 2");
        assertEquals(0, new BigDecimal("24.00").compareTo(
                new BigDecimal(report.data().path("totalCost").asText())), "成本 2 × 12 = 24");
        assertEquals(0, wage.compareTo(new BigDecimal(report.data().path("wage").asText())),
                "工钱应等于该单产生的计件收益合计");
        // 净利 = 47 − 24 − 工钱；毛利 = 40 − 24 = 16
        assertEquals(0, new BigDecimal("23.00").subtract(wage).compareTo(
                        new BigDecimal(report.data().path("netProfit").asText())),
                "净利 = 收入合计 47 − 成本 24 − 工钱 " + wage);
        // 净利与毛利不相等，否则说明配送费/楼层费/工钱有一项根本没接上
        assertNotEquals(0, new BigDecimal(report.data().path("totalProfit").asText())
                        .compareTo(new BigDecimal(report.data().path("netProfit").asText())),
                "净利与毛利不应相等（本例有配送费/楼层费/工钱）");
    }

    @Test
    @DisplayName("缺成本时净利也必须是 null —— 只 null 毛利会让站长拿净利继续当真")
    void netProfitIsNullWhenCostMissing() {
        long station = createStation("缺成本净利站");
        long manager = createStaff("缺成本净利站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("缺成本净利客户", "np-missing-openid");
        long address = createAddress(customer, "缺成本净利小区 1 号");
        long product = createProduct("缺成本净利水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        // 现金单（货到付款需要客户级授权，故直接造数而不是走下单接口）
        long orderId = createOrderFull(customer, address, station, product,
                2, 1, 2, "40.00", "0.00", "40.00", false, 2);
        insert("INSERT INTO order_item(order_id, product_id, product_name_snapshot, price, quantity, deposit, subtotal) "
                        + "VALUES (?,?,?,?,?,?,?)",
                orderId, product, "缺成本净利水", new BigDecimal("20.00"), 2,
                new BigDecimal("0.00"), new BigDecimal("40.00"));

        String today = java.time.LocalDate.now().toString();
        Api report = get("/api/manager/gross-profit?from=" + today + "&to=" + today, mgr);
        assertEquals(0, report.code(), "净利报表: " + report);
        assertTrue(report.data().path("totalProfit").isNull(), "合计毛利必须是 null");
        assertTrue(report.data().path("netProfit").isNull(),
                "合计净利同样必须是 null —— 成本按 0 计会让净利凭空多出整整一个进货成本");
        // 收入构成仍然照实下发（那些数字不依赖成本）
        assertEquals(1, report.data().path("orderCount").asInt(), "单数照实下发");
        assertTrue(report.data().path("profitBasisNote").asText().contains("净利 ="),
                "必须下发净利口径文案: " + report.data().path("profitBasisNote").asText());
    }

    private Api order(String customerToken, long stationId, long addressId, long productId,
                      int qty, String key) {
        return post("/api/orders/create", customerToken,
                "{\"addressId\":" + addressId + ",\"stationId\":" + stationId + ",\"paymentMethod\":1,"
                        + "\"idempotencyKey\":\"" + key + "\","
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }
}
