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

/**
 * 并发一致性回归。
 *
 * <p>这三个场景的共同点：<b>「先查状态、再改状态」在并发下必然双赢</b>。</p>
 * <ul>
 *   <li>接单：两个请求都读到「待配送」，都判定可接 → 都写配送员，后写覆盖先写。</li>
 *   <li>确认收款：两个请求都读到「待收款」，都判定可确认 → 押金被入账两次。</li>
 *   <li>纯还桶：两个请求都读到 over=0、占用=1，都判定「还 1 ≤ 持有 1」→ over 被冲成 -2，
 *       占用变成 -1，物理上不可能——桶账被冲穿。</li>
 * </ul>
 *
 * <p>所以断言不只是「恰好一个成功」，还要看<b>库里的最终值</b>：只对了一个返回值、
 * 库里却写了两次，那是最坏的结果。</p>
 */
@DisplayName("Phase B · 并发一致性（接单/收款/退桶）")
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 0, "0.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /**
     * 把任务同时放出去：先用 {@code ready} 等所有线程都就绪，再统一放开 {@code start}，
     * 保证它们真的撞在同一时刻，而不是被线程池调度顺序错开。
     */
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

    private long successCount(List<Api> results) {
        return results.stream().filter(Api::isSuccess).count();
    }

    @Test
    @DisplayName("两个并发接单请求：只有一个成功，状态只前进一次")
    void concurrentAcceptOrder_onlyOneWins() throws Exception {
        seed();
        long order = createOrder(customer, addr, station, product, 1 /* 待配送 */, 0);
        String token = mgrToken();

        List<Api> results = fireTogether(List.of(
                () -> put("/api/orders/" + order + "/status?status=2", token, null),
                () -> put("/api/orders/" + order + "/status?status=2", token, null)));

        assertEquals(1L, successCount(results), "并发接单应恰好一个成功，实际=" + results);
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "状态应只前进一次到「配送中」");
    }

    @Test
    @DisplayName("两个并发确认收款请求：只有一个成功，只产生一条已付流水")
    void concurrentCashConfirm_onlyOneWins() throws Exception {
        seed();
        long order = createOrderFull(customer, addr, station, product,
                1 /* 待配送 */, 0, 2 /* 现金 */, "40.00", "0.00", "40.00", false, 0);
        long paymentId = createPaymentRecord(order, customer, station, "40.00", 2 /* 现金 */, 1 /* 待收款 */);
        String token = mgrToken();

        List<Api> results = fireTogether(List.of(
                () -> put("/api/payments/" + paymentId + "/cash-confirm", token, null),
                () -> put("/api/payments/" + paymentId + "/cash-confirm", token, null)));

        assertEquals(1L, successCount(results), "并发确认收款应恰好一个成功，实际=" + results);
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", order),
                "只应存在一条已付流水");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "订单应已付");
    }

    @Test
    @DisplayName("两个并发纯还桶请求：只能冲减一次 over（不得超还）")
    void concurrentPureReturn_onlyOneWins() throws Exception {
        seed();
        createBarrelLot("DP-CC-1", customer, station, product, "30.00", 1, 1);
        createBarrelAsset(customer, station, product, 1, "30.00");

        String token = mgrToken();
        List<Api> results = fireTogether(List.of(
                () -> post("/api/barrels/return-empty", token,
                        "{\"customerId\":" + customer + ",\"clientToken\":\"cc-A\","
                                + "\"items\":[{\"productId\":" + product + ",\"qty\":1}]}"),
                () -> post("/api/barrels/return-empty", token,
                        "{\"customerId\":" + customer + ",\"clientToken\":\"cc-B\","
                                + "\"items\":[{\"productId\":" + product + ",\"qty\":1}]}")));

        assertEquals(1L, successCount(results), "并发纯还桶应恰好一个成功，实际=" + results);
        assertEquals(-1, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "over 只能被冲减一次（-1），超还即为账实不符");
    }
}
