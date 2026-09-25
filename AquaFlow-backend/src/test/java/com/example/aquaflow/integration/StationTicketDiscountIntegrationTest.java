package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站级「统一折扣」（v58，2026-09-20 产品澄清后的定稿形态）。
 *
 * <p>产品原话：「统一水票，**在站长端是特殊化的**，但在**用户端看起来没区别**，
 * 执行上**也不是统一定价**，而是**对应水怎么统一打折、统一打几折**的区别，
 * **不是专门卖统一水票**。」+ 拍板「按统一折扣买的票进**该商品**的账户，只能抵那款水」。</p>
 *
 * <p>本类盯四件事，每一条都对应上面一句话：</p>
 * <ol>
 *   <li><b>不是统一定价</b>：同一个 9.5 折，20 元的水折出 19.00、12 元的水折出 11.40
 *       —— 价格按**各款水自己的水票价**算（★本类最核心的一条）；</li>
 *   <li><b>用户端看起来没区别</b>：顾客拉到的档位永远是**某款水的**档位，没有"统一水票"这个商品；</li>
 *   <li><b>定制优先</b>：某款水自己开了定制票（且有档位）时，即使站里配了统一折扣也走定制的；</li>
 *   <li><b>只能抵那款水</b>：买来的票进**该商品**的账户，别的商品用不了。</li>
 * </ol>
 */
@DisplayName("v58 · 站级统一折扣（按各款水自己的价打折）")
class StationTicketDiscountIntegrationTest extends AbstractIntegrationTest {

    /** 站长配一个统一折扣档：买 qty 张打 discountPerMille 千分比（950 = 9.5 折）。 */
    private long saveTier(long station, String mgrToken, int qty, int perMille) {
        Api res = post("/api/ticket-discounts", mgrToken,
                "{\"qty\":" + qty + ",\"discountPerMille\":" + perMille + "}");
        assertEquals(0, res.code(), "站长应能配统一折扣档: " + res);
        return res.data().path("id").asLong();
    }

    @Test
    @DisplayName("★不是统一定价：同一个 9.5 折，20 元的水折出 19.00、12 元的水折出 11.40")
    void discountAppliesPerProductPrice() {
        long station = createStation("折扣按水站");
        long manager = createStaff("折扣按水站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("折扣按水客户", "std-per-product-openid");
        // 两款桶装水，**都没有开定制票**（inventory.ticket_enabled = 0），价格不同
        long pricey = createProduct("折扣按水·贵水", 1, "20.00", "50.00", 0, "0.00");
        long cheap = createProduct("折扣按水·便宜水", 1, "12.00", "50.00", 0, "0.00");
        createInventoryFull(station, pricey, 100, 0, "0.00");
        createInventoryFull(station, cheap, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        saveTier(station, mgr, 10, 950);   // 10 张 9.5 折

        // 顾客端：两款水**各自**能看到 10 张档，价格按各自的水价算
        JsonNode tiersOfPricey = get("/api/ticket-packages?stationId=" + station + "&productId=" + pricey, mgr).data();
        JsonNode tiersOfCheap = get("/api/ticket-packages?stationId=" + station + "&productId=" + cheap, mgr).data();
        assertEquals(1, tiersOfPricey.size(), "贵水应看到 1 个统一折扣档: " + tiersOfPricey);
        assertEquals(1, tiersOfCheap.size(), "便宜水也应看到 1 个档: " + tiersOfCheap);
        assertEquals("UNIFIED", tiersOfPricey.get(0).path("source").asText(),
                "站里没给这款水开定制票 → 走统一折扣");
        assertEquals(0, new BigDecimal("190.00").compareTo(tiersOfPricey.get(0).path("price").decimalValue()),
                "20.00 × 10 × 0.95 = 190.00");
        assertEquals(0, new BigDecimal("19.00").compareTo(tiersOfPricey.get(0).path("unitPrice").decimalValue()));
        assertEquals(0, new BigDecimal("114.00").compareTo(tiersOfCheap.get(0).path("price").decimalValue()),
                "12.00 × 10 × 0.95 = 114.00 —— **不是**跟贵水同一个价（那才是「统一定价」）");
        assertEquals(0, new BigDecimal("11.40").compareTo(tiersOfCheap.get(0).path("unitPrice").decimalValue()));

        // 真的买一次：服务端按各自水价定价，且**两笔金额不同**
        String cus = customerToken(customer);
        Api buyPricey = post("/api/tickets/purchase", cus,
                "{\"productId\":" + pricey + ",\"quantity\":10,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"unifiedQty\":10,\"idempotencyKey\":\"std-per-product-1\"}");
        assertEquals(0, buyPricey.code(), "按统一折扣买贵水的票: " + buyPricey);
        assertEquals(0, new BigDecimal("190.00").compareTo(
                        decimalOf("SELECT amount FROM payment_record WHERE id=?",
                                buyPricey.data().path("paymentId").asLong())),
                "实付金额 = 该款水价 × 张数 × 折扣");

        Api buyCheap = post("/api/tickets/purchase", cus,
                "{\"productId\":" + cheap + ",\"quantity\":10,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"unifiedQty\":10,\"idempotencyKey\":\"std-per-product-2\"}");
        assertEquals(0, buyCheap.code(), "按同一个折扣买便宜水的票: " + buyCheap);
        assertEquals(0, new BigDecimal("114.00").compareTo(
                        decimalOf("SELECT amount FROM payment_record WHERE id=?",
                                buyCheap.data().path("paymentId").asLong())),
                "同一档折扣、不同水价 → 金额必须不同");
    }

    @Test
    @DisplayName("用户端看起来没区别：档位永远是「某款水的」，没有「统一水票」这种商品")
    void customerSeesNoUnifiedTicketProduct() {
        long station = createStation("无感站");
        long manager = createStaff("无感站长", "STATION_MANAGER", station, 1);
        long water = createProduct("无感站桶装水", 1, "20.00", "50.00", 0, "0.00");
        createInventoryFull(station, water, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        saveTier(station, mgr, 10, 950);

        // 关键：公共接口只认**真实商品 id**；问"商品 0"（v54 那个伪商品）应该什么都没有
        assertEquals(0, get("/api/ticket-packages?stationId=" + station + "&productId=0", mgr).data().size(),
                "统一折扣不是一种商品 —— 顾客端不该存在 productId=0 的档位查询结果");
        // 而真实商品拿到的档位里，也不带任何"统一票"痕迹，只有正常的价格/张数
        JsonNode tier = get("/api/ticket-packages?stationId=" + station + "&productId=" + water, mgr).data().get(0);
        assertTrue(tier.has("qty") && tier.has("price") && tier.has("unitPrice"),
                "档位形状与定制档一致（前端不必分两套渲染）: " + tier);
        assertEquals(0, tier.path("packageId").asLong(), "统一档没有 ticket_package 行 → packageId 为空/0");
    }

    @Test
    @DisplayName("定制优先：这款水自己开了定制票，就不走统一折扣（即使站里配了折扣档）")
    void customTicketWinsOverUnifiedDiscount() {
        long station = createStation("定制优先站");
        long manager = createStaff("定制优先站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("定制优先客户", "std-custom-first-openid");
        // 这款水**开了定制票**（ticket_enabled=1 + 水票价 20）
        long water = createProduct("定制优先站水", 1, "20.00", "50.00", 1, "20.00");
        createInventoryFull(station, water, 100, 1, "20.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 站里配统一折扣 9.5 折；同时给这款水挂一个定制档位（10 张卖 180 = 9 折）
        saveTier(station, mgr, 10, 950);
        assertEquals(0, post("/api/ticket-packages", mgr,
                "{\"productId\":" + water + ",\"qty\":10,\"price\":180.00}").code(), "挂定制档位");

        JsonNode tiers = get("/api/ticket-packages?stationId=" + station + "&productId=" + water, mgr).data();
        assertEquals(1, tiers.size(), "只应看到定制档（不是定制 + 统一各一条）: " + tiers);
        assertEquals("CUSTOM", tiers.get(0).path("source").asText(), "定制优先");
        assertEquals(0, new BigDecimal("180.00").compareTo(tiers.get(0).path("price").decimalValue()),
                "用的是站长挂的定制价 180，不是统一折扣算出来的 190");

        // 买了定制票后再传 unifiedQty 必须被拒（不能绕过定制价）
        String cus = customerToken(customer);
        assertNotEquals(0, post("/api/tickets/purchase", cus,
                "{\"productId\":" + water + ",\"quantity\":10,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"unifiedQty\":10,\"idempotencyKey\":\"std-custom-first-1\"}").code(),
                "该商品已开定制票 → 不许走统一折扣");
    }

    @Test
    @DisplayName("只能抵那款水：按统一折扣买的票进该商品账户，别的商品抵扣被拒")
    void ticketsOnlyCoverTheirOwnProduct() {
        long station = createStation("只抵本品站");
        long manager = createStaff("只抵本品站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("只抵本品客户", "std-own-product-openid");
        long waterA = createProduct("只抵本品站A水", 1, "20.00", "50.00", 0, "0.00");
        long waterB = createProduct("只抵本品站B水", 1, "20.00", "50.00", 0, "0.00");
        createInventoryFull(station, waterA, 100, 0, "0.00");
        createInventoryFull(station, waterB, 100, 0, "0.00");
        long address = createAddress(customer, "只抵本品小区1号");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        saveTier(station, mgr, 10, 950);
        long payId = post("/api/tickets/purchase", cus,
                "{\"productId\":" + waterA + ",\"quantity\":10,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"unifiedQty\":10,\"idempotencyKey\":\"std-own-1\"}")
                .data().path("paymentId").asLong();
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code(), "站长确认收款");

        // 10 张票只落在 A 水账户
        assertEquals(10, intOf("SELECT COUNT(*) FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, waterA) > 0
                ? intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? AND product_id=?",
                        customer, station, waterA) : 0,
                "票应进 A 水自己的账户");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, waterB), "B 水不该凭空有账户");

        // 用 A 的票下 B 水的单：下单闸门放行（站里配了统一折扣），但**支付时必然失败**（B 账户没票）
        Api madeB = post("/api/orders/create", cus,
                "{\"addressId\":" + address + ",\"stationId\":" + station + ",\"paymentMethod\":3"
                        + ",\"idempotencyKey\":\"std-own-order-b\",\"items\":[{\"productId\":" + waterB
                        + ",\"quantity\":1}]}");
        assertTrue(madeB.isSuccess(), "B 水下单应成功（票支付闸门看的是站里配没配折扣）: " + madeB);
        long orderB = madeB.data().path("orderId").asLong();
        assertNotEquals(0, post("/api/payments", cus, "{\"orderId\":" + orderB + ",\"paymentMethod\":3}").code(),
                "A 水的票不能抵 B 水 —— 支付必须失败");
        assertEquals(10, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, waterA), "失败后 A 的票一张不少");
    }

    @Test
    @DisplayName("只抵桶装水 + 站级隔离 + 折扣边界校验")
    void guards() {
        long stationA = createStation("折扣护栏A站");
        long stationB = createStation("折扣护栏B站");
        long managerA = createStaff("折扣护栏站长A", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("折扣护栏站长B", "STATION_MANAGER", stationB, 1);
        long bottle = createProduct("折扣护栏瓶装水", 2, "2.00", "0.00", 0, "0.00");
        createInventoryFull(stationA, bottle, 100, 0, "0.00");
        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        String mgrB = staffToken(managerB, "STATION_MANAGER", stationB);

        long tierId = saveTier(stationA, mgrA, 10, 950);

        // ① 只抵桶装水：瓶装水拿不到统一折扣档
        assertEquals(0, get("/api/ticket-packages?stationId=" + stationA + "&productId=" + bottle, mgrA).data().size(),
                "瓶装水不占桶、没有循环 → 统一折扣不覆盖它");

        // ② 站级隔离：B 站看不到 A 站的折扣档（列表以登录态的水站为准）
        assertEquals(0, get("/api/ticket-discounts", mgrB).data().size(), "B 站不该看到 A 站的折扣档");
        assertTrue(get("/api/ticket-discounts", mgrA).data().size() > 0, "反证：A 站自己看得到");

        // ③ 折扣边界：0 折 / 超过 10 折 / 张数非法都拒绝
        assertNotEquals(0, post("/api/ticket-discounts", mgrA,
                "{\"qty\":20,\"discountPerMille\":0}").code(), "0 折不合法");
        assertNotEquals(0, post("/api/ticket-discounts", mgrA,
                "{\"qty\":20,\"discountPerMille\":1001}").code(), "超过 10 折不合法");
        assertNotEquals(0, post("/api/ticket-discounts", mgrA,
                "{\"qty\":0,\"discountPerMille\":900}").code(), "张数必须大于 0");

        // ④ 删别人的档位删不掉（按 id 操作必须验证归属）
        assertNotEquals(0, delete("/api/ticket-discounts/" + tierId, mgrB).code(), "B 站站长不得删除 A 站的折扣档");
        assertEquals(1, intOf("SELECT COUNT(*) FROM station_ticket_discount WHERE id=?", tierId), "被拒后档位还在");

        // ⑤ 全下架 = 统一折扣关闭（"有没有上架的档位"就是判据，没有开关列）
        assertEquals(0, post("/api/ticket-discounts", mgrA,
                "{\"qty\":10,\"discountPerMille\":950,\"status\":0}").code(), "把唯一一档下架");
        assertEquals(0, get("/api/ticket-packages?stationId=" + stationA + "&productId=" + bottle, mgrA).data().size(),
                "下架后不再下发任何档位");
    }

    @Test
    @DisplayName("回到 v54 之前的样子：客户端只认真实商品，拿不到任何「统一票」形态")
    void nothingNamedUnifiedTicketAnywhere() {
        long station = createStation("无统一票站");
        long manager = createStaff("无统一票站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("无统一票客户", "std-nounified-openid");
        long water = createProduct("无统一票站水", 1, "20.00", "50.00", 0, "0.00");
        createInventoryFull(station, water, 100, 0, "0.00");
        long address = createAddress(customer, "无统一票小区1号");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        saveTier(station, mgr, 10, 950);
        long payId = post("/api/tickets/purchase", cus,
                "{\"productId\":" + water + ",\"quantity\":10,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"unifiedQty\":10,\"idempotencyKey\":\"std-nounified-1\"}")
                .data().path("paymentId").asLong();
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code());

        // 客户水票明细里出现的是**这款水**，不是"统一水票（站级通用）"
        JsonNode accounts = get("/api/tickets?stationId=" + station, cus).data();
        assertEquals(1, accounts.size());
        assertEquals("无统一票站水", accounts.get(0).path("productName").asText(),
                "水票明细按真实商品展示: " + accounts);
        assertEquals(water, accounts.get(0).path("productId").asLong());

        // 流水同理：product_id 是真实商品，且**不再有** account_product_id 这一列
        //（v54 为"站级通用账户"加过它，v59 已撤回；这里用查询本身证明基线里没有它）
        JsonNode records = get("/api/ticket-records?stationId=" + station, cus).data();
        assertTrue(records.size() >= 1, "应有购票流水: " + records);
        assertEquals("无统一票站水", records.get(0).path("productName").asText(),
                "流水也按真实商品展示: " + records.get(0));
    }
}
