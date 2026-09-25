package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全 · 防前端篡改：服务端**不信任任何客户端可改字段**。
 *
 * <p>本类只做一件事：把已经存在的护栏钉死。每条用例都断言**两件事**——
 * ① 请求被拒（或篡改被覆盖）；② <b>没有产生副作用</b>（订单/流水/调整单没落库）。
 * 只断言"返回码非 0"是不够的：错误可能来自参数校验，而真正的越权路径已经执行。</p>
 *
 * <p>覆盖的信任边界（对应代码位置见各用例注释）：</p>
 * <ul>
 *   <li>身份：`customerId` 一律取自 JWT（`OrderController:60-64`、`PaymentController:113-117`）；</li>
 *   <li>归属：地址/订单必须属于本人（`OrderServiceImpl:201`、`PaymentController:115`）；</li>
 *   <li>金额：水费/押金/合计全部服务端重算，客户端传的 `extraDeposit` 被显式忽略（`OrderServiceImpl:393`）；</li>
 *   <li>权限：顾客 token 调员工端点必须被拒（<b>契约是 HTTP 200 + body `code=1`「权限不足」，不是 403</b>，见 `RequireRoleAspect`）；</li>
 *   <li>租户：站长只能动本站客户（`StationAdjustmentServiceImpl:343-347`）；</li>
 *   <li>业务约束：限购、水票适用性、货到付款授权由服务端校验。</li>
 * </ul>
 */
@DisplayName("安全 · 防前端篡改（身份 / 金额 / 权限 / 跨租户 / 业务约束）")
class TamperResistanceIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long station;
    private long product;
    private long customer;      // Alice，本站、已开通货到付款
    private long other;         // Bob，本站、未开通货到付款
    private long addr;
    private long otherAddr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 100, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        other = createCustomer("Bob", "openid-bob");
        addr = createAddress(customer, "A 的地址");
        otherAddr = createAddress(other, "B 的地址");
        createCustomerStationConfig(customer, station, 1);
    }

    /** 下单报文：customerId 由参数决定（用于模拟篡改），可追加任意额外字段。 */
    private String orderBody(long bodyCustomerId, long bodyAddressId, long bodyProductId, int qty,
                             int paymentMethod, String extraFields) {
        return "{\"customerId\":" + bodyCustomerId + ",\"addressId\":" + bodyAddressId
                + ",\"stationId\":" + station + ",\"paymentMethod\":" + paymentMethod
                + ",\"idempotencyKey\":\"tamper-" + SEQ.incrementAndGet() + "\""
                + ",\"items\":[{\"productId\":" + bodyProductId + ",\"quantity\":" + qty + "}]"
                + (extraFields == null ? "" : "," + extraFields) + "}";
    }

    private Api createOrder(String token, String body) {
        return post("/api/orders/create", token, body);
    }

    @Test
    @DisplayName("篡改 customerId 无效：订单仍落在 token 名下")
    void tamperedCustomerIdIsOverwrittenByToken() {
        seed();
        // 用自己的地址、但把 customerId 写成 Bob —— 服务端必须覆盖成 token 的 Alice
        Api res = createOrder(customerToken(customer), orderBody(other, addr, product, 1, 2, null));
        assertTrue(res.isSuccess(), "顾客下单应成功（customerId 被服务端覆盖），实际=" + res);

        long orderId = res.data().path("orderId").asLong();
        assertEquals(customer, longOf("SELECT customer_id FROM orders WHERE id=?", orderId),
                "订单必须属于 token 的客户，而不是请求体里的 customerId");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE customer_id=?", other),
                "不得在他人名下产生任何订单");
    }

    @Test
    @DisplayName("用别人的地址下单：拒绝且不落单")
    void otherCustomersAddressIsRejected() {
        seed();
        Api res = createOrder(customerToken(customer), orderBody(other, otherAddr, product, 1, 2, null));
        assertFalse(res.isSuccess(), "拿别人的地址下单应被拒，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "被拒后不得留下任何订单");
    }

    @Test
    @DisplayName("篡改金额无效：extraDeposit/合计/单价一律服务端重算")
    void tamperedAmountsAreRecomputed() {
        seed();
        // 客户端把押金压成 0，并塞入不存在的字段（totalAmount/depositAmount/price 应被忽略，extraDeposit 被显式忽略）
        Api res = createOrder(customerToken(customer), orderBody(customer, addr, product, 2, 2,
                "\"extraDeposit\":0,\"totalAmount\":0.01,\"depositAmount\":0,\"price\":0.01,\"waterAmount\":0.01"));
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);

        long orderId = res.data().path("orderId").asLong();
        assertEquals(0, decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId)
                        .compareTo(new BigDecimal("60.00")),
                "押金必须按服务端算出的缺桶数（2 桶 × 30）计，客户端传 0 无效");
        assertEquals(0, decimalOf("SELECT water_amount FROM orders WHERE id=?", orderId)
                        .compareTo(new BigDecimal("40.00")),
                "水费必须按商品/站级单价重算（现金 20 × 2）");
        assertEquals(0, decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId)
                        .compareTo(new BigDecimal("100.00")),
                "合计 = 40 + 60，客户端传的 0.01 必须被忽略");
    }

    @Test
    @DisplayName("非法数量（0 / 负数）被拒且不落单")
    void illegalQuantityIsRejected() {
        seed();
        assertFalse(createOrder(customerToken(customer), orderBody(customer, addr, product, 0, 2, null)).isSuccess(),
                "数量 0 应被拒");
        assertFalse(createOrder(customerToken(customer), orderBody(customer, addr, product, -1, 2, null)).isSuccess(),
                "负数应被拒");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "被拒后不得留下订单");
    }

    @Test
    @DisplayName("服务端业务约束不可绕过：限购上限")
    void maxPerOrderIsEnforced() {
        seed();
        jdbc.update("UPDATE product SET max_per_order=1 WHERE id=?", product);
        Api res = createOrder(customerToken(customer), orderBody(customer, addr, product, 2, 2, null));
        assertFalse(res.isSuccess(), "超过单笔限购上限应被拒，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"));
    }

    @Test
    @DisplayName("水票支付适用性由服务端判定：商品未开水票则拒")
    void ticketPaymentRequiresTicketEnabledProduct() {
        seed();
        long noTicket = createProduct("未开水票的桶装水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, noTicket, 10, 0, "0.00");

        Api res = createOrder(customerToken(customer), orderBody(customer, addr, noTicket, 1, 3, null));
        assertFalse(res.isSuccess(), "商品未开通水票时应拒绝水票支付，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"));
    }

    @Test
    @DisplayName("货到付款需站长授权：未开通的客户用现金支付被拒")
    void cashPaymentRequiresOfflineAuthorization() {
        seed();
        // Bob 没有 customer_station_config 记录
        Api res = createOrder(customerToken(other), orderBody(other, otherAddr, product, 1, 2, null));
        assertFalse(res.isSuccess(), "未开通货到付款的客户用现金支付应被拒，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"));
    }

    @Test
    @DisplayName("顾客 token 调员工端点：一律被拒（权限不足）且不产生副作用")
    void customerTokenCannotCallStaffEndpoints() {
        seed();
        String t = customerToken(customer);

        // 注意：角色拦截的实际契约是 HTTP 200 + body code=1（业务错误），**不是真 403** ——
        // RequireRoleAspect 抛 BusinessException，由 GlobalExceptionHandler 统一兜成 code=1/200。
        // 只有"未认证"才是真 401（见 AbstractIntegrationTest 的说明）。
        Api save = post("/api/orders", t, "{}");
        assertFalse(save.isSuccess(), "代客录单（save）必须被拒，实际=" + save);
        assertTrue(save.message().contains("权限不足"), "拒绝原因应是权限不足，实际=" + save.message());
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "被拒后不得落库");

        assertFalse(get("/api/manager/owed-barrels", t).isSuccess(), "欠桶台账必须被拒");
        assertFalse(get("/api/manager/reconciliation", t).isSuccess(), "对账必须被拒");
        assertFalse(post("/api/manager/adjustments", t, "{}").isSuccess(), "资产调整单必须被拒");
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_adjustment"), "被拒后不得落库");
    }

    @Test
    @DisplayName("不得操控他人订单的支付：拒且不写 payment_record")
    void cannotPayForOtherCustomersOrder() {
        seed();
        long otherOrder = createOrder(other, otherAddr, station, product, 1, 0);

        Api res = post("/api/payments", customerToken(customer),
                "{\"orderId\":" + otherOrder + ",\"amount\":\"20.00\",\"waterAmount\":\"20.00\",\"barrelDeposit\":\"0.00\",\"paymentMethod\":2}");
        assertFalse(res.isSuccess(), "不得为他人订单发起支付，实际=" + res);
        assertTrue(res.message().contains("无权") || res.message().contains("他人"),
                "拒绝原因应指向越权，实际=" + res.message());
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=?", otherOrder),
                "被拒后不得留下任何支付流水");
    }

    @Test
    @DisplayName("跨租户：站长只能调整本站客户")
    void managerCannotAdjustOtherStationsCustomer() {
        seed();
        long station2 = createStation("S2");
        long mgr2 = createStaff("M2", "STATION_MANAGER", station2, 1);

        // Alice 只绑定 S1；用 S2 站长给她开调整单 → 必须被拒
        Api res = post("/api/manager/adjustments", staffToken(mgr2, "STATION_MANAGER", station2),
                "{\"customerId\":" + customer + ",\"adjustType\":\"OVER_ADJUST\",\"productId\":" + product
                        + ",\"qty\":1,\"reason\":\"越权测试\",\"clientToken\":\"tamper-adj-1\"}");
        assertFalse(res.isSuccess(), "他站站长不得给本站客户开调整单，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_adjustment"), "被拒后不得留下调整单");
    }
}
