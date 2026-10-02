package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-47（台账 {@code docs/audit/项目健康度台账.md} §4 P2）· <b>四条资金只读端点的零覆盖</b>。
 *
 * <p>补本用例之前，{@code GET /api/payments/by-order}、{@code /by-customer}、
 * {@code /customer/{customerId}}、{@code /}（根列表）与 {@code /all} 在
 * {@code src/test/**} 里<b>一次都没有被调用过</b> —— 而 §3.3 曾据此写下"其余高风险端点全有用例"，
 * 被 F-47 证伪。病根是旧 D6.1 只"抽查 3 条路径"，抽查看不出全量的洞。</p>
 *
 * <p><b>写法按 F-14 三件套</b>（AGENTS §8.22：只读端点的真实故障形态是界面空白，不是崩溃）：</p>
 * <ol>
 *   <li><b>正例</b>：{@code code=0} 且逐条与库对账（金额取自 {@code payment_record} 那一行）；</li>
 *   <li><b>失败 / 空态可区分</b>：订单不存在、他站订单、无记录是三种不同答案
 *       （{@code code=1+文案} vs {@code code=0+空数组}），不许互相顶替；</li>
 *   <li><b>越权</b>：站别/身份只认登录态，请求参数里的 {@code stationId}/{@code customerId} 不生效。</li>
 * </ol>
 *
 * <p>断言语义一律看 body {@code code}（业务错误 HTTP 仍是 200），只有未认证才是真 401。</p>
 */
@DisplayName("F-47 · 资金只读零覆盖端点（/api/payments by-order / by-customer / customer/{id} / 列表）")
class PaymentReadEndpointsIntegrationTest extends AbstractIntegrationTest {

    /* ==================================================================
     * 端点 1：GET /api/payments/by-order?orderId=（PaymentController#listByOrderId）
     * 站长查订单流水；站别按**履约站**判定（requireOrderStation，与退款入口同源）。
     * ================================================================== */

    @Test
    @DisplayName("by-order 正例 + 按履约站判权：本站单能查且金额与库对账；跨站单只认履约站")
    void byOrderReturnsFlowScopedByFulfillmentStation() {
        long stationA = createStation("F47 甲站");
        long stationB = createStation("F47 乙履约站");
        long managerA = createStaff("F47 甲站长", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("F47 乙站长", "STATION_MANAGER", stationB, 1);
        long customer = createCustomer("F47 甲客户", "f47-byorder-openid");
        long addr = createAddress(customer, "F47 小区 1 号");
        long product = createProduct("F47 水", 1, "20.00", "30.00", 0, "0.00");

        // ① 站内单：履约站 = 归属站 = 甲站
        long ownOrder = createOrderFull(customer, addr, stationA, product, 1, 2, 2,
                "40.00", "0.00", "40.00", false, 0);
        long ownFlow = createPaymentRecord(ownOrder, customer, stationA, "40.00", 2, 2);

        Api ok = get("/api/payments/by-order?orderId=" + ownOrder, staffToken(managerA, "STATION_MANAGER", stationA));
        assertEquals(0, ok.code(), "本站订单的流水应能查到：" + ok);
        JsonNode row = findById(ok.data(), ownFlow);
        assertNotNull(row, "返回里必须含夹具那条流水（JOIN 写错时这里最先红，§8.22）：" + ok.data());
        assertEquals(0, new BigDecimal("40.00").compareTo(row.path("amount").decimalValue()),
                "金额必须是库里那一行，而不是 0/null：" + row);
        // 派生文案由后端下发（前端禁建映射表），这里钉"下发了且非空"
        assertFalse(row.path("statusText").asText().isBlank(), "statusText 必须由后端下发：" + row);
        assertFalse(row.path("methodText").asText().isBlank(), "methodText 必须由后端下发：" + row);

        // ② 跨站单：归属站甲、履约站乙 —— 按履约站判权（PaymentController.requireOrderStation 的口径）
        long crossOrder = createOrderCrossStation(customer, addr, stationA, stationB, product,
                1, 2, 2, "40.00", "0.00", "40.00");
        long crossFlow = createPaymentRecord(crossOrder, customer, stationB, "40.00", 2, 2);

        Api byDelivery = get("/api/payments/by-order?orderId=" + crossOrder,
                staffToken(managerB, "STATION_MANAGER", stationB));
        assertEquals(0, byDelivery.code(), "履约站应能查自己履约的单：" + byDelivery);
        assertNotNull(findById(byDelivery.data(), crossFlow), "履约站拿到的必须含这条流水：" + byDelivery.data());

        Api byOwner = get("/api/payments/by-order?orderId=" + crossOrder,
                staffToken(managerA, "STATION_MANAGER", stationA));
        assertEquals(1, byOwner.code(), "归属站查他站履约的单必须被拒（判履约站）：" + byOwner);
        assertTrue(byOwner.message().contains("无权操作他站订单"), "拒绝文案要指得出原因：" + byOwner);
        assertTrue(byOwner.data().isNull(), "被拒时不得下发 data（否则与空列表混淆）：" + byOwner);

        // ③ 单不存在 = 明确失败，不许退化成 code=0 空数组（"查不到"与"没这条单"必须分得开）
        Api ghost = get("/api/payments/by-order?orderId=999999", staffToken(managerA, "STATION_MANAGER", stationA));
        assertEquals(1, ghost.code(), "订单不存在必须是失败而非空数据：" + ghost);
        assertTrue(ghost.message().contains("订单不存在"), "文案要指得出是订单不存在：" + ghost);
    }

    @Test
    @DisplayName("by-order 角色与认证：配送员被拒、未认证真 401")
    void byOrderGuardsRoleAndAuth() {
        long station = createStation("F47 角色站");
        long manager = createStaff("F47 角色站长", "STATION_MANAGER", station, 1);
        long driver = createStaff("F47 配送员", "DELIVERY", station, 1);
        long customer = createCustomer("F47 角色客户", "f47-byorder-role");
        long addr = createAddress(customer, "F47 角色小区");
        long product = createProduct("F47 角色水", 1, "20.00", "30.00", 0, "0.00");
        long order = createOrderFull(customer, addr, station, product, 1, 2, 2, "40.00", "0.00", "40.00", false, 0);

        Api asDriver = get("/api/payments/by-order?orderId=" + order, staffToken(driver, "DELIVERY", station));
        assertEquals(1, asDriver.code(), "配送员不是站长，必须被拒：" + asDriver);
        assertTrue(asDriver.message().contains("权限不足"), "拒绝要给权限文案：" + asDriver);

        assertEquals(401, get("/api/payments/by-order?orderId=" + order, null).status(),
                "无令牌应被 AuthInterceptor 拒成真 401");
    }

    /* ==================================================================
     * 端点 2：GET /api/payments/by-customer（PaymentController#listByCustomerId）
     * 顾客查自己的流水：身份只认 AuthContext，无 @RequireRole 注解。
     * ================================================================== */

    @Test
    @DisplayName("by-customer 自助隔离：只回自己的流水，参数里塞别人无效，无记录是空数组不是失败")
    void byCustomerIsSelfScopedFromTokenOnly() {
        long stationA = createStation("F47 自助甲站");
        long stationB = createStation("F47 自助乙站");
        long alice = createCustomer("F47 自助甲", "f47-bycust-alice");
        long bob = createCustomer("F47 自助乙", "f47-bycust-bob");
        long addrA = createAddress(alice, "F47 甲小区");
        long addrB = createAddress(bob, "F47 乙小区");
        long product = createProduct("F47 自助水", 1, "20.00", "30.00", 0, "0.00");

        long aliceOrder = createOrderFull(alice, addrA, stationA, product, 1, 2, 2, "40.00", "0.00", "40.00", false, 0);
        long aliceFlow = createPaymentRecord(aliceOrder, alice, stationA, "40.00", 2, 2);
        long bobOrder = createOrderFull(bob, addrB, stationB, product, 1, 2, 2, "55.00", "0.00", "55.00", false, 0);
        long bobFlow = createPaymentRecord(bobOrder, bob, stationB, "55.00", 2, 2);

        Api mine = get("/api/payments/by-customer", customerToken(alice));
        assertEquals(0, mine.code(), "顾客查自己的流水应成功：" + mine);
        assertNotNull(findById(mine.data(), aliceFlow), "必须含自己的那条：" + mine.data());
        assertNull(findById(mine.data(), bobFlow), "绝不含别人的流水（漏过滤就是一条可枚举他人资金的越权链）：" + mine.data());

        // 请求参数里塞 customerId 一律不认（身份取自 AuthContext）
        Api spoof = get("/api/payments/by-customer?customerId=" + bob, customerToken(alice));
        assertEquals(0, spoof.code(), "带无关参数不该报错：" + spoof);
        assertNull(findById(spoof.data(), bobFlow), "端点不读 customerId 参数 —— 读了就是越权：" + spoof.data());

        // 员工不是客户 → 明拒，不许回 code=0 空数据
        Api asStaff = get("/api/payments/by-customer", staffToken(
                createStaff("F47 自助站长", "STATION_MANAGER", stationA, 1), "STATION_MANAGER", stationA));
        assertEquals(1, asStaff.code(), "员工调顾客自助端点必须被拒：" + asStaff);
        assertEquals("仅客户可访问此接口", asStaff.message(), "拒绝文案要具体：" + asStaff);
        assertTrue(asStaff.data().isNull(), "被拒时不得下发 data：" + asStaff);

        // 「确实没有」= 合法空态，必须与"失败"分得开（§8.22）
        long lonely = createCustomer("F47 无流水客户", "f47-bycust-lonely");
        Api empty = get("/api/payments/by-customer", customerToken(lonely));
        assertEquals(0, empty.code(), "没流水是合法状态不是错误：" + empty);
        assertTrue(empty.data().isArray() && empty.data().size() == 0,
                "空态必须是空数组（既非 data:null 也非 code=1）：" + empty.data());

        assertEquals(401, get("/api/payments/by-customer", null).status(), "无令牌应是真 401");
    }

    /* ==================================================================
     * 端点 3：GET /api/payments/customer/{customerId}?stationId=（#listByCustomerIdForStaff）
     * 站长查指定客户流水；[AQ-023] 强制用登录站，忽略客户端传入的 stationId。
     * ================================================================== */

    @Test
    @DisplayName("customer/{id} 强制登录站：传入的 stationId 不生效，他站只能拿到空数组")
    void staffCustomerLookupIgnoresRequestedStationId() {
        long stationA = createStation("F47 指定甲站");
        long stationB = createStation("F47 指定乙站");
        long managerA = createStaff("F47 指定甲站长", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("F47 指定乙站长", "STATION_MANAGER", stationB, 1);
        long driver = createStaff("F47 指定配送员", "DELIVERY", stationA, 1);
        long customer = createCustomer("F47 指定客户", "f47-cust-staff");
        long addr = createAddress(customer, "F47 指定小区");
        long product = createProduct("F47 指定水", 1, "20.00", "30.00", 0, "0.00");

        long order = createOrderFull(customer, addr, stationA, product, 1, 2, 2, "40.00", "0.00", "40.00", false, 0);
        long flow = createPaymentRecord(order, customer, stationA, "40.00", 2, 2);

        // ① 本站站长：能查到；顺带传一个别人的 stationId，必须被忽略（还是回自己的）
        Api ok = get("/api/payments/customer/" + customer + "?stationId=999999",
                staffToken(managerA, "STATION_MANAGER", stationA));
        assertEquals(0, ok.code(), "本站站长查本站客户流水应成功：" + ok);
        assertNotNull(findById(ok.data(), flow), "应含本站那条流水：" + ok.data());

        // ② 他站站长即使把 stationId 明写成甲站，也只能拿到**自己站**的（空）——
        //    AQ-023 钉的正是"参数不生效"；若参数生效，这里会回出甲站的流水 = 跨站泄露
        Api cross = get("/api/payments/customer/" + customer + "?stationId=" + stationA,
                staffToken(managerB, "STATION_MANAGER", stationB));
        assertEquals(0, cross.code(), "他站查询是空态不是错误（列表按登录站过滤）：" + cross);
        assertTrue(cross.data().isArray() && cross.data().size() == 0,
                "传入的 stationId 不得生效 —— 回出任何行都是跨站泄露：" + cross.data());
        assertNull(findById(cross.data(), flow), "他站不得看到本站流水：" + cross.data());

        // ③ 配送员不是站长 → 被拒（@RequireRole STATION_MANAGER）
        Api asDriver = get("/api/payments/customer/" + customer + "?stationId=" + stationA,
                staffToken(driver, "DELIVERY", stationA));
        assertEquals(1, asDriver.code(), "配送员不得查客户资金档案：" + asDriver);
        assertTrue(asDriver.message().contains("权限不足"), "拒绝要给权限文案：" + asDriver);

        // ④ 顾客调员工端点 → 被拒，且不许回空数组（否则会被读成"这个客户没消费"）
        Api asCustomer = get("/api/payments/customer/" + customer + "?stationId=" + stationA,
                customerToken(customer));
        assertEquals(1, asCustomer.code(), "顾客不得调员工端点：" + asCustomer);
        assertTrue(asCustomer.message().contains("权限不足"), "拒绝要给权限文案：" + asCustomer);
        assertTrue(asCustomer.data().isNull(), "被拒时不得下发 data：" + asCustomer);

        assertEquals(401, get("/api/payments/customer/" + customer + "?stationId=" + stationA, null).status(),
                "无令牌应是真 401");
    }

    /* ==================================================================
     * 端点 4/5：GET /api/payments（根列表）与 GET /api/payments/all（#listAll / #listAllBackup）
     * 站长端"查所有"其实是"查本站"（listByStation/listAllByStation 按登录站过滤）。
     * ================================================================== */

    @Test
    @DisplayName("根列表与 /all 都按登录站过滤：只回本站流水，status 过滤真实生效，配送员被拒")
    void stationListsAreScopedToLoginStation() {
        long stationA = createStation("F47 列表甲站");
        long stationB = createStation("F47 列表乙站");
        long managerA = createStaff("F47 列表甲站长", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("F47 列表乙站长", "STATION_MANAGER", stationB, 1);
        long driver = createStaff("F47 列表配送员", "DELIVERY", stationA, 1);
        long custA = createCustomer("F47 列表甲客户", "f47-list-a");
        long custB = createCustomer("F47 列表乙客户", "f47-list-b");
        long addrA = createAddress(custA, "F47 列表甲小区");
        long addrB = createAddress(custB, "F47 列表乙小区");
        long product = createProduct("F47 列表水", 1, "20.00", "30.00", 0, "0.00");

        long orderA = createOrderFull(custA, addrA, stationA, product, 1, 2, 2, "40.00", "0.00", "40.00", false, 0);
        long flowA = createPaymentRecord(orderA, custA, stationA, "40.00", 2, 1); // status=1 待收款
        long orderB = createOrderFull(custB, addrB, stationB, product, 1, 2, 2, "55.00", "0.00", "55.00", false, 0);
        long flowB = createPaymentRecord(orderB, custB, stationB, "55.00", 2, 2); // status=2 已付

        // ① 根列表（带 query 参数的形态 —— FND-2 探针认这个形状）
        Api root = get("/api/payments?limit=100", staffToken(managerA, "STATION_MANAGER", stationA));
        assertEquals(0, root.code(), "根列表应成功：" + root);
        assertNotNull(findById(root.data(), flowA), "本站流水必须在列表里：" + root.data());
        assertNull(findById(root.data(), flowB), "他站流水绝不能混进来（漏过滤=跨站资金泄露）：" + root.data());

        // ② status 过滤真实生效：flowA 是 status=1，按 status=2 过滤后必须消失
        //    （若参数被吞，这里会照样返回 flowA —— 那是"过滤形同虚设"的潜伏形态）
        Api filtered = get("/api/payments?status=2&limit=100", staffToken(managerA, "STATION_MANAGER", stationA));
        assertEquals(0, filtered.code(), "带过滤条件的列表应成功：" + filtered);
        assertNull(findById(filtered.data(), flowA), "status=2 过滤必须把 status=1 的行滤掉：" + filtered.data());

        // ③ /all 备用列表同样按登录站过滤
        Api all = get("/api/payments/all?limit=100", staffToken(managerA, "STATION_MANAGER", stationA));
        assertEquals(0, all.code(), "/all 应成功：" + all);
        assertNotNull(findById(all.data(), flowA), "/all 必须含本站流水：" + all.data());
        assertNull(findById(all.data(), flowB), "/all 不得含他站流水：" + all.data());

        // ④ 换站视角：乙站站长看到的是 B 不是 A（同一端点、两个身份、两份互斥数据）
        Api asB = get("/api/payments?limit=100", staffToken(managerB, "STATION_MANAGER", stationB));
        assertEquals(0, asB.code(), "乙站站长应成功：" + asB);
        assertNotNull(findById(asB.data(), flowB), "乙站要看到自己的：" + asB.data());
        assertNull(findById(asB.data(), flowA), "乙站不得看到甲站的：" + asB.data());

        // ⑤ 角色与认证
        Api asDriver = get("/api/payments?limit=100", staffToken(driver, "DELIVERY", stationA));
        assertEquals(1, asDriver.code(), "配送员不得看资金列表：" + asDriver);
        assertTrue(asDriver.message().contains("权限不足"), "拒绝要给权限文案：" + asDriver);

        assertEquals(401, get("/api/payments?limit=100", null).status(), "无令牌应是真 401");
    }

    /** 在 JSON 数组里按 id 找那条流水；找不到返回 null（与"字段缺失"区分开：断言会打印整行）。 */
    private static JsonNode findById(JsonNode arr, long id) {
        if (arr == null || !arr.isArray()) return null;
        for (JsonNode n : arr) {
            if (n.path("id").asLong() == id) return n;
        }
        return null;
    }
}
