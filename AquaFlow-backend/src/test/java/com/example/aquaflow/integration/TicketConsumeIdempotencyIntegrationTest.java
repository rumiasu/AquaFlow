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
 * 站长「手工扣票」的幂等（v70，台账 F-24）。
 *
 * <p><b>为什么单独一条用例</b>：{@code POST /api/tickets/consume} 的 {@code orderId} <b>可空</b>，
 * 站长手工扣票时它就是 NULL。这使数据库唯一键
 * {@code uk_ticket_consume(order_id, product_id, source)} <b>零保护</b> ——
 * MySQL 唯一键中 NULL 互不冲突，{@code (NULL, p, '消费')} 可以插无限多条。
 * 于是站长连点两次「扣 10 张」，账户被扣 20 张、批次账被 FIFO 消耗两次，
 * 而旧代码那句 {@code catch (DuplicateKeyException)} 在 orderId 为 NULL 时<b>根本不可能触发</b>。</p>
 *
 * <p><b>本用例最关键的断言不是「流水只落一条」，而是「余额只少一次」</b> ——
 * 流水条数只是手段，余额与批次账才是后果。{@link #sameKeyDeductsOnce()} 与
 * {@link #concurrentSameKeyDeductsOnce()} 两条都在钉这个后果。</p>
 *
 * <p>形状与判据照抄 v33 的 {@code TicketPurchaseIdempotencyIntegrationTest}
 * （在线购票的无订单支付是同一个坑）。</p>
 */
class TicketConsumeIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("同一幂等键重放：余额与批次只扣一次，流水只有一条")
    void sameKeyDeductsOnce() {
        long station = createStation("扣票幂等站");
        long manager = createStaff("扣票站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("扣票客户", "consume-idem-openid");
        long product = createProduct("扣票水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");
        createTicketAccount(customer, station, product, 20);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String key = "consume-idem-key-1";

        // 同一笔扣票意图提交两次（模拟连点 / 网络重试）
        Api first = post("/api/tickets/consume", mgr, body(customer, product, 3, null, key));
        Api second = post("/api/tickets/consume", mgr, body(customer, product, 3, null, key));

        assertEquals(0, first.code(), "首次扣票应成功: " + first);
        assertEquals(0, second.code(), "重放应成功返回同一结果，而不是报错: " + second);

        assertEquals(17, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                        + "AND product_id=?", customer, station, product),
                "同一幂等键只能扣一次（修复前是 14 —— 那正是连点两次被扣 6 张）");
        assertEquals(17, intOf("SELECT remain_qty FROM ticket_lot WHERE customer_id=? AND station_id=? "
                        + "AND product_id=?", customer, station, product),
                "批次账必须与账户余额同步，不能只扣账户不扣批次");
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=? AND product_id=? "
                        + "AND source='消费' AND decrease_qty>0", customer, product),
                "同一幂等键只能落一条消费流水");
        assertEquals(key, jdbc.queryForObject("SELECT idempotency_key FROM ticket_record WHERE customer_id=? "
                        + "AND product_id=? AND source='消费'", String.class, customer, product),
                "幂等键必须落在流水上（这是唯一键能兜底并发的前提）");
    }

    @Test
    @DisplayName("不同幂等键是两笔独立扣票，不能被合并成一笔")
    void differentKeysEachDeductOnce() {
        long station = createStation("两笔扣票站");
        long manager = createStaff("两笔扣票站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("两笔扣票客户", "consume-two-openid");
        long product = createProduct("两笔扣票水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");
        createTicketAccount(customer, station, product, 20);

        String mgr = staffToken(manager, "STATION_MANAGER", station);

        assertEquals(0, post("/api/tickets/consume", mgr, body(customer, product, 3, null, "consume-k-a")).code());
        assertEquals(0, post("/api/tickets/consume", mgr, body(customer, product, 2, null, "consume-k-b")).code());

        assertEquals(15, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer),
                "不同幂等键是两次独立扣票，必须各扣一次（否则站长想扣两次会被吞掉一次）");
        assertEquals(2, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=? AND source='消费'",
                customer));
    }

    @Test
    @DisplayName("缺幂等键必须拒绝：宁可失败出声，也不能静默扣一笔没有保护的票")
    void missingKeyIsRejected() {
        long station = createStation("缺键扣票站");
        long manager = createStaff("缺键扣票站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("缺键扣票客户", "consume-nokey-openid");
        long product = createProduct("缺键扣票水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");
        createTicketAccount(customer, station, product, 20);

        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 完全不传该字段（旧客户端 / 绕过客户端的调用）
        Api absent = post("/api/tickets/consume", mgr, body(customer, product, 3, null, null));
        assertNotEquals(0, absent.code(), "缺 idempotencyKey 必须被拒: " + absent);

        // 空串与纯空白同样算缺失（"  " 不能被当成一个有效的键）
        assertNotEquals(0, post("/api/tickets/consume", mgr, body(customer, product, 3, null, "")).code());
        assertNotEquals(0, post("/api/tickets/consume", mgr, body(customer, product, 3, null, "   ")).code());

        assertEquals(20, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer),
                "被拒时不得动余额");
        assertEquals(20, intOf("SELECT remain_qty FROM ticket_lot WHERE customer_id=?", customer),
                "被拒时不得动批次账");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=?", customer),
                "被拒时不得留下任何流水");

        // 幂等键超过列宽（varchar(64)）也必须给出可读业务错误，而不是数据库层 1062/1406
        assertNotEquals(0, post("/api/tickets/consume", mgr, body(customer, product, 1, null, "x".repeat(65))).code(),
                "超长幂等键必须被拒");
        assertEquals(20, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer));
    }

    @Test
    @DisplayName("幂等键按客户隔离：两个客户用同一个键各自扣一次，且互不串账")
    void keyIsScopedByCustomer() {
        long station = createStation("扣票隔离站");
        long manager = createStaff("扣票隔离站长", "STATION_MANAGER", station, 1);
        long alice = createCustomer("Alice", "consume-iso-openid-a");
        long bob = createCustomer("Bob", "consume-iso-openid-b");
        long product = createProduct("扣票隔离水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");
        createTicketAccount(alice, station, product, 10);
        createTicketAccount(bob, station, product, 10);

        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 同一个 key、同一个商品、同一个站 —— 只有 customer_id 不同
        Api a = post("/api/tickets/consume", mgr, body(alice, product, 4, null, "same-key-both"));
        Api b = post("/api/tickets/consume", mgr, body(bob, product, 4, null, "same-key-both"));

        assertEquals(0, a.code(), "Alice 的扣票应成功: " + a);
        assertEquals(0, b.code(), "Bob 复用同一个 key 也必须成功。唯一键若是单列 (idempotency_key)，"
                + "Bob 会撞键失败 —— 那说明作用域漏了 customer_id: " + b);
        assertEquals(6, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", alice));
        assertEquals(6, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", bob),
                "两个客户各自的余额都要按自己的扣，不能串账");
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=?", alice));
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=?", bob));
    }

    @Test
    @DisplayName("并发同一幂等键：数据库唯一键兜底，余额最终只少一次")
    void concurrentSameKeyDeductsOnce() throws Exception {
        long station = createStation("并发扣票站");
        long manager = createStaff("并发扣票站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("并发扣票客户", "consume-race-openid");
        long product = createProduct("并发扣票水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");
        createTicketAccount(customer, station, product, 20);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String key = "consume-race-key";
        String payload = body(customer, product, 3, null, key);

        List<Api> results = fireTogether(List.of(
                () -> post("/api/tickets/consume", mgr, payload),
                () -> post("/api/tickets/consume", mgr, payload),
                () -> post("/api/tickets/consume", mgr, payload)));

        // 关键断言是"只扣一次"：并发下允许其中一个成功、其余拿到 code=1「请勿重复提交」，
        // 客户端用同一个 key 重试即会命中幂等分支拿到原结果。
        // 输掉竞争的那次**必须整笔回滚**（它的扣减与批次消耗一起撤销）—— 若只吞掉 1062 不抛，
        // 这里会看到 11（扣了三次），而那正是 F-24 描述的缺陷形态。
        assertEquals(17, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer),
                "并发同一键只能扣一次，实际结果=" + results);
        assertEquals(17, intOf("SELECT remain_qty FROM ticket_lot WHERE customer_id=?", customer),
                "批次账也必须只消耗一次，实际结果=" + results);
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=? AND source='消费'",
                customer), "并发同一键只能落一条消费流水，实际结果=" + results);
        assertTrue(results.stream().anyMatch(Api::isSuccess), "至少要有一个请求成功，实际=" + results);
    }

    @Test
    @DisplayName("带 orderId 的手工扣票同样受幂等键保护：撞 uk_ticket_consume 时必须回滚、不许只吞异常")
    void orderBoundConsumeAlsoRollsBack() {
        long station = createStation("带单扣票站");
        long manager = createStaff("带单扣票站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("带单扣票客户", "consume-order-openid");
        long product = createProduct("带单扣票水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");
        createTicketAccount(customer, station, product, 20);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        // ticket_record.order_id 无外键，随便给一个不存在的订单号即可构造"订单内扣票"形态
        long fakeOrderId = 987654321L;

        assertEquals(0, post("/api/tickets/consume", mgr,
                body(customer, product, 2, fakeOrderId, "consume-with-order-1")).code());
        assertEquals(18, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer));

        // 第 2 笔换 key 但同一个 orderId：唯一键 uk_ticket_consume(order_id, product_id, '消费') 会撞 1062。
        // ⚠️ 这里钉的是"撞键必须整笔回滚"——旧实现把 DuplicateKeyException 吞掉后照样提交，
        // 结果是账户少 2 张、却没有任何流水能解释（钱货对不上）。判据是「有没有客户端幂等键」，
        // 不是「有没有订单」，所以带 orderId 也走同一条回滚语义。
        Api dup = post("/api/tickets/consume", mgr,
                body(customer, product, 2, fakeOrderId, "consume-with-order-2"));
        assertNotEquals(0, dup.code(), "同一订单同一商品重复扣票必须被拒: " + dup);
        assertEquals(18, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer),
                "被拒绝的那一笔必须整笔回滚 —— 余额不能再少 2 张（旧实现是 16，且没有对应流水）");
        assertEquals(18, intOf("SELECT remain_qty FROM ticket_lot WHERE customer_id=?", customer),
                "批次消耗也必须一并回滚");
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=? AND source='消费'",
                customer), "只能有一条消费流水");
    }

    // ---------- helpers ----------

    /**
     * 请求体。{@code idempotencyKey} 为 {@code null} 时**不带该字段**（模拟旧客户端/绕过客户端的调用），
     * 空串与空白则原样发送 —— 两种都要被拒，但走的是不同判据（缺失 vs 空白）。
     */
    private String body(long customerId, long productId, int qty, Long orderId, String idempotencyKey) {
        StringBuilder sb = new StringBuilder("{\"customerId\":").append(customerId)
                .append(",\"productId\":").append(productId)
                .append(",\"quantity\":").append(qty);
        if (orderId != null) {
            sb.append(",\"orderId\":").append(orderId);
        }
        if (idempotencyKey != null) {
            sb.append(",\"idempotencyKey\":\"").append(idempotencyKey).append('"');
        }
        return sb.append('}').toString();
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
