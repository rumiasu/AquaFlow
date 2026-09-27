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

    // ==================== [2026-09-26] 并发同键：只许落一单 ====================
    // 背景：客户在开发者工具里点了 3 下「立即下单」成交 3 单（订单 37/38/39，request_digest 相同、
    // 幂等键三个都不同）。**那一半是前端的缺陷**（成功即清键 ⇒ 再点就是新键），已在小程序侧修
    // （见 create.js 的 RECENT_ORDER_WINDOW_MS 闸门）。但本条要钉的是**服务端这一半**：
    // 当前端真的用**同一个键**并发发多次时，必须只落一单、只扣一次库存与押金；
    // 其余请求要么按幂等拿回原单，要么拿到可读的业务拒绝 —— **绝不能建出第二张单**，
    // 也绝不能把并发拒绝伪装成 500（那是 §8.21 的老坑）。
    @Test
    @DisplayName("并发同键下单：三路齐发只落一单，其余返回原单或可读拒绝")
    void concurrentSameKeyCreatesExactlyOneOrder() throws Exception {
        seed();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(3);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Api>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return createOrderApi("idem-concurrent", 2);
                }));
            }
            ready.await(10, java.util.concurrent.TimeUnit.SECONDS);
            start.countDown();
            java.util.List<Api> results = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Api> f : futures) {
                results.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }

            assertEquals(1, intOf("SELECT COUNT(*) FROM orders"), "三路并发同键只许落一单：" + results);
            assertEquals(2, intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                            + "WHERE station_id=? AND product_id=? AND status=1", station, product),
                    "预留只许发生一次（2 桶，不是 6 桶）");
            assertEquals(1, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit"),
                    "配送中桶只许建一次");

            // 每一路都必须是"可读结果"：要么成功（含幂等命中返回原单），要么 code=1 业务拒绝。
            // 出现 HTTP 5xx / code=500 就是并发拒绝被伪装成系统错误（§8.21）。
            for (Api r : results) {
                assertTrue(r.status() == 200, "并发同键不该返回非 200：" + r);
                assertTrue(r.code() == 0 || r.code() == 1,
                        "并发同键只许是成功或业务拒绝，不许是系统错误：" + r);
            }
            // 成功的那些必须指向**同一张单**
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (Api r : results) {
                if (r.code() == 0 && r.data() != null) {
                    ids.add(r.data().path("orderId").asLong());
                }
            }
            assertEquals(1, ids.size(), "所有成功的响应必须指向同一张订单：" + ids);
        } finally {
            pool.shutdownNow();
        }
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
