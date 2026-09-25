package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下单金额重算与幂等回归。
 *
 * <p>核心不变量：金额 100% 由服务端推导（客户端传什么都无关紧要）；
 * 同一个幂等键重复下单只产生一张订单，库存与桶权益只扣一次。</p>
 *
 * <p>金额口径：水费取<b>站级水票价</b>（inventory.ticket_price=18），不是商品零售价（20）——
 * 这两者一混，水票订单就会多收钱；押金是<b>缺桶数 × 押金单价</b>，
 * 客户手上已有权益的部分不该再收一次押金。</p>
 */
@DisplayName("Phase B · 下单金额重算与幂等")
class OrderCreationIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
    }

    private Api createOrderApi(String idemKey, int qty) {
        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":3,\"idempotencyKey\":\"" + idemKey + "\","
                + "\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}";
        return post("/api/orders/create", customerToken(customer), body);
    }

    @Test
    @DisplayName("订单金额全部由服务端重算（水票价取站级配置，押金按缺桶数）")
    void orderAmountIsServerDerived() {
        seed();

        Api res = createOrderApi("idem-amt", 2);
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        long orderId = res.data().path("orderId").asLong();

        BigDecimal water = decimalOf("SELECT water_amount FROM orders WHERE id=?", orderId);
        assertEquals(0, water.compareTo(new BigDecimal("36.00")),
                "水费应为站级水票价 18.00×2=36.00（取零售价 20 即为计价错位），实际=" + water);

        BigDecimal deposit = decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId);
        assertEquals(0, deposit.compareTo(new BigDecimal("60.00")), "押金应按缺桶数重算，实际=" + deposit);

        BigDecimal total = decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId);
        assertEquals(0, total.compareTo(new BigDecimal("96.00")), "合计应为 36+60=96.00，实际=" + total);

        // [2026-09-25 库存预留模型] 下单**不再扣实物**：改为"预留占住 2 桶"，实物在完成配送时才出库
        // （旧口径断言的是 8；见 docs/design/28-库存预留与履约凭据.md 与 InventoryReservationIntegrationTest）
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product), "下单不动实物（10 桶还是 10 桶）");
        assertEquals(2, intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND product_id=? AND status=1", station, product), "必须预留 2 桶");
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit WHERE related_order_id=?", orderId));
        assertEquals(2, intOf("SELECT qty FROM customer_barrel_in_transit WHERE related_order_id=?", orderId));
        assertEquals(0, decimalOf("SELECT unit_price FROM customer_barrel_in_transit WHERE related_order_id=?", orderId)
                        .compareTo(new BigDecimal("30.00")),
                "配送中桶应快照下单当时押金单价");
    }

    @Test
    @DisplayName("同一幂等键重复下单：只建一单、只扣一次库存、不重复建配送中桶")
    void duplicateIdempotencyKeyIsIgnored() {
        seed();

        Api first = createOrderApi("idem-dup", 2);
        Api second = createOrderApi("idem-dup", 2);

        assertTrue(first.isSuccess(), "首次下单应成功，实际=" + first);
        assertTrue(second.isSuccess(), "重复下单应返回既有订单而非报错，实际=" + second);

        long firstId = first.data().path("orderId").asLong();
        long secondId = second.data().path("orderId").asLong();
        assertEquals(firstId, secondId, "幂等键命中应返回同一订单");
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders"), "只应存在一张订单");
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                        station, product),
                "下单不动实物（旧口径的 8 已废）");
        assertEquals(2, intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                        + "WHERE station_id=? AND product_id=? AND status=1", station, product),
                "预留只应发生一次（2 桶，不是 4 桶）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit"), "配送中桶只应建一次");
    }

    // ==================== [2026-09-25 架构评审问题 5] 幂等的作用域与内容契约 ====================
    // 旧实现：键为空即服务端自造 UUID（重试 = 新键 = 新单）；命中查询只按 key 全局查
    //（跨客户复用同一个键会拿回**别人的订单 id**）；同键不同内容没有任何冲突语义。
    // 唯一键也从单列改成了 (customer_id, idempotency_key)（迁移 v62）—— 下面每条都在钉这两半。

    @Test
    @DisplayName("问题5：缺幂等键 → 直接拒绝（不再由服务端代生成 UUID 假装幂等）")
    void missingIdempotencyKeyIsRejected() {
        seed();

        Api res = post("/api/orders/create", customerToken(customer),
                "{\"addressId\":" + addr + ",\"stationId\":" + station + ",\"paymentMethod\":3,"
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}");

        assertTrue(!res.isSuccess(), "缺键必须被拒（否则服务端每次生成新键 = 无幂等），实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "被拒后不得建单");
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product), "被拒后不得扣库存");
    }

    @Test
    @DisplayName("问题5：跨客户复用同一个幂等键 → 各自建各自的单，绝不串单")
    void sameKeyAcrossCustomersDoesNotLeakOtherOrder() {
        seed();
        long otherCustomer = createCustomer("Bob", "openid-bob");
        long otherAddr = createAddress(otherCustomer, "某小区2号");

        Api alice = createOrderApi("idem-shared-key", 1);

        Api bob = post("/api/orders/create", customerToken(otherCustomer),
                "{\"addressId\":" + otherAddr + ",\"stationId\":" + station + ",\"paymentMethod\":3,"
                        + "\"idempotencyKey\":\"idem-shared-key\","
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}");

        assertTrue(alice.isSuccess(), "Alice 下单应成功，实际=" + alice);
        assertTrue(bob.isSuccess(), "Bob 用同一个键也必须能建自己的单（旧实现会撞单列唯一键或返回 Alice 的单），实际=" + bob);
        long aliceOrder = alice.data().path("orderId").asLong();
        long bobOrder = bob.data().path("orderId").asLong();
        assertTrue(aliceOrder != bobOrder, "两个客户必须是两张不同的订单");
        assertEquals(2, intOf("SELECT COUNT(*) FROM orders"), "两个客户各一张单");
        assertEquals(otherCustomer, longOf("SELECT customer_id FROM orders WHERE id=?", bobOrder),
                "返回的订单必须属于调用者自己");
    }

    @Test
    @DisplayName("问题5：同一个键、内容不同（改了商品/数量）→ 拒绝，不得拿回无关的旧单")
    void sameKeyDifferentContentIsRejected() {
        seed();

        Api first = createOrderApi("idem-content", 1);
        assertTrue(first.isSuccess(), "首次下单应成功，实际=" + first);
        long orderId = first.data().path("orderId").asLong();

        // 同键、数量从 1 改成 2（"另一次下单意图"却复用了同一个键）
        Api conflict = createOrderApi("idem-content", 2);

        assertTrue(!conflict.isSuccess(),
                "同键不同内容必须被拒（否则客户端以为下了新单，其实拿回的是旧单），实际=" + conflict);
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders"), "不得新增订单");
        assertEquals(1, intOf("SELECT quantity FROM order_item WHERE order_id=?", orderId),
                "原单的数量不得被改写");
    }

    @Test
    @DisplayName("问题5：缺货确认路径仍可复用同一个键（confirmShortage 不算『内容不同』）")
    void shortageConfirmReusesSameKeySuccessfully() {
        seed();
        // 把库存清到 1，下单 2 桶 → 第一次返回 needConfirm（**不建单**），确认后带同一个键重提
        jdbc.update("UPDATE inventory SET quantity=1 WHERE station_id=? AND product_id=?", station, product);

        Api needConfirm = createOrderApi("idem-shortage", 2);
        assertTrue(needConfirm.isSuccess(), "缺货首次提交不应报错，实际=" + needConfirm);
        assertTrue(needConfirm.data().path("needConfirm").asBoolean(), "缺货应回 needConfirm=true");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "needConfirm 阶段不得建单");

        Api confirmed = post("/api/orders/create", customerToken(customer),
                "{\"addressId\":" + addr + ",\"stationId\":" + station + ",\"paymentMethod\":3,"
                        + "\"idempotencyKey\":\"idem-shortage\",\"confirmShortage\":true,"
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":2}]}");

        assertTrue(confirmed.isSuccess(),
                "确认缺货后用**同一个键**重提必须成功（把 confirmShortage 算进摘要就会永远下不了单），实际=" + confirmed);
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders"), "确认后才建单");
        assertTrue(intOf("SELECT COUNT(*) FROM orders WHERE request_digest IS NOT NULL") == 1,
                "新单必须落幂等请求摘要（同键第二次请求靠它判断内容是否一致）");
    }
}
