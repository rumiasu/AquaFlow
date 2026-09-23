package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户特权（v40，Phase 3）。
 *
 * <p>盯三件事：</p>
 * <ol>
 *   <li>特权**真的生效**（免起送门槛时报价与下单都不再被拦）—— 不是"存了一行没人读"；</li>
 *   <li>**未实现的类型在授予时就被拒绝**，而不是收下一个"配了也不生效"的开关；</li>
 *   <li>特权按 (customer, station) 隔离，A 站给的不在 B 站生效。</li>
 * </ol>
 */
class CustomerPrivilegeIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("免起送门槛：授予前被拦，授予后报价与下单都放行，撤销后恢复")
    void noMinOrderPrivilegeActuallyTakesEffect() {
        long station = createStation("特权站");
        long manager = createStaff("特权站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("特权客户", "priv-openid");
        long address = createAddress(customer, "特权小区 1 号");
        long product = createProduct("特权水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        createCustomerStationConfig(customer, station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 起送量 5 桶 + 不接单
        assertEquals(0, put("/api/manager/delivery-config", mgr,
                "{\"minOrderBuckets\":5,\"minOrderMode\":\"REJECT\"}").code(), "配置起送量");

        // 授予前：2 桶被拦
        Api before = quote(cus, station, address, product, 2);
        assertTrue(before.data().path("blocked").asBoolean(), "授予前报价应被拦: " + before);
        assertNotEquals(0, order(cus, station, address, product, 2, "priv-1").code(), "授予前下单应被拒");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE station_id=?", station), "被拒时不得留下订单");

        // 授予特权
        String base = "/api/manager/customers/" + customer + "/privileges";
        assertEquals(0, post(base, mgr, "{\"type\":\"NO_MIN_ORDER\",\"note\":\"老客户就买1桶\"}").code(),
                "授予免起送门槛");
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_privilege WHERE customer_id=? AND station_id=? "
                + "AND type='NO_MIN_ORDER'", customer, station), "特权应落库");

        // 授予后：报价不再 blocked、下单放行
        Api after = quote(cus, station, address, product, 2);
        assertFalse(after.data().path("blocked").asBoolean(), "授予后报价不该再被拦: " + after);
        assertEquals(0, order(cus, station, address, product, 2, "priv-2").code(), "授予后应能下单");
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE station_id=?", station));

        // 幂等：重复授予不产生第二行，也不报错
        assertEquals(0, post(base, mgr, "{\"type\":\"NO_MIN_ORDER\",\"note\":\"再确认一次\"}").code(),
                "重复授予应幂等");
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_privilege WHERE customer_id=?", customer));
        assertEquals("再确认一次", jdbc.queryForObject(
                "SELECT note FROM customer_privilege WHERE customer_id=? AND type='NO_MIN_ORDER'",
                String.class, customer), "重复授予应更新备注而不是插新行");

        // 撤销后门槛恢复
        assertEquals(0, delete(base + "/NO_MIN_ORDER", mgr).code(), "撤销特权");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_privilege WHERE customer_id=?", customer));
        assertTrue(quote(cus, station, address, product, 2).data().path("blocked").asBoolean(),
                "撤销后应恢复起送量拦截");
        assertNotEquals(0, delete(base + "/NO_MIN_ORDER", mgr).code(),
                "撤销本来就没有的特权必须报错，不能无条件返回成功");
    }

    @Test
    @DisplayName("护栏：未实现的类型被拒、未知类型名被拒、非本站客户被拒")
    void grantGuards() {
        long station = createStation("特权护栏站");
        long otherStation = createStation("特权护栏他站");
        long manager = createStaff("特权护栏站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("特权护栏客户", "privguard-openid");
        long outsider = createCustomer("他站客户", "privguard-outsider");
        createCustomerStationConfig(customer, station, 1);
        createCustomerStationConfig(outsider, otherStation, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        String base = "/api/manager/customers/" + customer + "/privileges";

        // ⚠️ 核心：未实现的「动钱」类型必须被拒绝，并说明为什么 ——
        // 收下它就是一个"看着开了、其实没用"的开关
        Api money = post(base, mgr, "{\"type\":\"DISCOUNT_RATE\"}");
        assertNotEquals(0, money.code(), "未实现的折扣率必须被拒");
        assertTrue(money.message().contains("尚未实现"), "拒绝原因应说明清楚: " + money.message());
        assertNotEquals(0, post(base, mgr, "{\"type\":\"FREE_DELIVERY_TIMES\"}").code(),
                "未实现的免配送次数必须被拒");
        assertNotEquals(0, post(base, mgr, "{\"type\":\"TICKET_RETURNABLE\"}").code(),
                "未实现的允许退票必须被拒");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_privilege WHERE customer_id=?", customer),
                "被拒的授予不得留下任何特权行");

        // 未知类型名（拼错）也要明确拒绝，不能静默存下一行永远不会被读到的特权
        assertNotEquals(0, post(base, mgr, "{\"type\":\"NO_MIM_ORDER\"}").code(), "拼错的类型名必须被拒");
        assertNotEquals(0, post(base, mgr, "{}").code(), "缺 type 必须被拒");

        // 非本站客户不得授予（否则站长能给别站的客户开特权）
        assertNotEquals(0, post("/api/manager/customers/" + outsider + "/privileges", mgr,
                "{\"type\":\"NO_MIN_ORDER\"}").code(), "他站客户不得授予本站特权");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_privilege WHERE station_id=?", station),
                "被拒的授予不得落库");

        // 不存在的客户
        assertNotEquals(0, post("/api/manager/customers/99999999/privileges", mgr,
                "{\"type\":\"NO_MIN_ORDER\"}").code(), "不存在的客户必须被拒");
    }

    @Test
    @DisplayName("特权按站隔离：A 站给的不在 B 站生效（否则等于跨站送钱）")
    void privilegeIsStationScoped() {
        long stationA = createStation("特权A站");
        long stationB = createStation("特权B站");
        long mgrA = createStaff("特权站长A", "STATION_MANAGER", stationA, 1);
        long mgrB = createStaff("特权站长B", "STATION_MANAGER", stationB, 1);
        long customer = createCustomer("跨站特权客户", "privcross-openid");
        long product = createProduct("跨站特权水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(stationA, product, 100, 0, "0.00");
        createInventoryFull(stationB, product, 100, 0, "0.00");
        createCustomerStationConfig(customer, stationA, 1);
        createCustomerStationConfig(customer, stationB, 1);
        String tokenA = staffToken(mgrA, "STATION_MANAGER", stationA);
        String tokenB = staffToken(mgrB, "STATION_MANAGER", stationB);

        // 两个站都配起送量 5 桶 + 不接单
        assertEquals(0, put("/api/manager/delivery-config", tokenA,
                "{\"minOrderBuckets\":5,\"minOrderMode\":\"REJECT\"}").code());
        assertEquals(0, put("/api/manager/delivery-config", tokenB,
                "{\"minOrderBuckets\":5,\"minOrderMode\":\"REJECT\"}").code());

        // 只在 A 站授予
        assertEquals(0, post("/api/manager/customers/" + customer + "/privileges", tokenA,
                "{\"type\":\"NO_MIN_ORDER\"}").code());

        // A 站列表能看到，B 站看不到
        assertEquals(1, get("/api/manager/customers/" + customer + "/privileges", tokenA)
                .data().path("privileges").size(), "A 站应看到该特权");
        assertEquals(0, get("/api/manager/customers/" + customer + "/privileges", tokenB)
                .data().path("privileges").size(), "B 站不该看到 A 站给的特权");

        // 计费链路上同样按站生效：A 站放行（本用例只验列表明细，计费放行由上一个用例覆盖）
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_privilege WHERE customer_id=? AND station_id=?",
                customer, stationA));
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_privilege WHERE customer_id=? AND station_id=?",
                customer, stationB), "特权不得跨站");

        // 授予清单只含已实现的类型（前端据此渲染，不写死枚举）
        assertEquals(1, get("/api/manager/customers/" + customer + "/privileges", tokenA)
                .data().path("grantableTypes").size(), "可授予清单当前只应有免起送门槛一项");
    }

    private Api quote(String customerToken, long stationId, long addressId, long productId, int qty) {
        return post("/api/payments/quote", customerToken,
                "{\"stationId\":" + stationId + ",\"paymentMethod\":1,\"addressId\":" + addressId + ","
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }

    private Api order(String customerToken, long stationId, long addressId, long productId,
                      int qty, String key) {
        return post("/api/orders/create", customerToken,
                "{\"addressId\":" + addressId + ",\"stationId\":" + stationId + ",\"paymentMethod\":1,"
                        + "\"idempotencyKey\":\"" + key + "\","
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }
}
