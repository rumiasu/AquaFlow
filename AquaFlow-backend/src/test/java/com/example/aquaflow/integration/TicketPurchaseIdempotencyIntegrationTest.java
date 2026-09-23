package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线购票幂等（v33）。
 *
 * <p><b>为什么单独一条用例</b>：{@code POST /api/tickets/purchase} 是「无订单支付」——
 * {@code order_id} 为 NULL。这带来两处保护同时失效：
 * <ol>
 *   <li>{@code PaymentServiceImpl.createPayment} 的重复流水检查整段包在
 *       {@code if (orderId != null)} 里，这条路径根本不走它；</li>
 *   <li>数据库唯一键 {@code uk_payment_active_order} 建在生成列 {@code active_order_id}
 *       （{@code case when status in (1,2) then order_id else null end}）上，order_id 为 NULL 时
 *       生成列也是 NULL，而 <b>MySQL 唯一键中 NULL 互不冲突</b> → 零保护。</li>
 * </ol>
 * 因此修复前连点两次就落两条待收款流水，站长在「待确认收款」看到两行、都确认即入账两次。</p>
 *
 * <p><b>本用例最关键的一条断言不是「只落一条流水」，而是「确认后水票只多一份」</b>
 * （{@link #sameKeyReplaysAndCreditsTicketsOnce()}）—— 流水条数只是手段，入账金额才是后果。
 * 注意 {@code confirmPayment} 的乐观锁（CAS PENDING→PAID）只保证<b>单条</b>流水确认一次，
 * 它管不住「重复流水」，所以修复必须发生在落流水这一步。</p>
 */
class TicketPurchaseIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("同一幂等键重放：返回同一笔流水，且确认收款后水票只入账一次")
    void sameKeyReplaysAndCreditsTicketsOnce() {
        long station = createStation("幂等站");
        long manager = createStaff("幂等站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("幂等客户", "idem-openid");
        long product = createProduct("幂等水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");

        String cus = customerToken(customer);
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String key = "ticket-idem-key-1";

        // 同一笔购买意图提交两次（模拟连点）
        Api first = post("/api/tickets/purchase", cus, body(product, 3, station, key));
        Api second = post("/api/tickets/purchase", cus, body(product, 3, station, key));

        assertEquals(0, first.code(), "首次购票应成功: " + first);
        assertEquals(0, second.code(), "重放应成功返回同一笔，而不是报错: " + second);
        long firstId = first.data().path("paymentId").asLong();
        long secondId = second.data().path("paymentId").asLong();
        assertEquals(firstId, secondId, "同一幂等键必须返回同一笔流水，实际 " + firstId + " vs " + secondId);

        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE customer_id=? AND idempotency_key=?",
                customer, key), "同一幂等键只能落一条流水（修复前会落两条）");

        // —— 后果断言：确认收款后水票只能多 3 张 ——
        Api confirmed = put("/api/payments/" + firstId + "/confirm", mgr, null);
        assertEquals(0, confirmed.code(), "站长确认收款: " + confirmed);
        assertEquals(3, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                        + "AND product_id=?", customer, station, product),
                "确认一笔支付只应入账 3 张。若是两条流水被分别确认，这里会是 6 —— 那正是修复前的行为");
        // 重复确认同一笔必须被乐观锁拒绝（这条不是本次修复引入的，但不能被改坏）
        assertNotEquals(0, put("/api/payments/" + firstId + "/confirm", mgr, null).code(),
                "同一笔流水不得确认两次");
        assertEquals(3, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, product), "重复确认后余额不得再变");
        assertEquals(3, intOf("SELECT increase_qty FROM ticket_record WHERE customer_id=? AND station_id=? "
                + "AND product_id=? AND source='购买'", customer, station, product),
                "「购买」流水只应有一条 3 张");
    }

    @Test
    @DisplayName("缺幂等键必须拒绝：宁可失败出声，也不能静默落一条没有保护的流水")
    void missingKeyIsRejected() {
        long station = createStation("缺键站");
        long customer = createCustomer("缺键客户", "nokey-openid");
        long product = createProduct("缺键水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");

        String cus = customerToken(customer);
        Api res = post("/api/tickets/purchase", cus, body(product, 1, station, null));
        assertNotEquals(0, res.code(), "缺 idempotencyKey 必须被拒: " + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE customer_id=?", customer),
                "被拒时不得留下任何流水");

        // 空串/纯空白同样算缺失（"  " 不能被当成一个有效的键）
        assertNotEquals(0, post("/api/tickets/purchase", cus, body(product, 1, station, "")).code());
        assertNotEquals(0, post("/api/tickets/purchase", cus, body(product, 1, station, "   ")).code());
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE customer_id=?", customer),
                "空白键被拒时也不得留下流水");
    }

    @Test
    @DisplayName("不同幂等键是两笔独立购买，不能被合并成一笔")
    void differentKeysCreateSeparatePayments() {
        long station = createStation("两笔站");
        long customer = createCustomer("两笔客户", "twokey-openid");
        long product = createProduct("两笔水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");

        String cus = customerToken(customer);
        long a = post("/api/tickets/purchase", cus, body(product, 1, station, "key-a"))
                .data().path("paymentId").asLong();
        long b = post("/api/tickets/purchase", cus, body(product, 2, station, "key-b"))
                .data().path("paymentId").asLong();
        assertNotEquals(a, b, "不同幂等键必须是两笔（否则客户想买两次会被吞掉一次）");
        assertEquals(2, intOf("SELECT COUNT(*) FROM payment_record WHERE customer_id=?", customer));
    }

    @Test
    @DisplayName("幂等键按客户隔离：两个客户用同一个 token 各自成一笔，且互不泄露")
    void keyIsScopedByCustomer() {
        long station = createStation("隔离站");
        long alice = createCustomer("Alice", "iso-openid-a");
        long bob = createCustomer("Bob", "iso-openid-b");
        long product = createProduct("隔离水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");

        String shared = "same-token-both-customers";
        long alicePayment = post("/api/tickets/purchase", customerToken(alice),
                body(product, 1, station, shared)).data().path("paymentId").asLong();
        long bobPayment = post("/api/tickets/purchase", customerToken(bob),
                body(product, 1, station, shared)).data().path("paymentId").asLong();

        assertNotEquals(alicePayment, bobPayment,
                "唯一键必须是 (customer_id, idempotency_key)。若只按 token 唯一，"
                        + "Bob 复用同一 token 会直接拿回 Alice 的支付记录（跨客户信息泄露）");
        assertEquals(alicePayment, post("/api/tickets/purchase", customerToken(alice),
                        body(product, 1, station, shared)).data().path("paymentId").asLong(),
                "Alice 重放仍应拿到自己的那一笔");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE customer_id=?", alice));
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE customer_id=?", bob));
    }

    @Test
    @DisplayName("并发同一幂等键：数据库唯一键兜底，最终只落一条流水")
    void concurrentSameKeyCreatesOnePayment() throws Exception {
        long station = createStation("并发站");
        long customer = createCustomer("并发客户", "race-openid");
        long product = createProduct("并发水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");

        String cus = customerToken(customer);
        String key = "race-key";
        String payload = body(product, 5, station, key);

        List<Api> results = fireTogether(List.of(
                () -> post("/api/tickets/purchase", cus, payload),
                () -> post("/api/tickets/purchase", cus, payload),
                () -> post("/api/tickets/purchase", cus, payload)));

        // 关键断言是"只落一条"：并发下允许其中一个成功、其余拿到 code=1「请勿重复提交」，
        // 客户端用同一个 key 重试即会命中幂等分支拿到原流水。这比"三个都成功且返回同一笔"
        // 更保守，但绝不违反唯一性 —— 而唯一性才是钱不出错的前提。
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE customer_id=? AND idempotency_key=?",
                customer, key), "并发同一键只能落一条流水，实际结果=" + results);
        assertTrue(results.stream().anyMatch(Api::isSuccess), "至少要有一个请求成功，实际=" + results);
    }

    // ---------- helpers ----------

    private String body(long productId, int qty, long stationId, String idempotencyKey) {
        String base = "{\"productId\":" + productId + ",\"quantity\":" + qty
                + ",\"paymentMethod\":2,\"stationId\":" + stationId;
        // key 为 null 时**不带该字段**，模拟旧客户端/绕过客户端的调用
        if (idempotencyKey == null) {
            return base + "}";
        }
        return base + ",\"idempotencyKey\":\"" + idempotencyKey + "\"}";
    }

    /** 与 ConcurrencyIntegrationTest.fireTogether 同款：先等所有线程就绪，再统一放开。 */
    private List<Api> fireTogether(List<Callable<Api>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Api>> futures = new ArrayList<>();
        try {
            for (Callable<Api> t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return t.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<Api> out = new ArrayList<>();
            for (Future<Api> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
