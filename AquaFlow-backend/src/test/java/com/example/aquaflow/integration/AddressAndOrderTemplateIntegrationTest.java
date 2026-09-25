package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顾客自助：收货地址簿 与 常用订单模板。
 *
 * <p>这两块是顾客端最常用的功能，此前**一条 HTTP 用例都没有**（地址只在造数时被直接塞库，
 * 模板从未被测试碰过）。它们的共同风险是<b>横向越权</b>：接口按 id 操作记录，
 * 而 id 是客户端可任意编造的 —— 只要漏一处归属校验，A 就能改/删 B 的地址与模板。</p>
 *
 * <p>身份铁律：{@code customerId} 一律取自 JWT（{@code AuthContext.requireCustomerId()}），
 * 请求体里的 {@code customerId} 只能被忽略或覆盖，绝不能被信任。</p>
 */
@DisplayName("顾客自助 · 地址簿与常用模板的归属校验（含横向越权）")
class AddressAndOrderTemplateIntegrationTest extends AbstractIntegrationTest {

    private long stationA;
    private long stationB;
    private long product;
    private long alice;
    private long bob;
    private long mgrA;
    private long driverA;

    private void seed() {
        stationA = createStation("A站");
        stationB = createStation("B站");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(stationA, product, 50, 1, "18.00");
        alice = createCustomer("Alice", "openid-alice");
        bob = createCustomer("Bob", "openid-bob");
        mgrA = createStaff("MA", "STATION_MANAGER", stationA, 1);
        driverA = createStaff("DA", "DELIVERY", stationA, 1);
    }

    private String mgrToken() {
        return staffToken(mgrA, "STATION_MANAGER", stationA);
    }

    private long aliceAddressId() {
        return longOf("SELECT id FROM address WHERE customer_id=? ORDER BY id LIMIT 1", alice);
    }

    private long bobAddressId() {
        return longOf("SELECT id FROM address WHERE customer_id=? ORDER BY id LIMIT 1", bob);
    }

    @Test
    @DisplayName("新建地址：请求体里的 customerId 被 JWT 覆盖，落不到别人名下")
    void createAddressUsesJwtIdentity() {
        seed();
        String body = "{\"customerId\":" + bob + ",\"name\":\"李四\",\"phone\":\"13900000000\",\"detail\":\"花园路1号\"}";

        Api res = post("/api/addresses", customerToken(alice), body);
        assertTrue(res.isSuccess(), "顾客新建地址应成功，实际=" + res);

        long newId = res.data().asLong();
        assertEquals(alice, longOf("SELECT customer_id FROM address WHERE id=?", newId),
                "地址必须落在 token 的客户名下，而不是请求体里的 customerId");
        assertEquals(0, intOf("SELECT COUNT(*) FROM address WHERE customer_id=?", bob),
                "不得在他人名下产生任何地址");
    }

    @Test
    @DisplayName("横向越权：读/改/删/设默认别人的地址一律被拒，且数据不变")
    void customerCannotTouchOthersAddress() {
        seed();
        long aliceAddr = createAddress(alice, "A 的地址");
        createAddress(bob, "B 的地址");
        long bobAddr = bobAddressId();

        assertFalse(get("/api/addresses/" + bobAddr, customerToken(alice)).isSuccess(), "不得读别人的地址");
        assertFalse(put("/api/addresses/" + bobAddr, customerToken(alice),
                "{\"detail\":\"被篡改\"}").isSuccess(), "不得改别人的地址");
        assertFalse(delete("/api/addresses/" + bobAddr, customerToken(alice)).isSuccess(),
                "不得删别人的地址（删除必须报错，不能返回成功却一行都没删）");
        assertFalse(put("/api/addresses/" + bobAddr + "/default", customerToken(alice), null).isSuccess(),
                "不得把别人的地址设成默认（旧实现会清空自己的默认、并改动别人那一行）");

        assertEquals("B 的地址", jdbc.queryForObject("SELECT detail FROM address WHERE id=?", String.class, bobAddr),
                "被拒后地址内容不得变");
        assertEquals(bob, longOf("SELECT customer_id FROM address WHERE id=?", bobAddr), "归属不得变");

        // 自己的地址正常可删（证明上面的拒绝来自归属校验，而不是接口坏了）
        assertTrue(delete("/api/addresses/" + aliceAddr, customerToken(alice)).isSuccess(), "删自己的地址应成功");
        assertEquals(0, intOf("SELECT COUNT(*) FROM address WHERE id=?", aliceAddr), "自己的地址应真的被删掉");
    }

    @Test
    @DisplayName("列表只看自己的；设默认地址后默认唯一")
    void listIsOwnOnlyAndDefaultIsUnique() {
        seed();
        createAddress(alice, "A1");
        createAddress(bob, "B1");

        Api list = get("/api/addresses", customerToken(alice));
        assertTrue(list.isSuccess(), "列表应可读，实际=" + list);
        for (var node : list.data()) {
            assertEquals(alice, node.path("customerId").asLong(), "地址列表里不得出现别人的地址");
        }

        Api created = post("/api/addresses", customerToken(alice),
                "{\"name\":\"张三\",\"phone\":\"13800000001\",\"detail\":\"A2\"}");
        assertTrue(created.isSuccess(), "再建一条应成功，实际=" + created);
        long secondId = created.data().asLong();

        assertTrue(put("/api/addresses/" + secondId + "/default", customerToken(alice), null).isSuccess(),
                "设默认应成功");
        assertEquals(1, intOf("SELECT COUNT(*) FROM address WHERE customer_id=? AND is_default=1", alice),
                "默认地址必须唯一");
        assertEquals(secondId, longOf("SELECT id FROM address WHERE customer_id=? AND is_default=1", alice),
                "新的默认地址应是刚设的这条");
    }

    @Test
    @DisplayName("站长只能看本站客户的地址；配送员读地址一律拒绝")
    void managerScopeAndDeliveryDenied() {
        seed();
        createAddress(alice, "A 的地址");
        long aliceAddr = aliceAddressId();
        // Alice 在 A 站有订单（站长校验依赖"该客户在本站有过订单"）
        createOrderFull(alice, aliceAddr, stationA, product, 1, 1, 2, "20.00", "0.00", "20.00", true, 1);

        assertTrue(get("/api/addresses/" + aliceAddr, mgrToken()).isSuccess(), "本站客户地址应可读");

        createAddress(bob, "B 的地址");
        long bobAddr = bobAddressId();
        // Bob 在 A 站没有任何订单 → A 站站长不得读他的地址
        Api cross = get("/api/addresses/" + bobAddr, mgrToken());
        assertFalse(cross.isSuccess(), "站长不得读他站客户的地址，实际=" + cross);

        assertFalse(get("/api/addresses/" + aliceAddr, staffToken(driverA, "DELIVERY", stationA)).isSuccess(),
                "配送员不得通过地址簿读取客户地址");
    }

    @Test
    @DisplayName("站长不得把地址改挂到别的客户名下")
    void managerCannotReassignAddressOwner() {
        seed();
        createAddress(alice, "A 的地址");
        long aliceAddr = aliceAddressId();
        createOrderFull(alice, aliceAddr, stationA, product, 1, 1, 2, "20.00", "0.00", "20.00", true, 1);

        Api res = put("/api/addresses/" + aliceAddr, mgrToken(),
                "{\"customerId\":" + bob + ",\"detail\":\"改挂到 Bob\"}");
        assertFalse(res.isSuccess(), "不得变更地址归属客户，实际=" + res);
        assertEquals(alice, longOf("SELECT customer_id FROM address WHERE id=?", aliceAddr), "归属不得变");
    }

    @Test
    @DisplayName("模板横向越权：拿别人的模板 id 覆盖保存必须被拒（AQ-036 漏掉的那一处）")
    void templateUpdateIsOwnershipGuarded() {
        seed();
        Api created = post("/api/order-templates?stationId=" + stationA, customerToken(alice),
                "{\"name\":\"Alice的常用\",\"specialNote\":\"两桶\",\"isDefault\":1,"
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":2}]}");
        assertTrue(created.isSuccess(), "Alice 存模板应成功，实际=" + created);
        long aliceTemplate = created.data().path("id").asLong();
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_template_item WHERE template_id=?", aliceTemplate),
                "模板明细应落库");

        // Bob 拿着 Alice 的模板 id 提交"保存"：旧实现只按 id 更新，会覆盖 Alice 的模板并清空她的明细
        Api attack = post("/api/order-templates?stationId=" + stationA, customerToken(bob),
                "{\"id\":" + aliceTemplate + ",\"name\":\"被Bob改名\",\"specialNote\":\"篡改\","
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":99}]}");
        assertFalse(attack.isSuccess(), "不得覆盖别人的模板，实际=" + attack);

        assertEquals("Alice的常用", jdbc.queryForObject(
                "SELECT name FROM order_template WHERE id=?", String.class, aliceTemplate), "模板名不得被改");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_template_item WHERE template_id=?", aliceTemplate),
                "别人的模板明细不得被清空");
    }

    @Test
    @DisplayName("模板按 (客户, 水站) 隔离：换站看不到，他人模板也动不了")
    void templateIsolatedByCustomerAndStation() {
        seed();
        Api created = post("/api/order-templates?stationId=" + stationA, customerToken(alice),
                "{\"name\":\"A站模板\",\"isDefault\":1,\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}");
        long tpl = created.data().path("id").asLong();

        Api listA = get("/api/order-templates?stationId=" + stationA, customerToken(alice));
        assertTrue(listA.isSuccess(), "A 站列表应可读，实际=" + listA);
        assertEquals(1, listA.data().size(), "A 站应能看到刚存的模板");

        Api listB = get("/api/order-templates?stationId=" + stationB, customerToken(alice));
        assertTrue(listB.isSuccess());
        assertEquals(0, listB.data().size(), "换到 B 站就不该看到 A 站的模板（模板按站隔离）");

        assertFalse(put("/api/order-templates/" + tpl + "/toggle?enabled=0", customerToken(bob), null).isSuccess(),
                "不得停用别人的模板");
        assertFalse(put("/api/order-templates/" + tpl + "/default?stationId=" + stationA,
                customerToken(bob), null).isSuccess(), "不得把别人的模板设为默认");
        assertFalse(delete("/api/order-templates/" + tpl, customerToken(bob)).isSuccess(), "不得删别人的模板");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_template WHERE id=?", tpl), "被拒后模板仍应存在");

        // 快速下单入口应返回该客户在该站的默认模板
        Api quick = get("/api/order-templates/quick?stationId=" + stationA, customerToken(alice));
        assertTrue(quick.isSuccess(), "快速下单模板应可读，实际=" + quick);
        assertEquals(tpl, quick.data().path("id").asLong(), "快捷下单应取默认模板");
    }

    @Test
    @DisplayName("从订单生成模板：只能用自己在该站的订单")
    void templateFromOrderIsScoped() {
        seed();
        long aliceAddr = createAddress(alice, "A 的地址");
        long aliceOrder = createOrderFull(alice, aliceAddr, stationA, product, 4, 2, 2,
                "20.00", "0.00", "20.00", false, 1);

        Api mine = post("/api/order-templates/from-order?orderId=" + aliceOrder + "&stationId=" + stationA,
                customerToken(alice), null);
        assertTrue(mine.isSuccess(), "用自己的订单建模板应成功，实际=" + mine);

        Api others = post("/api/order-templates/from-order?orderId=" + aliceOrder + "&stationId=" + stationA,
                customerToken(bob), null);
        assertFalse(others.isSuccess(), "不得用别人的订单建模板，实际=" + others);
    }
}
