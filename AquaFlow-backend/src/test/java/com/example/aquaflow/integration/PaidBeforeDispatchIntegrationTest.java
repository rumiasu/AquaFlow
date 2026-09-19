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
 * 「先收到钱，才把单推给站长 / 才允许接单」这条规则（2026-09-18 产品裁定）。
 *
 * <p>产品原话：「至于支付的订单才推到站长那里，允许接单，只有允许货到付款的客户是例外。」
 * 补充口径：**水票单算已付**（等同微信支付成功）；**微信未支付单在配送员那里当不存在**，
 * 支付成功才推（渠道未接入期间它们会一直停在待支付）。</p>
 *
 * <p>本类盯四件事：</p>
 * <ol>
 *   <li><b>未支付的微信单不进任何一张待办列表</b>（站长待分配、配送员待接单），
 *       也不能被接单 / 被分配（列表看不到但 id 可编造，所以业务闸门必须另守一道）；</li>
 *   <li><b>支付成功后自动出现</b>——判据是查询时算的，不靠推送、不需要回填；</li>
 *   <li><b>货到付款是例外</b>：客户在本站开通货到付款后，现金单下单即进列表；
 *       没开通的客户**根本下不了现金单**（所以"现金单但未开通"这个状态不该存在）；</li>
 *   <li><b>水票单在下单那一刻就已经扣票并记已付</b>（票不足则整笔下单失败、订单不存在），
 *       否则"水票算已付"就只是句口号 —— 客户端不补那次支付请求时，货送出去而票一分没动。</li>
 * </ol>
 */
@DisplayName("先付款后派单：未支付的单不推给站长/配送员，也不能接单（货到付款与水票例外）")
class PaidBeforeDispatchIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long mgr;
    private long driver;
    private long customer;
    private long address;
    /** 非桶装水商品：不触碰押金/桶权益规则，把本类要盯的"钱到没到"与那条规则解耦。 */
    private long goods;
    private long ticketGoods;
    private String tokenMgr;
    private String tokenDriver;
    private String cus;

    private void seed(boolean offlinePaymentEnabled) {
        station = createStation("付款站");
        mgr = createStaff("付款站长", "STATION_MANAGER", station, 1);
        driver = createStaff("付款配送员", "DELIVERY", station, 1);
        customer = createCustomer("付款客户", "paid-openid");
        address = createAddress(customer, "付款小区1号");
        goods = createProduct("付款饮水机", 2, "20.00", "0.00", 0, "0.00");
        ticketGoods = createProduct("付款饮水机票", 2, "20.00", "0.00", 1, "18.00");
        createInventoryFull(station, goods, 100, 0, "0.00");
        createInventoryFull(station, ticketGoods, 100, 1, "18.00");
        if (offlinePaymentEnabled) {
            createCustomerStationConfig(customer, station, 1);
        }
        tokenMgr = staffToken(mgr, "STATION_MANAGER", station);
        tokenDriver = staffToken(driver, "DELIVERY", station);
        cus = customerToken(customer);
    }

    private Api place(long productId, int payMethod, String key) {
        return place(productId, payMethod, key, 1);
    }

    private Api place(long productId, int payMethod, String key, int qty) {
        return post("/api/orders/create", cus, "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":" + payMethod + ",\"idempotencyKey\":\"" + key + "\","
                + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }

    private long orderIdOf(String key) {
        return longOf("SELECT id FROM orders WHERE idempotency_key=?", key);
    }

    /* ==================================================================
     *  1. 未支付的微信单：看不到、接不了
     * ================================================================== */

    @Test
    @DisplayName("未支付的微信单：两张待办列表都没有它，接单/分配都被拒；收款确认后自动出现")
    void unpaidWechatOrderIsInvisibleUntilPaid() {
        seed(true);
        assertEquals(0, place(goods, 1 /* 微信 */, "paid-wechat-1").code(), "下单应成功");
        long order = orderIdOf("paid-wechat-1");
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order), "客户还没付钱");

        assertFalse(inList("/api/delivery/orders/station-pending", tokenMgr, order),
                "没收到钱的单不该出现在站长待分配里");
        assertFalse(inList("/api/delivery/orders/pending", tokenDriver, order),
                "没收到钱的单不该出现在配送员待接单里");

        // 列表看不到 ≠ 接口调不动：id 是客户端可编造的，所以业务闸门要另守一道
        assertNotEquals(0, post("/api/delivery/orders/" + order + "/accept", tokenDriver, "{}").code(),
                "未支付的单不得接单");
        assertNotEquals(0, post("/api/delivery/orders/assign/" + order, tokenMgr,
                "{\"deliveryStaffId\":" + driver + "}").code(), "未支付的单不得分配配送员");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "两次拒绝都不得改状态");

        // 客户完成支付（渠道未接入 → 站长核对到账后手工确认）→ 订单自动出现在待办里
        Api created = post("/api/payments", tokenMgr, "{\"orderId\":" + order + ",\"paymentMethod\":1}");
        assertEquals(0, created.code(), "发起收款应成功: " + created);
        long paymentId = longOf("SELECT id FROM payment_record WHERE order_id=? AND status=1", order);
        assertEquals(0, put("/api/payments/" + paymentId + "/confirm", tokenMgr, "{}").code(),
                "站长确认到账应成功");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "收款后应置已付");

        assertTrue(inList("/api/delivery/orders/station-pending", tokenMgr, order),
                "收到钱之后必须自动出现在站长待分配里（判据是查询时算的，不靠推送回填）");
        assertTrue(inList("/api/delivery/orders/pending", tokenDriver, order),
                "收到钱之后必须自动出现在配送员待接单里");
        assertTrue(post("/api/delivery/orders/" + order + "/accept", tokenDriver, "{}").isSuccess(),
                "已支付的单应可接单");
    }

    /* ==================================================================
     *  2. 货到付款：例外（且"没开通就下不了单"）
     * ================================================================== */

    @Test
    @DisplayName("货到付款客户：现金单下单即进两张列表；未开通的客户连单都下不出来")
    void codIsTheExceptionAndUnopenedCustomerCannotEvenOrder() {
        seed(true);
        assertEquals(0, place(goods, 2 /* 现金 */, "paid-cod-1").code(), "开通货到付款后现金单应可下");
        long order = orderIdOf("paid-cod-1");
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "现金单下单即待收款（钱要当面收）");
        assertTrue(inList("/api/delivery/orders/station-pending", tokenMgr, order),
                "货到付款是例外：钱还没到也要推进站长视野（否则没人去送、也就收不到钱）");
        assertTrue(inList("/api/delivery/orders/pending", tokenDriver, order), "配送员同样看得到");
        assertTrue(post("/api/delivery/orders/" + order + "/accept", tokenDriver, "{}").isSuccess(),
                "货到付款单应可直接接单");
    }

    @Test
    @DisplayName("未开通货到付款的客户：现金单在下单那一刻就被拒（不存在「未开通却是现金单」的单）")
    void cashOrderIsRejectedWhenOfflinePaymentNotEnabled() {
        seed(false);
        Api res = place(goods, 2, "paid-cod-unopened");
        assertNotEquals(0, res.code(), "未开通货到付款却下现金单，必须被拒");
        assertTrue(res.message() != null && res.message().contains("货到付款"),
                "拒绝文案要指向货到付款，实际=" + res.message());
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE idempotency_key=?", "paid-cod-unopened"),
                "被拒的单不得留下任何痕迹");
    }

    /* ==================================================================
     *  3. 水票：扣票成功那一刻等同已付（与微信支付成功同一条线）
     * ================================================================== */

    @Test
    @DisplayName("水票单：下单只落单不进列表；扣票成功（置已付）后自动进列表；票不够则扣不动、也不进列表")
    void ticketOrderEntersListsOnlyAfterTicketsAreDeducted() {
        seed(true);
        createTicketAccount(customer, station, ticketGoods, 5);

        assertEquals(0, place(ticketGoods, 3 /* 水票 */, "paid-ticket-1").code(), "水票下单应成功");
        long order = orderIdOf("paid-ticket-1");
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "下单只是落单：票还没扣，钱还没到手");
        assertFalse(inList("/api/delivery/orders/station-pending", tokenMgr, order),
                "票还没扣的单不得进站长待分配 —— 否则就是「货送出去、票一分没动」");
        assertEquals(5, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? AND product_id=?",
                customer, station, ticketGoods), "下单本身不扣票");

        // 客户用票支付（扣票是服务端原子扣减，余额不足会直接抛业务错误）
        Api paid = post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":3}");
        assertEquals(0, paid.code(), "用票支付应成功: " + paid);
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "扣票成功 = 等同已付（水票与微信支付成功同一条线）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", order),
                "必须留下一条已付凭据（否则对账等式2 会判「已付无凭证」）");
        assertEquals(4, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? AND product_id=?",
                customer, station, ticketGoods), "用户付了 1 桶的票：5 张变 4 张");
        assertEquals(4, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM ticket_lot WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customer, station, ticketGoods), "批次账必须同步（E8：账户 = Σ 批次）");
        assertTrue(inList("/api/delivery/orders/station-pending", tokenMgr, order),
                "扣票成功后自动进站长待分配");

        // 票不够：下单能成（与「先下单、再扣票」的既有流程一致），但扣不动 → 永远进不了站长视野
        long before = longOf("SELECT COUNT(*) FROM orders WHERE station_id=?", station);
        assertEquals(0, place(ticketGoods, 3, "paid-ticket-short", 10 /* 只剩 4 张票 */).code(),
                "下单本身不查票：先把单建出来，扣票发生在客户支付那一步");
        long shortOrder = orderIdOf("paid-ticket-short");
        assertEquals(before + 1, longOf("SELECT COUNT(*) FROM orders WHERE station_id=?", station),
                "没票也能先把单建出来（既有流程如此），扣票在这一步之后");
        Api shortPaid = post("/api/payments", cus, "{\"orderId\":" + shortOrder + ",\"paymentMethod\":3}");
        assertNotEquals(0, shortPaid.code(), "票不足时扣票必须失败");
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", shortOrder),
                "扣票失败 → 仍是待收款");
        assertFalse(inList("/api/delivery/orders/station-pending", tokenMgr, shortOrder),
                "票没扣成功的单永远不进站长视野（这就是「没收到钱就不派单」在水票上的形态）");
        assertEquals(4, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? AND product_id=?",
                customer, station, ticketGoods), "扣票失败不得动票");
    }

    /** 该订单是否出现在某个列表端点返回的数组里。 */
    private boolean inList(String path, String token, long orderId) {
        Api res = get(path, token);
        assertEquals(0, res.code(), path + " 应可读: " + res);
        JsonNode data = res.data();
        if (data == null || !data.isArray()) {
            return false;
        }
        for (JsonNode node : data) {
            if (node.path("id").asLong() == orderId) {
                return true;
            }
        }
        return false;
    }
}
