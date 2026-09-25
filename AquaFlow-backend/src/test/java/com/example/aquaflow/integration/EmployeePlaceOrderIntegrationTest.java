package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代客下单（2026-09-17，规格见 {@code docs/design/20} §5）。
 *
 * <p>盯四件事：</p>
 * <ol>
 *   <li><b>归属判据是并集</b>：已有本站订单但<b>没有绑定行</b>的老客户必须能代客下单 ——
 *       只看绑定行会把这类客户拒掉，而客户列表里又点得到他（最难排查的一种拒绝）；</li>
 *   <li>他站客户仍然被拒（放宽不等于放开）；</li>
 *   <li>客户选择器能搜到"已建档但还没下过单"的新客户（{@code GET /api/customers} 是
 *       orders 驱动，查不到这种人）；</li>
 *   <li><b>站长试算与建单同口径</b>：试算的 totalAmount 必须等于真正落库的 total_amount。</li>
 * </ol>
 */
@DisplayName("代客下单 · 归属判据 / 客户选择器 / 试算与建单同口径")
class EmployeePlaceOrderIntegrationTest extends AbstractIntegrationTest {

    /** 员工端建单：customerId 由请求体指定（顾客端由登录态强覆盖）。 */
    private Api staffOrder(String token, long customerId, long stationId, long addressId,
                           long productId, int qty, int payMethod, String key) {
        return post("/api/orders/create", token,
                "{\"customerId\":" + customerId + ",\"addressId\":" + addressId
                        + ",\"stationId\":" + stationId + ",\"paymentMethod\":" + payMethod
                        + ",\"source\":1,\"idempotencyKey\":\"" + key + "\","
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }

    private String quoteBody(long stationId, long addressId, long productId, int qty, int payMethod) {
        return "{\"stationId\":" + stationId + ",\"paymentMethod\":" + payMethod
                + ",\"addressId\":" + addressId + ","
                + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}";
    }

    @Test
    @DisplayName("已有本站订单但没有绑定行的老客户：可以代客下单，不再被判『不属于本水站』")
    void customerWithOrderButNoBindingCanStillBeServed() {
        long station = createStation("代客站A");
        long manager = createStaff("代客站长A", "STATION_MANAGER", station, 1);
        long customer = createCustomer("老客户", "assist-old");
        long address = createAddress(customer, "老客户小区 1 号");
        long product = createProduct("代客水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        createCustomerStationConfig(customer, station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 先让顾客自己下一单（产生"本站订单"）
        String cus = customerToken(customer);
        assertEquals(0, post("/api/orders/create", cus,
                "{\"addressId\":" + address + ",\"stationId\":" + station
                        + ",\"paymentMethod\":2,\"idempotencyKey\":\"assist-cus-1\","
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}").code(),
                "顾客自助下单应成功");
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE customer_id=? AND station_id=?",
                customer, station));

        // 造出"有订单、没有绑定行"这个状态（旧实现只看绑定行，必然拒掉这类客户）
        jdbc.update("DELETE FROM customer_station_config WHERE customer_id=? AND station_id=?",
                customer, station);
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_station_config "
                + "WHERE customer_id=? AND station_id=?", customer, station), "绑定行应已删除");

        Api r = staffOrder(mgr, customer, station, address, product, 2, 2, "assist-staff-1");
        assertFalse(String.valueOf(r.message()).contains("不属于本水站"),
                "有本站订单的老客户不该再被判『不属于本水站』: " + r);
    }

    @Test
    @DisplayName("他站客户仍被拒 —— 放宽归属判据不等于放开")
    void foreignCustomerIsStillRejected() {
        long stationA = createStation("代客站A2");
        long stationB = createStation("代客站B2");
        long managerA = createStaff("代客站长A2", "STATION_MANAGER", stationA, 1);
        long outsider = createCustomer("他站客户", "assist-outsider");
        long outsiderAddr = createAddress(outsider, "他站小区 1 号");
        long product = createProduct("代客水2", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(stationA, product, 100, 0, "0.00");
        createCustomerStationConfig(outsider, stationB, 1);
        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);

        Api r = staffOrder(mgrA, outsider, stationA, outsiderAddr, product, 1, 2, "assist-outsider-1");
        assertNotEquals(0, r.code(), "他站客户不得代客下单");
        assertTrue(String.valueOf(r.message()).contains("不属于本水站"), "拒绝原因应指向归属: " + r);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE station_id=?", stationA),
                "被拒时不得留下订单");
    }

    @Test
    @DisplayName("客户选择器：能搜到已建档但还没下过单的新客户，且不含他站客户")
    void customerPickerFindsBoundCustomerWithoutOrders() {
        long station = createStation("代客站C");
        long other = createStation("代客站D");
        long manager = createStaff("代客站长C", "STATION_MANAGER", station, 1);
        long fresh = createCustomer("刚建档客户", "assist-fresh");
        long foreign = createCustomer("他站客户C", "assist-foreign");
        // fresh：只绑定、没有任何订单；foreign：绑到另一个站
        createCustomerStationConfig(fresh, station, 0);
        createCustomerStationConfig(foreign, other, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE customer_id=?", fresh),
                "本用例前提：该客户确实一单都没有");

        Api all = get("/api/manager/order-assist/customers", mgr);
        assertEquals(0, all.code(), "读客户选择器: " + all);
        assertTrue(containsId(all, fresh),
                "没下过单的新客户必须能选到（GET /api/customers 是 orders 驱动，查不到他）: " + all);
        assertFalse(containsId(all, foreign), "他站客户不得出现在本站选择器里: " + all);

        // 关键字过滤：按姓名片段能命中
        Api kw = get("/api/manager/order-assist/customers?keyword=刚建档", mgr);
        assertEquals(1, kw.data().size(), "按姓名片段应只命中 1 个: " + kw);
        assertTrue(containsId(kw, fresh), "命中的应是那个新客户: " + kw);
        Api miss = get("/api/manager/order-assist/customers?keyword=不存在的名字", mgr);
        assertEquals(0, miss.data().size(), "搜不到就该是空列表: " + miss);
    }

    /** 按 id 判定，不靠断言 JSON 里的中文串（序列化口径一变就会误红）。 */
    private boolean containsId(Api resp, long id) {
        for (var node : resp.data()) {
            if (node.path("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("客户地址：站长可取到该客户的地址；他站客户的地址取不到")
    void managerCanReadCustomerAddresses() {
        long station = createStation("代客站E");
        long other = createStation("代客站F");
        long manager = createStaff("代客站长E", "STATION_MANAGER", station, 1);
        long mine = createCustomer("本站客户E", "assist-mine");
        long foreign = createCustomer("他站客户E", "assist-foreign-e");
        createAddress(mine, "本站小区 1 号");
        createCustomerStationConfig(mine, station, 1);
        createCustomerStationConfig(foreign, other, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api ok = get("/api/manager/order-assist/customers/" + mine + "/addresses", mgr);
        assertEquals(0, ok.code(), "本站客户的地址应可读: " + ok);
        assertEquals(1, ok.data().size(), "应有 1 个地址");

        assertNotEquals(0, get("/api/manager/order-assist/customers/" + foreign + "/addresses", mgr).code(),
                "他站客户的地址不得读取");
        assertNotEquals(0, get("/api/manager/order-assist/customers/99999999/addresses", mgr).code(),
                "不存在的客户必须被拒");
    }

    @Test
    @DisplayName("站长试算与建单同口径：试算 totalAmount 必须等于落库 total_amount")
    void managerQuoteMatchesCreatedOrderTotal() {
        long station = createStation("代客站G");
        long manager = createStaff("代客站长G", "STATION_MANAGER", station, 1);
        long customer = createCustomer("代客报价客户", "assist-quote");
        long address = createAddress(customer, "代客小区 7 号");
        long product = createProduct("代客水G", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        createCustomerStationConfig(customer, station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api q = post("/api/manager/order-assist/quote?customerId=" + customer, mgr,
                quoteBody(station, address, product, 3, 2));
        assertEquals(0, q.code(), "站长试算应成功: " + q);
        BigDecimal quoted = q.data().path("totalAmount").decimalValue();
        assertTrue(quoted.signum() > 0, "试算金额应大于 0: " + q);

        // 支付方式选项必须由服务端下发（前端禁止自带 1/2/3 映射表）
        assertTrue(q.data().path("methods").isArray() && q.data().path("methods").size() > 0,
                "试算必须下发支付方式选项: " + q);

        Api created = staffOrder(mgr, customer, station, address, product, 3, 2, "assist-quote-1");
        assertEquals(0, created.code(), "代客下单应成功: " + created);
        long orderId = created.data().path("orderId").asLong();

        assertEquals(0, decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId).compareTo(quoted),
                "试算金额必须等于落库金额（计价双轨就是在这里出的客诉）");
        assertEquals(1, intOf("SELECT source FROM orders WHERE id=?", orderId),
                "站长代录的来源应记为 1 电话");
        assertEquals(customer, jdbc.queryForObject("SELECT customer_id FROM orders WHERE id=?",
                        Long.class, orderId).longValue(),
                "订单必须挂在被代下单的客户名下");

        // 地址不属于该客户时必须拒绝试算（否则楼层费/远程费会按别人的地址算）
        long otherCustomer = createCustomer("别家客户", "assist-other-c");
        long otherAddr = createAddress(otherCustomer, "别家小区 9 号");
        assertNotEquals(0, post("/api/manager/order-assist/quote?customerId=" + customer, mgr,
                quoteBody(station, otherAddr, product, 1, 2)).code(),
                "拿别人的地址试算必须被拒");
    }
}
