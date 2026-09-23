package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 遗留空白（矩阵 C1/「仍待决策」旁的那批）：配送端各 Tab 的列表端点、
 * 水站自助查询、登录态自助（me / update-profile / refresh 轮换 / logout）、
 * 未绑站站长建站、桶流水读取、以及站长手工退款。
 *
 * <p>列表端点值得单独钉一遍：它们是配送端每个 Tab 的<b>唯一数据来源</b>，
 * 一旦某个 mapper 的 JOIN 写错，界面就是"空白页"而不是报错 —— 也正因如此，
 * 这批端点长期零覆盖却没人发现。</p>
 *
 * <p>⚠️ 2026-09-19：本类**显式**把 dev-login 打开。此前它写的是"由 application-local.yml
 * 提供"，而那个文件是 gitignore 的、CI 上没有，CI 又把 {@code DEV_LOGIN_ENABLED} 设成
 * {@code false}（那是**有意**的：CI 要贴近生产）→ {@code DevLoginController} 带
 * {@code @ConditionalOnProperty(havingValue="true")}，端点根本不存在，
 * 于是 {@code loginSelfServiceLifecycle} 在 CI 上必然红（实测 {@code code=404
 * 接口不存在：api/auth/dev-login}）。用例依赖什么就自己声明什么，别依赖开发机上那份不入库的配置。</p>
 */
@TestPropertySource(properties = "app.dev-login-enabled=true")
class DeliveryConsoleAndSelfServiceIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("配送端列表端点：站长/配送员都能读到，顾客一律被拒")
    void deliveryConsoleListsRespondForStaff() {
        long station = createStation("配送控制台站");
        long manager = createStaff("控制台站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("控制台配送员", "DELIVERY", station, 1);
        long customer = createCustomer("控制台客户", "console-openid");
        long product = createProduct("控制台水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "控制台地址");
        createInventory(station, product, 20);

        // 一张待接单（已分配给我）和一张配送中的单，用来确认列表不是"恒空"
        long assigned = createOrderFull(customer, address, station, product, 1, 1, 2,
                "10.00", "30.00", "40.00", true, 1);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", delivery, assigned);
        long delivering = createOrderFull(customer, address, station, product, 2, 2, 2,
                "10.00", "30.00", "40.00", true, 1);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", delivery, delivering);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String del = staffToken(delivery, "DELIVERY", station);
        String cus = customerToken(customer);

        // 两种角色都允许的端点
        String[] bothRoles = {
                "/api/delivery/orders/pending",
                "/api/delivery/orders/assigned-to-me",
                "/api/delivery/orders/delivering",
                "/api/delivery/orders/completed-today",
                "/api/delivery/orders/delivered-unpaid",
                "/api/delivery/stats/today",
                "/api/delivery/history",
                "/api/delivery/transfers",
                "/api/delivery/transfers/incoming",
                "/api/delivery/barrel-records"
        };
        for (String path : bothRoles) {
            Api m = get(path, mgr);
            assertEquals(0, m.code(), path + " 站长: " + m);
            Api d = get(path, del);
            assertEquals(0, d.code(), path + " 配送员: " + d);
            assertNotEquals(0, get(path, cus).code(), path + " 顾客必须被拒");
        }

        // 站长专属的端点
        String[] managerOnly = {
                "/api/delivery/orders/station-pending",
                "/api/delivery/orders/station-delivering",
                "/api/delivery/orders/station-completed",
                "/api/delivery/orders/station-transfer",
                "/api/delivery/orders/station-return",
                // [2026-09-18] station-exception 已按死端点评估删除（名字叫"异常"、实际返回取消单），
                // 它的 404 断言在 ManagerOrderControllerRemovedIntegrationTest 里。
                "/api/delivery/orders/pending-approvals",
                "/api/delivery/orders/pool",
                "/api/delivery/orders/dispatch-tracking",
                "/api/delivery/orders/directed-returns",
                "/api/delivery/orders/directed-incoming",
                "/api/delivery/orders/station-pending?page=1&size=10"
        };
        for (String path : managerOnly) {
            assertEquals(0, get(path, mgr).code(), path + " 站长应可读");
            assertNotEquals(0, get(path, del).code(), path + " 配送员不该读站长视图");
        }

        // 列表内容要真的按"我的"过滤，而不是把全站单都倒出来
        Api mine = get("/api/delivery/orders/assigned-to-me", del);
        assertEquals(0, mine.code());
        assertTrue(mine.data().toString().contains("\"id\":" + assigned), "待接单列表应含分配给我的单");
        assertTrue(get("/api/delivery/orders/delivering", del).data().toString().contains("\"id\":" + delivering),
                "配送中列表应含我配送中的单");
    }

    @Test
    @DisplayName("水站自助：员工取本站，公开接口免登录，顾客被拒")
    void stationSelfServiceEndpoints() {
        long station = createStation("自助站");
        long manager = createStaff("自助站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("自助配送员", "DELIVERY", station, 1);
        long customer = createCustomer("自助客户", "station-openid");

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String del = staffToken(delivery, "DELIVERY", station);
        String cus = customerToken(customer);

        assertEquals(0, get("/api/stations", mgr).code(), "站长列水站");
        assertEquals(0, get("/api/stations/" + station, mgr).code());
        assertEquals(0, get("/api/stations/mine", mgr).code(), "站长取本站");
        assertEquals(0, get("/api/station/mine", del).code(), "配送员取本站（单数前缀同样可用）");
        assertEquals(0, get("/api/stations/mine/list", mgr).code(), "站长名下所有水站");
        // 双前缀是历史兼容：两种写法必须都活着
        assertEquals(0, get("/api/station/" + station, mgr).code(), "单数前缀");

        assertNotEquals(0, get("/api/stations", cus).code(), "顾客不得列水站");
        assertNotEquals(0, get("/api/stations/mine", cus).code(),
                "顾客取员工版'我的水站'必须被拒（历史上顾客端误用过它，导致取水站电话为空）");
        assertNotEquals(0, get("/api/stations/mine/list", cus).code());

        // 公开接口：未登录也要能用（选站页与取水站电话就靠它）
        assertEquals(0, get("/api/stations/public", null).code(), "选站页免登录");
        assertEquals(0, get("/api/station/public", null).code());
        assertEquals(0, get("/api/stations/search?keyword=x", null).code());
        Api phone = get("/api/stations/" + station + "/public-phone", null);
        assertEquals(0, phone.code(), "取水站公开电话: " + phone);
        assertEquals(0, get("/api/station/" + station + "/public-phone", null).code());
    }

    @Test
    @DisplayName("登录态自助：me / 改资料 / refresh 轮换 / logout 后旧 refresh 失效")
    void loginSelfServiceLifecycle() throws InterruptedException {
        // dev-login 由本类的 @TestPropertySource 显式打开（**不能**再依赖 application-local.yml：
        // 那份配置已 gitignore、CI 上不存在，而 CI 的 DEV_LOGIN_ENABLED 是有意设成 false 的），
        // 它能一次给齐 access + refresh，正是要验证 refresh 轮换所需要的
        Api login = post("/api/auth/dev-login", null, "{\"role\":\"CUSTOMER\",\"openid\":\"p1-self-openid\"}");
        assertEquals(0, login.code(), "开发登录: " + login);
        String access = login.data().path("accessToken").asText();
        String refresh = login.data().path("refreshToken").asText();
        long customerId = login.data().path("customerId").asLong();
        assertTrue(access.length() > 10 && refresh.length() > 10);

        Api me = get("/api/auth/me", access);
        assertEquals(0, me.code(), "/me: " + me);
        assertEquals("customer", me.data().path("role").asText());
        assertEquals(customerId, me.data().path("customerId").asLong());

        Api updated = post("/api/auth/update-profile", access,
                "{\"nickname\":\"改过的昵称\",\"phone\":\"13900000001\"}");
        assertEquals(0, updated.code(), "改资料: " + updated);
        assertEquals("改过的昵称",
                jdbc.queryForObject("SELECT name FROM customer WHERE id=?", String.class, customerId));
        assertEquals("改过的昵称", get("/api/auth/me", access).data().path("nickname").asText());

        Api refreshed = post("/api/auth/refresh", null, "{\"refreshToken\":\"" + refresh + "\"}");
        assertEquals(0, refreshed.code(), "刷新令牌: " + refreshed);
        String access2 = refreshed.data().path("accessToken").asText();
        String refresh2 = refreshed.data().path("refreshToken").asText();
        assertEquals(0, get("/api/auth/me", access2).code(), "新 access 可用");

        // 轮换语义要跨秒才可验证：generateRefreshToken 用**秒级** iat/exp 且没有 jti，
        // 同一秒内签发的两个 refresh token 完全一样（此时"旧 token 失效"实际不成立）。
        // 这里睡过一秒再刷，验证的是真实轮换；那段同秒局限如实写在注释里，不当作缺陷。
        Thread.sleep(1100);
        Api refreshed2 = post("/api/auth/refresh", null, "{\"refreshToken\":\"" + refresh2 + "\"}");
        assertEquals(0, refreshed2.code(), "第二次刷新: " + refreshed2);
        String refresh3 = refreshed2.data().path("refreshToken").asText();
        assertNotEquals(refresh2, refresh3, "跨秒后 refresh token 必须轮换");
        assertNotEquals(0, post("/api/auth/refresh", null, "{\"refreshToken\":\"" + refresh2 + "\"}").code(),
                "轮换后上一个 refresh 必须失效（防重放）");

        assertEquals(0, post("/api/auth/logout", access2, null).code(), "登出");
        assertNotEquals(0, post("/api/auth/refresh", null, "{\"refreshToken\":\"" + refresh3 + "\"}").code(),
                "登出后 refresh 必须失效");
        // access token 是自包含 JWT，登出不会让它立刻失效（无黑名单）—— 如实记录，不当成缺陷
        assertEquals(0, get("/api/auth/me", access2).code(),
                "access token 仍有效：本项目没有 access 级黑名单，登出只清 refresh 记录");
    }

    @Test
    @DisplayName("未绑站站长建站：建完绑定自己；已绑站的再建必被拒")
    void createStationForUnboundManager() {
        long manager = createStaff("新站长", "STATION_MANAGER", null, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", null);

        // latitude/longitude 是建站页**一直在发**的字段（wx.chooseLocation 选点）。
        // [2026-09-17 / v34] 此前 DTO 没有这两个字段 → Jackson 静默忽略未知字段 →
        // 站长选的点凭空消失（不报错、不进日志）。这条断言就是它的回归口径。
        Api created = post("/api/auth/create-station", mgr,
                "{\"name\":\"新开的水站\",\"phone\":\"13800000002\",\"province\":\"江苏省\","
                        + "\"city\":\"南京市\",\"district\":\"玄武区\",\"address\":\"xx路1号\","
                        + "\"latitude\":32.060300,\"longitude\":118.796900}");
        assertEquals(0, created.code(), "建站: " + created);
        long newStation = longOf("SELECT station_id FROM staff WHERE id=?", manager);
        assertTrue(newStation > 0, "建站后站长必须绑定到新水站");
        assertEquals("新开的水站",
                jdbc.queryForObject("SELECT name FROM station WHERE id=?", String.class, newStation));
        assertEquals(1, intOf("SELECT COUNT(*) FROM station WHERE id=? AND lat=32.060300 AND lng=118.796900",
                        newStation),
                "建站时选的坐标必须落库（此前被 Jackson 静默丢弃，配送范围将无从判断）");

        assertNotEquals(0, post("/api/auth/create-station", mgr, "{\"name\":\"再建一个\"}").code(),
                "已绑定水站不得重复建站");
        assertEquals(1, intOf("SELECT COUNT(*) FROM station"));

        // 顾客身份不能建站
        long customer = createCustomer("建站客户", "createstation-openid");
        assertNotEquals(0, post("/api/auth/create-station", customerToken(customer), "{\"name\":\"客户建的站\"}").code());
    }

    @Test
    @DisplayName("桶流水与退桶预检：顾客只读自己的，站长能看全站")
    void barrelRecordsAndReturnPreview() {
        long station = createStation("桶流水站");
        long manager = createStaff("桶流水站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("桶流水客户", "barrelrec-openid");
        long product = createProduct("桶流水水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 20);
        // 客户手上有 2 个桶权益（走真实批次表，保证与桶账口径一致）
        createBarrelLot("lot-rec-1", customer, station, product, "30.00", 2, 2);
        createBarrelAsset(customer, station, product, 2, "60.00");
        createDepositBalance(customer, station, "60.00");
        insert("INSERT INTO barrel_record(customer_id, station_id, product_id, type, quantity, deposit_refund, note) "
                + "VALUES (?,?,?,1,2,60.00,'首购押金')", customer, station, product);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        Api records = get("/api/barrels/records?stationId=" + station, cus);
        assertEquals(0, records.code(), "顾客桶流水: " + records);
        assertEquals(1, records.data().size());

        Api all = get("/api/barrels/all-records?limit=50", mgr);
        assertEquals(0, all.code(), "站长全站桶流水: " + all);
        assertNotEquals(0, get("/api/barrels/all-records", cus).code(), "顾客不得读全站桶流水");

        Api preview = get("/api/barrels/return/preview?productId=" + product
                + "&quantity=1&stationId=" + station, cus);
        assertEquals(0, preview.code(), "退桶预检: " + preview);
        assertEquals(2, preview.data().path("rightQty").asInt(), "预检要回权益数");
        assertEquals(2, preview.data().path("occupiedQty").asInt(), "占用 = 权益 + over");
        assertEquals(30.00, preview.data().path("refundAmount").asDouble(), 0.01, "可退金额 = 押金单价 × 数量");
        assertTrue(preview.data().path("blocked").isMissingNode() || !preview.data().path("blocked").asBoolean(),
                "没欠桶且数量不超权益时不应被标记 blocked");

        // 预检本身不拒绝（恒 code=0），而是用 blocked=true 告诉前端 ——
        // 真正"拒绝"发生在 POST /api/barrels/return（控制器读 blocked 直接拒、不建申请单）。
        Api tooMany = get("/api/barrels/return/preview?productId=" + product
                + "&quantity=99&stationId=" + station, cus);
        assertEquals(0, tooMany.code(), "预检恒 200/code=0，用 blocked 表达拒绝: " + tooMany);
        assertTrue(tooMany.data().path("blocked").asBoolean(), "超量预检必须标记 blocked");
        assertEquals(0, tooMany.data().path("refundAmount").asDouble(), 0.01, "被拦时退款金额应为 0");
    }

    @Test
    @DisplayName("站长手工退款：流水转已退款，订单支付状态只前进（不得倒滚成未支付）")
    void adHocPaymentRefundKeepsPaymentStatusForward() {
        long station = createStation("退款站");
        long manager = createStaff("退款站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("退款客户", "refund-openid");
        long product = createProduct("退款水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "退款地址");
        long orderId = createOrderFull(customer, address, station, product, 4, 2, 2,
                "10.00", "30.00", "40.00", true, 2);
        long paymentId = createPaymentRecord(orderId, customer, station, "40.00", 2, 2);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        assertNotEquals(0, put("/api/payments/" + paymentId + "/refund", cus, "{\"note\":\"越权\"}").code(),
                "顾客不得退款");

        Api refunded = put("/api/payments/" + paymentId + "/refund", mgr, "{\"note\":\"客户投诉多收\"}");
        assertEquals(0, refunded.code(), "退款: " + refunded);
        assertEquals(3, intOf("SELECT status FROM payment_record WHERE id=?", paymentId), "流水应为已退款(3)");
        // 回归锁：这里曾是 UNPAID(0)。钱退了却显示"从未付款"，对账/客服都会读到假的资金状态，
        // 也违反「支付状态只前进、不倒滚」（[AQ-022] 在 refundOrder 里已经修过同款问题）。
        assertEquals(3, intOf("SELECT payment_status FROM orders WHERE id=?", orderId),
                "订单支付状态应为已退款(3)，不得倒滚成未支付(0)");

        assertNotEquals(0, put("/api/payments/" + paymentId + "/refund", mgr, "{\"note\":\"重复退款\"}").code(),
                "只有已付款记录可退款，重复退款必须失败");
    }

    @Test
    @DisplayName("取消订单退款：原支付流水必须转已退款，且对账等式2 不得残留差异")
    void cancelledOrderMarksOriginalPaymentRecordRefunded() {
        long station = createStation("取消退款站");
        long manager = createStaff("取消退款站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("取消退款客户", "cancelrefund-openid");
        long product = createProduct("取消退款水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 10);
        long address = createAddress(customer, "取消退款地址");
        // 已付款的待配送单：顾客可自助取消，取消走 refundOrder（完整退款链）
        long orderId = createOrderFull(customer, address, station, product, 1, 2, 2,
                "10.00", "30.00", "40.00", true, 1);
        long paymentId = createPaymentRecord(orderId, customer, station, "40.00", 2, 2);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        Api cancelled = put("/api/orders/" + orderId + "/customer-cancel", cus, null);
        assertEquals(0, cancelled.code(), "顾客取消: " + cancelled);
        assertEquals(5, intOf("SELECT status FROM orders WHERE id=?", orderId), "订单应已取消(5)");
        assertEquals(3, intOf("SELECT payment_status FROM orders WHERE id=?", orderId), "订单应已退款(3)");

        // 回归锁（2026-09-16）：原支付记录必须转「已退款(3)」。此前
        // PaymentRecordMapper.updateStatusIf 的参数顺序在退款路径被写反（(PAID, REFUNDED)），
        // SQL 恒命中 0 行 —— 钱退了，流水却一直显示"已付款"。
        assertEquals(3, intOf("SELECT status FROM payment_record WHERE id=?", paymentId),
                "原支付流水必须标记为已退款(3)");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND amount < 0 AND status = 3",
                orderId), "应另有一条负金额退款流水");

        // 对账等式2（ReconciliationService:193-199 同款口径，这里是直接查库复现）：
        // p2b = 订单非已付款却存在 status=2 的流水 —— 原流水没翻转时这一项立刻非 0。
        // 注意站长端 /api/manager/reconciliation 的 checks 只暴露 SE1/SE3/SE4/SE5/SE6，
        // 等式2 只在每日 03:00 的 dailyReconcile 里算，所以这里自己查。
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders o WHERE o.payment_status <> 2 "
                        + "AND EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 2)"),
                "等式2 p2b：已退款订单不该还挂着 status=2 的流水");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders o WHERE o.payment_status = 3 "
                        + "AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 3)"),
                "等式2 p2d：已退款订单必须有 status=3 的流水");

        Api rec = get("/api/manager/reconciliation", mgr);
        assertEquals(0, rec.code(), "对账: " + rec);
        assertEquals(0, rec.data().path("totalDiff").asInt(), "退款后总差异应为 0");
    }
}
