package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水票 / 押金流水 / 客户通知 / 客户侧异常查询 / 企业资料 —— 2026-09-16 之前全部零覆盖（矩阵 C1）。
 *
 * <p>这几组端点的共同点是「顾客自助 + 站长代办」两种身份混在同一组路径下，
 * 所以每条都同时断言"该身份能用"和"另一身份被拒"。押金那组还额外验证
 * 对账等式 1 的前提：{@code customer_deposit_account.balance == SUM(deposit_record.amount)}。</p>
 */
class TicketDepositNotificationIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("水票：站长加票/扣票留流水，顾客查自己的余额与流水")
    void ticketAccountAndRecords() {
        long station = createStation("水票站");
        long manager = createStaff("水票站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("水票客户", "ticket-openid");
        long other = createCustomer("别的水票客户", "ticket-openid-2");
        long product = createProduct("水票水", 1, "10.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 未选站时返回空列表，而不是 400（前端在选站前就是这么调的）
        Api empty = get("/api/tickets", cus);
        assertEquals(0, empty.code(), "未选站应返回空列表: " + empty);
        assertEquals(0, empty.data().size());

        Api added = post("/api/tickets/add", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":5}");
        assertEquals(0, added.code(), "站长加票: " + added);
        assertEquals(5, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, product));
        assertTrue(intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=? AND station_id=?",
                customer, station) >= 1, "加票必须留流水");

        Api mine = get("/api/tickets?stationId=" + station, cus);
        assertEquals(0, mine.code(), "顾客查自己的水票: " + mine);
        assertEquals(1, mine.data().size());

        Api staffView = get("/api/tickets/customer/" + customer + "?stationId=" + station, mgr);
        assertEquals(0, staffView.code(), "站长按客户查水票: " + staffView);
        assertNotEquals(0, get("/api/tickets/customer/" + customer, cus).code(), "顾客不得用管理端接口");

        Api consumed = post("/api/tickets/consume", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":2}");
        assertEquals(0, consumed.code(), "站长扣票: " + consumed);
        assertEquals(3, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, product));
        assertNotEquals(0, post("/api/tickets/consume", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":99}").code(),
                "余额不足必须被拒");

        assertNotEquals(0, post("/api/tickets/add", cus,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":100}").code(),
                "顾客不得自己加票");

        // 顾客线上买水票：只创建 PENDING 支付，真正入账在 confirmPayment
        // idempotencyKey 必传（v33）：无订单支付在数据库层没有任何防重
        Api purchase = post("/api/tickets/purchase", cus,
                "{\"productId\":" + product + ",\"quantity\":2,\"paymentMethod\":2,\"stationId\":" + station
                        + ",\"idempotencyKey\":\"ticket-dep-notify-1\"}");
        assertEquals(0, purchase.code(), "顾客买水票: " + purchase);
        long paymentId = purchase.data().path("paymentId").asLong();
        assertTrue(paymentId > 0);
        assertEquals(1, intOf("SELECT status FROM payment_record WHERE id=?", paymentId), "应是待收款(1)");
        assertEquals(2, intOf("SELECT ticket_qty FROM payment_record WHERE id=?", paymentId),
                "买的是几张必须落库，否则支付成功也无从入账");
        // 水票入账挂在 confirmPayment 上，买了但没付款前余额不能变（见 TicketAccountServiceImpl.purchaseTicket 注释）
        assertEquals(3, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, product), "未确认支付前不得入账水票");

        assertEquals(0, get("/api/ticket-records?stationId=" + station, cus).code(), "顾客查自己的水票流水");
        assertEquals(0, get("/api/ticket-records/customer/" + customer + "?stationId=" + station, mgr).code(),
                "站长按客户查水票流水");
        assertNotEquals(0, get("/api/ticket-records/customer/" + customer + "?stationId=" + station, cus).code(),
                "顾客不得用管理端流水接口");
        // 别人站的水票账户不对顾客可见（按 (customer, station) 隔离）
        assertEquals(0, get("/api/tickets?stationId=" + (station + 999), customerToken(other)).data().size());
    }

    @Test
    @DisplayName("押金流水：站长记账后余额等于流水之和，金额/类型/归属全服务端校验")
    void depositRecordsKeepBalanceEqualSum() {
        long station = createStation("押金站");
        long otherStation = createStation("押金他站");
        long manager = createStaff("押金站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("押金客户", "deposit-openid");
        long outsider = createCustomer("他站客户", "deposit-openid-2");
        createCustomerStationConfig(customer, station, 1);
        createCustomerStationConfig(outsider, otherStation, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        assertNotEquals(0, get("/api/deposit-records", cus).code(), "未选站时提示先选水站，而不是静默返回全部");
        assertEquals(0, get("/api/deposit-records?stationId=" + station, cus).code());

        // 9 = 人工补录押金（余额增加），站长给存量客户补录
        Api added = post("/api/deposit-records", mgr,
                "{\"customerId\":" + (int) customer + ",\"type\":9,\"amount\":100.00,\"note\":\"存量补录\"}");
        assertEquals(0, added.code(), "站长补录押金: " + added);
        assertEquals(0, new java.math.BigDecimal("100.00")
                        .compareTo(decimalOf("SELECT balance FROM customer_deposit_account WHERE customer_id=? "
                                + "AND station_id=?", customer, station)),
                "等式1：余额必须等于流水之和");
        assertEquals(station, longOf("SELECT station_id FROM deposit_record WHERE customer_id=? ORDER BY id DESC LIMIT 1",
                customer), "流水归属强制绑定登录站");

        Api list = get("/api/deposit-records?stationId=" + station, cus);
        assertEquals(0, list.code());
        assertEquals(1, list.data().size());
        assertEquals(0, get("/api/deposit-records/customer/" + customer + "?stationId=" + station, mgr).code());
        assertNotEquals(0, get("/api/deposit-records/customer/" + customer + "?stationId=" + station, cus).code(),
                "顾客不得用管理端接口");

        // [AQ-004] 金额与类型必须服务端校验（amount 传负数会反向放大余额）
        assertNotEquals(0, post("/api/deposit-records", mgr,
                "{\"customerId\":" + (int) customer + ",\"type\":9,\"amount\":-50.00}").code(), "金额必须 > 0");
        assertNotEquals(0, post("/api/deposit-records", mgr,
                "{\"customerId\":" + (int) customer + ",\"type\":99,\"amount\":10.00}").code(), "非法类型必须被拒");
        // [AQ-012] 不得替别站客户记账
        assertNotEquals(0, post("/api/deposit-records", mgr,
                "{\"customerId\":" + (int) outsider + ",\"type\":9,\"amount\":10.00}").code(),
                "不得替别站客户记押金");
        assertEquals(0, intOf("SELECT COUNT(*) FROM deposit_record WHERE customer_id=?", outsider));
    }

    @Test
    @DisplayName("客户通知：列表/未读/计数/单条已读/全部已读，且只能动自己的")
    void customerNotificationsAreOwned() {
        long customer = createCustomer("通知客户", "notify-openid");
        long other = createCustomer("别人", "notify-openid-2");
        long orderId = createOrder(customer, createAddress(customer, "通知地址"),
                createStation("通知站"), createProduct("通知水", 1, "10.00", "30.00", 0, "0.00"), 1, 1);

        long mine = insert("INSERT INTO customer_notification(customer_id, type, title, content, related_order_id, is_read) "
                + "VALUES (?,?,?,?,?,0)", customer, "ORDER_REJECT", "订单被拒", "详情", orderId);
        long theirs = insert("INSERT INTO customer_notification(customer_id, type, title, content, is_read) "
                + "VALUES (?,?,?,?,0)", other, "ORDER_REJECT", "别人的通知", "详情");

        String cus = customerToken(customer);

        Api list = get("/api/customer/notifications", cus);
        assertEquals(0, list.code(), "通知列表: " + list);
        assertEquals(1, list.data().size(), "只看得到自己的通知");
        assertEquals(0, get("/api/customer/notifications?limit=1", cus).code());
        assertEquals(0, get("/api/customer/notifications/unread", cus).code());
        assertEquals(1, get("/api/customer/notifications/unread-count", cus).data().asInt());

        // [AQ-035] 标记他人通知 → 必须拒绝（旧实现传任意 id 就能改别人）
        assertNotEquals(0, post("/api/customer/notifications/" + theirs + "/read", cus, null).code(),
                "不得把别人的通知标记已读");
        assertEquals(0, intOf("SELECT is_read FROM customer_notification WHERE id=?", theirs), "别人那条不该被改动");

        assertEquals(0, post("/api/customer/notifications/" + mine + "/read", cus, null).code());
        assertEquals(1, intOf("SELECT is_read FROM customer_notification WHERE id=?", mine));
        assertEquals(0, get("/api/customer/notifications/unread-count", cus).data().asInt());

        // 全部已读之后，新来的通知仍能正确计数
        insert("INSERT INTO customer_notification(customer_id, type, title, content, is_read) VALUES (?,?,?,?,0)",
                customer, "ORDER_REJECT", "又来一条", "详情");
        assertEquals(1, get("/api/customer/notifications/unread-count", cus).data().asInt());
        assertEquals(0, post("/api/customer/notifications/read-all", cus, null).code());
        assertEquals(0, get("/api/customer/notifications/unread-count", cus).data().asInt());
    }

    @Test
    @DisplayName("客户侧桶异常查询：只看自己的，且必须限定水站")
    void customerExceptionQueries() {
        long station = createStation("异常站");
        long customer = createCustomer("异常客户", "cex-openid");
        long other = createCustomer("别人", "cex-openid-2");
        long product = createProduct("异常水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "异常地址");
        long orderId = createOrderFull(customer, address, station, product, 4, 2, 2,
                "10.00", "30.00", "40.00", true, 2);

        long mine = insert("INSERT INTO order_barrel_exception(order_id, customer_id, station_id, delivery_qty, "
                        + "return_qty, discrepancy, category, status) VALUES (?,?,?,2,0,2,'RETURN_SHORT','STAFF_RECORDED')",
                orderId, customer, station);
        long theirs = insert("INSERT INTO order_barrel_exception(order_id, customer_id, station_id, delivery_qty, "
                        + "return_qty, discrepancy, category, status) VALUES (?,?,?,2,0,2,'RETURN_SHORT','STAFF_RECORDED')",
                orderId, other, station);

        String cus = customerToken(customer);

        Api page = get("/api/customer/exceptions?stationId=" + station, cus);
        assertEquals(0, page.code(), "客户侧异常分页: " + page);
        assertEquals(0, get("/api/customer/exceptions?stationId=" + station + "&page=1&size=5", cus).code());
        // [2026-09-18] /list（"兼容：部分调用方按裸数组处理"）已按死端点评估删除，
        // 同一件事改在分页端点的 records 上断言；它的 404 断言在 ManagerOrderControllerRemovedIntegrationTest。
        assertEquals(1, page.data().path("records").size(), "只看得到自己的异常");

        assertEquals(0, get("/api/customer/exceptions/" + mine, cus).code());
        assertNotEquals(0, get("/api/customer/exceptions/" + theirs, cus).code(), "不得读别人的异常单");
    }

    @Test
    @DisplayName("企业资料：按登录客户覆盖，看不到也改不了别人的")
    void companyInfoIsPerCustomer() {
        long customer = createCustomer("企业客户", "company-openid");
        long other = createCustomer("另一家企业", "company-openid-2");
        String cus = customerToken(customer);
        String otherTok = customerToken(other);

        Api initial = get("/api/company-info", cus);
        assertEquals(0, initial.code(), "没填过应返回空而不是报错: " + initial);

        Api saved = post("/api/company-info", cus,
                "{\"companyName\":\"甲供水公司\",\"contactPerson\":\"张三\",\"contactPhone\":\"13800000000\",\"dueDays\":30}");
        assertEquals(0, saved.code(), "保存企业资料: " + saved);
        assertEquals("甲供水公司", saved.data().path("companyName").asText());

        // 请求体里塞别人 customerId 也必须被登录态覆盖
        Api overwritten = post("/api/company-info", cus,
                "{\"customerId\":" + other + ",\"companyName\":\"甲供水公司改\"}");
        assertEquals(0, overwritten.code());
        assertEquals(customer, longOf("SELECT customer_id FROM company_info WHERE company_name='甲供水公司改'"),
                "customerId 必须以登录态为准");
        assertEquals(0, intOf("SELECT COUNT(*) FROM company_info WHERE customer_id=?", other));

        assertEquals(0, put("/api/company-info", cus, "{\"companyName\":\"甲供水公司二代\"}").code());
        assertEquals("甲供水公司二代",
                get("/api/company-info", cus).data().path("companyName").asText());

        Api others = get("/api/company-info", otherTok);
        assertEquals(0, others.code());
        assertTrue(others.data().isNull() || others.data().isMissingNode(), "别人不该看到我的企业资料");
    }
}
