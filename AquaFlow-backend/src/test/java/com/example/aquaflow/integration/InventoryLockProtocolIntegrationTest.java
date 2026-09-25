package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存锁协议与补位数据视图的反例（二次收口契约 §3 的前四行 + B1/B2）。
 *
 * <h3>统一锁序（被这些用例钉住的那条）</h3>
 * <pre>
 *   orders（调用方已有） → inventory（按 (站,商品) 升序） → inventory_reservation（当前读） → order_item（镜像）
 * </pre>
 * 二次验收 B1 指出上一版是"补位/预留先库存、出库/换站/释放先凭据"两套相反的顺序 ——
 * 「入库对完成配送」能构造出固定锁环。现在的断言方式：**让两个不同命令各自停在第一把锁上**，
 * 并证明它们等的是**同一张表（`inventory`）上、由同一个阻塞事务持有的锁** ——
 * 如果哪个命令还是先锁凭据，等待的锁对象会变成 `inventory_reservation`，断言立刻失败。
 *
 * <p>⚠️ 锁证据绑定到"具体事务 + 具体锁对象"（{@code performance_schema.data_lock_waits} 里
 * 阻塞方 trx_id = 我们那条裸连接的 trx_id，等待的锁对象 = 指定表），不用"实例里有锁等待"糊过去
 * （二次收口契约 §3 末段）。</p>
 */
@DisplayName("库存锁协议与补位视图 · 反例（B1/B2）")
class InventoryLockProtocolIntegrationTest extends AbstractIntegrationTest {

    private long stationA;
    private long stationB;
    private long product;
    private long product2;
    private long customer;
    private long address;
    private long managerA;
    private long managerB;
    private long riderA;
    private long riderB;

    private void seed() {
        stationA = createStation("锁协议站A");
        stationB = createStation("锁协议站B");
        product = createProduct("瓶装水550ml", 2, "20.00", "0.00", 0, "0.00");
        product2 = createProduct("瓶装水1.5L", 2, "25.00", "0.00", 0, "0.00");
        createInventory(stationA, product, 10);
        createInventoryRecord(stationA, product, 10, "INIT", 0);
        createInventory(stationA, product2, 10);
        createInventoryRecord(stationA, product2, 10, "INIT", 0);
        createInventory(stationB, product, 10);
        createInventoryRecord(stationB, product, 10, "INIT", 0);
        createInventory(stationB, product2, 10);
        createInventoryRecord(stationB, product2, 10, "INIT", 0);
        customer = createCustomer("锁协议客户", "lock-openid");
        address = createAddress(customer, "锁协议小区1号");
        createCustomerStationConfig(customer, stationA, 1);
        createCustomerStationConfig(customer, stationB, 1);
        managerA = createStaff("A站站长", "STATION_MANAGER", stationA, 1);
        managerB = createStaff("B站站长", "STATION_MANAGER", stationB, 1);
        riderA = createStaff("A站配送员", "DELIVERY", stationA, 1);
        // ⚠️ 必须给履约站也建配送员：判权看的是**库里的站别**（AuthInterceptor 每次请求回查员工行、
        // 覆盖 token 里的旧值），拿 A 站员工的 token 去操作 B 站履约的单会先被"无权操作他站订单"挡掉，
        // 根本走不到库存那一步（本用例第一版就这么踩过：屏障等不到锁，因为请求早就返回了）。
        riderB = createStaff("B站配送员", "DELIVERY", stationB, 1);
    }

    private String tokenA() {
        return staffToken(managerA, "STATION_MANAGER", stationA);
    }

    private String tokenB() {
        return staffToken(managerB, "STATION_MANAGER", stationB);
    }

    private Api placeOrder(long stationId, int qty, String key, boolean confirmShortage) {
        return post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + stationId + ",\"addressId\":" + address + ",\"paymentMethod\":2,"
                        + "\"idempotencyKey\":\"" + key + "\""
                        + (confirmShortage ? ",\"confirmShortage\":true" : "")
                        + ",\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}");
    }

    private long lastOrderId() {
        return longOf("SELECT id FROM orders ORDER BY id DESC LIMIT 1");
    }

    private int physical(long stationId, long productId) {
        return intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", stationId, productId);
    }

    private int reservedTotal(long stationId, long productId) {
        return intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND product_id=? AND status=1", stationId, productId);
    }

    private int reservedOf(long orderId) {
        return intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE order_id=? AND status=1", orderId);
    }

    private void assertInventoryMatchesRecords(String when) {
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory i "
                        + "LEFT JOIN (SELECT station_id, product_id, SUM(delta) s FROM inventory_record "
                        + "           GROUP BY station_id, product_id) r "
                        + "  ON r.station_id = i.station_id AND r.product_id = i.product_id "
                        + "WHERE i.quantity <> COALESCE(r.s, 0)"),
                when + "：库存数量必须等于库存流水合计（对账等式 V1-4）");
    }

    /* ==================== 反例 1：入库 对 完成配送 ==================== */

    @Test
    @DisplayName("B1-①：入库与完成配送停在**同一把第一锁**（inventory 行）上 —— 无固定锁环、两笔都不 500")
    void inboundAndCompleteWaitOnTheSameFirstLock() throws Exception {
        seed();
        Api created = placeOrder(stationA, 2, "lock-b1-a", false);
        assertTrue(created.isSuccess(), "下单 2 桶，实际=" + created);
        long order = lastOrderId();
        assertEquals(0, post("/api/delivery/orders/" + order + "/accept",
                staffToken(riderA, "DELIVERY", stationA), "{}").code(), "接单 → 配送中(2)");

        // 裸事务先占住 (A,商品) 的库存行：两个命令的第一把锁都应该是它
        Connection blocker = openRawTransaction();
        Api inbound;
        Api complete;
        try {
            lockRowsRaw(blocker, "SELECT quantity FROM inventory WHERE station_id=? AND product_id=? FOR UPDATE",
                    stationA, product);
            long blockerTrx = rawTrxId(blocker);

            Future<Api> fInbound = callAsync(() -> post("/api/inventory/inbound?stationId=" + stationA, tokenA(),
                    "{\"items\":[{\"productId\":" + product + ",\"quantity\":5}]}"));
            Future<Api> fComplete = callAsync(() -> post("/api/delivery/orders/" + order + "/complete",
                    tokenA(), "{\"collected\":true}"));

            // ★ 关键断言：两个请求都在等 `inventory` 表上、由同一个阻塞事务持有的锁
            awaitBlockedBy(blockerTrx, "inventory", 2, 20_000);

            blocker.commit();
            inbound = fInbound.get(30, TimeUnit.SECONDS);
            complete = fComplete.get(30, TimeUnit.SECONDS);
        } finally {
            blocker.close();
        }

        for (Api r : List.of(inbound, complete)) {
            assertTrue(r.code() == 0 || r.code() == 1,
                    "合理的两笔操作只允许成功或可读冲突，不能 500/死锁，实际=" + r);
        }
        assertTrue(complete.isSuccess(), "完成配送应成功，实际=" + complete);
        assertTrue(inbound.isSuccess(), "入库应成功，实际=" + inbound);
        // 无论谁先拿到锁：实物最终 = 10 + 5(入库) − 2(出库) = 13，且预留归零、账平
        assertEquals(13, physical(stationA, product), "实物账必须一致（与执行先后无关）");
        assertEquals(0, reservedTotal(stationA, product), "出库后不再有活跃预留");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record WHERE type='CONSUME' AND ref_id=?", order),
                "出库流水恰好一条");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record WHERE type='INBOUND' AND delta=5"),
                "入库流水恰好一条");
        assertInventoryMatchesRecords("B1-① 之后");
    }

    /* ==================== 反例 2：新预留 对 释放（取消） ==================== */

    @Test
    @DisplayName("B1-②：新订单预留与取消释放停在**同一把第一锁**上 —— 不互等、账一致")
    void newReservationAndCancelWaitOnTheSameFirstLock() throws Exception {
        seed();
        Api first = placeOrder(stationA, 10, "lock-b1-b", false);
        assertTrue(first.isSuccess(), "老单占满 10 桶，实际=" + first);
        long oldOrder = lastOrderId();

        Connection blocker = openRawTransaction();
        Api created;
        Api cancelled;
        try {
            lockRowsRaw(blocker, "SELECT quantity FROM inventory WHERE station_id=? AND product_id=? FOR UPDATE",
                    stationA, product);
            long blockerTrx = rawTrxId(blocker);

            Future<Api> fCreate = callAsync(() -> placeOrder(stationA, 5, "lock-b1-b-new", true));
            Future<Api> fCancel = callAsync(() -> put("/api/orders/" + oldOrder + "/customer-cancel",
                    customerToken(customer), null));

            awaitBlockedBy(blockerTrx, "inventory", 2, 20_000);

            blocker.commit();
            created = fCreate.get(30, TimeUnit.SECONDS);
            cancelled = fCancel.get(30, TimeUnit.SECONDS);
        } finally {
            blocker.close();
        }

        for (Api r : List.of(created, cancelled)) {
            assertTrue(r.code() == 0 || r.code() == 1, "只允许成功或可读冲突，实际=" + r);
        }
        assertTrue(cancelled.isSuccess(), "取消应成功，实际=" + cancelled);
        assertTrue(created.isSuccess(), "下单应成功，实际=" + created);
        long newOrder = lastOrderId();
        assertTrue(newOrder != oldOrder, "应当是新单");
        // 不变量：无论两笔的先后，Σ活跃预留 ≤ 实物，且实物没被这两笔改过
        assertEquals(10, physical(stationA, product), "取消/预留都不该动实物");
        assertTrue(reservedTotal(stationA, product) <= 10,
                "Σ活跃预留不得超过实物，实际=" + reservedTotal(stationA, product));
        assertTrue(reservedOf(newOrder) == 0 || reservedOf(newOrder) == 5,
                "新单要么拿到 5（取消先执行）、要么 0（下单先执行，缺货待补），实际=" + reservedOf(newOrder));
        assertInventoryMatchesRecords("B1-② 之后");
    }

    /* ==================== 反例 3：发现 → 加锁之间资源集合变了（重新验证） ==================== */

    @Test
    @DisplayName("B1-③：加锁窗口里凭据被换站 ⇒ 必须可读拒绝，且什么都不改（不许照旧集合继续）")
    void resourceSetChangeDuringLockWindowIsRejected() throws Exception {
        seed();
        // 跨站单：归属 A、履约 B，凭据在 B（履约站）
        long order = createOrderCrossStation(customer, address, stationA, stationB, product,
                2 /* 配送中 */, 1, 2, "40.00", "0.00", "40.00");
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", riderB, order);
        long item = createReservedItem(order, product, "瓶装水550ml", 2, "20.00", "0.00");
        assertEquals(2, reservedTotal(stationB, product), "前置：凭据在履约站 B");

        Connection mover = openRawTransaction();
        Api done;
        try {
            // 换站方按协议升序锁 (A,商品) 与 (B,商品)
            lockRowsRaw(mover, "SELECT quantity FROM inventory WHERE station_id=? AND product_id=? FOR UPDATE",
                    stationA, product);
            lockRowsRaw(mover, "SELECT quantity FROM inventory WHERE station_id=? AND product_id=? FOR UPDATE",
                    stationB, product);
            long moverTrx = rawTrxId(mover);

            // 被测：B 站完成配送 —— 它先"发现"凭据在 B，然后要锁 (B,商品) → 排队
            Future<Api> pending = callAsync(() -> post("/api/delivery/orders/" + order + "/complete",
                    staffToken(riderB, "DELIVERY", stationB), "{}"));
            awaitBlockedBy(moverTrx, "inventory", 1, 20_000);

            // 排队期间，换站方把凭据从 B 搬到 A（合法操作：旧站释放 + 新站重建）
            executeRaw(mover, "UPDATE inventory_reservation SET status=3, released_qty=reserved_qty "
                    + "WHERE order_item_id=? AND status=1", item);
            insertRaw(mover, "INSERT INTO inventory_reservation(order_id, order_item_id, product_id, station_id, "
                            + "need_qty, need_time, reserved_qty, shipped_qty, released_qty, status) "
                            + "VALUES (?,?,?,?,2,NOW(),2,0,0,1)",
                    order, item, product, stationA);
            mover.commit();

            done = pending.get(30, TimeUnit.SECONDS);
        } finally {
            mover.close();
        }

        assertFalse(done.isSuccess(), "资源集合已经变了，不该照旧集合出库，实际=" + done);
        assertEquals(1, done.code(), "必须是可读冲突（code=1），不是 500，实际=" + done);
        assertTrue(done.message().contains("变更"), "文案要说明发生了并发变更，实际=" + done.message());
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "被拒后订单状态不变");
        assertEquals(10, physical(stationB, product), "被拒后不得扣 B 站的货");
        assertEquals(10, physical(stationA, product), "被拒后不得扣 A 站的货");
        assertEquals(2, reservedTotal(stationA, product), "凭据仍留在换站后的 A 站（那是并发方做的事）");
        assertEquals(0, reservedTotal(stationB, product));
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_record WHERE ref_id=? AND type='CONSUME'", order),
                "被拒后不得留下出库流水");
    }

    /* ==================== 反例 4：相向换站 + 商品反序 ==================== */

    @Test
    @DisplayName("B1-④：A→B 与 B→A 相向换站（商品反序）—— 稳定锁序、无死锁、凭据与履约站一致")
    void oppositeTransfersWithReversedProductsDoNotDeadlock() throws Exception {
        seed();
        // 单 X：两个商品（p1,p2）挂在 B；单 Y：反序（p2,p1）挂在 A —— 两笔外派要锁同一组 (站,商品)
        long orderX = createOrderCrossStation(customer, address, stationA, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00");
        long itemX1 = createReservedItem(orderX, product, "瓶装水550ml", 2, "20.00", "0.00");
        long itemX2 = createReservedItem(orderX, product2, "瓶装水1.5L", 2, "25.00", "0.00");
        long orderY = createOrderCrossStation(customer, address, stationB, stationA, product2,
                1, 1, 2, "40.00", "0.00", "40.00");
        long itemY1 = createReservedItem(orderY, product2, "瓶装水1.5L", 2, "25.00", "0.00");
        long itemY2 = createReservedItem(orderY, product, "瓶装水550ml", 2, "20.00", "0.00");
        assertEquals(2, reservedTotal(stationB, product), "X 的 p1 在 B");
        assertEquals(2, reservedTotal(stationA, product2), "Y 的 p2 在 A");

        // 相向：X 从 B 外派回 A（B 站操作）；Y 从 A 外派回 B（A 站操作）
        List<Api> results = fireTogether(List.of(
                () -> post("/api/delivery/orders/transfer/" + orderX + "/outsource", tokenB(),
                        "{\"targetStationId\":" + stationA + ",\"reason\":\"相向换站\"}"),
                () -> post("/api/delivery/orders/transfer/" + orderY + "/outsource", tokenA(),
                        "{\"targetStationId\":" + stationB + ",\"reason\":\"相向换站\"}")));

        for (Api r : results) {
            assertEquals(0, r.code(), "相向换站必须都成功（死锁会回滚其中一个 ⇒ 500/冲突），实际=" + r);
        }
        // 凭据必须与各自的履约站一致：X（履约站 A）的 p1/p2 都在 A；Y（履约站 B）的都在 B
        assertEquals(2, reservedTotal(stationA, product), "X 的 p1 应搬回 A");
        assertEquals(2, reservedTotal(stationA, product2), "X 的 p2 也应搬回 A");
        assertEquals(2, reservedTotal(stationB, product), "Y 的 p1 应搬到 B");
        assertEquals(2, reservedTotal(stationB, product2), "Y 的 p2 应搬到 B");
        assertEquals(4, intOf("SELECT COUNT(*) FROM inventory_reservation WHERE status=1"),
                "四条明细各一份活跃凭据");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_reservation r JOIN orders o ON o.id = r.order_id "
                        + "WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id)"),
                "活跃凭据的站别必须等于该单当前履约站（E13 的判据）");
        assertEquals(10, physical(stationA, product), "换站只搬承诺，不动实物");
        assertEquals(10, physical(stationB, product), "换站只搬承诺，不动实物");
        assertTrue(itemX1 > 0 && itemX2 > 0 && itemY1 > 0 && itemY2 > 0, "夹具明细都要建出来");
        assertInventoryMatchesRecords("B1-④ 之后");
    }

    /* ==================== 反例 5（B2）：新等待单晚于旧快照插入 ==================== */

    @Test
    @DisplayName("B2：取消方先建快照、新单在排队期间提交 —— 补位必须把货分给新单（不许当成 need=0 跳过）")
    void backfillSeesOrderCommittedAfterSnapshot() throws Exception {
        seed();
        // 老单占满 10 桶（它会被取消，释放出 10）
        Api first = placeOrder(stationA, 10, "lock-b2-old", false);
        assertTrue(first.isSuccess(), "老单占满 10，实际=" + first);
        long oldOrder = lastOrderId();

        Connection newOrderTx = openRawTransaction();
        Api cancelled;
        int recordsBefore = intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=?", stationA);
        try {
            // 新单的事务：先锁库存行（= 新模型里下单的第一把锁），再插订单/明细/0 预留凭据
            lockRowsRaw(newOrderTx, "SELECT quantity FROM inventory WHERE station_id=? AND product_id=? FOR UPDATE",
                    stationA, product);
            long trx = rawTrxId(newOrderTx);
            long newOrder = insertRaw(newOrderTx,
                    "INSERT INTO orders(customer_id, address_id, quantity, source, status, payment_status, "
                            + "payment_method, station_id, delivery_station_id, product_id, water_amount, "
                            + "deposit_amount, total_amount, create_time) "
                            + "VALUES (?,?,1,3,1,1,2,?,?,?,0.00,0.00,0.00,NOW())",
                    customer, address, stationA, stationA, product);
            long newItem = insertRaw(newOrderTx,
                    "INSERT INTO order_item(order_id, product_id, product_name_snapshot, price, quantity, "
                            + "deposit, subtotal, deducted_qty) VALUES (?,?,?,20.00,5,0.00,100.00,0)",
                    newOrder, product, "瓶装水550ml");
            // 可用量 0（被老单占满）⇒ 新单预留 0，但它必须留下"我在等货"的凭据（need_qty = 5）
            insertRaw(newOrderTx,
                    "INSERT INTO inventory_reservation(order_id, order_item_id, product_id, station_id, "
                            + "need_qty, need_time, reserved_qty, shipped_qty, released_qty, status) "
                            + "VALUES (?,?,?,?,5,NOW(),0,0,0,1)",
                    newOrder, newItem, product, stationA);

            // 被测：取消老单（它在排队前就读过订单/支付等数据 ⇒ 读视图早于下面这次提交建立）
            Future<Api> pending = callAsync(() -> put("/api/orders/" + oldOrder + "/customer-cancel",
                    customerToken(customer), null));
            awaitBlockedBy(trx, "inventory", 1, 20_000);

            newOrderTx.commit();   // ★ 新单的订单 + 明细 + 凭据在同一刻提交
            cancelled = pending.get(30, TimeUnit.SECONDS);

            // 断言：取消释放出的 10 桶必须按当前读补给"刚提交的新单"
            assertTrue(cancelled.isSuccess(), "取消应成功，实际=" + cancelled);
            assertEquals(5, reservedOf(newOrder),
                    "新单需求 5 必须拿到 5 —— 旧实现用普通 join 读需求，看不到刚提交的明细 ⇒ 会当成 need=0 跳过");
            assertEquals(5, intOf("SELECT deducted_qty FROM order_item WHERE id=?", newItem), "镜像同步为 5");
            assertEquals(5, reservedTotal(stationA, product), "Σ活跃预留 = 新单那 5（老单已释放）");
            assertEquals(10, physical(stationA, product), "释放/补位都不写实物流水、也不动实物");
            assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=? AND type='INBOUND'",
                    stationA), "不得凭空多记入库流水（期初那条是 INIT，不是 INBOUND）");
            assertEquals(recordsBefore, intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=?", stationA),
                    "取消 + 补位**不得新增任何实物流水**；当前流水="
                            + jdbc.queryForList("SELECT type, delta, note FROM inventory_record WHERE station_id=?",
                                    stationA));
            assertInventoryMatchesRecords("B2 之后");
        } finally {
            newOrderTx.close();
        }
    }

    /* ==================== 工具 ==================== */

    private Future<Api> callAsync(Callable<Api> task) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Api> f = pool.submit(task);
        pool.shutdown();
        return f;
    }

    private List<Api> fireTogether(List<Callable<Api>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Api>> futures = new java.util.ArrayList<>();
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
            List<Api> out = new java.util.ArrayList<>();
            for (Future<Api> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
