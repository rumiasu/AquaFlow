package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存分配的**屏障式**并发验收（返工契约 §5 的 V01 / V02 / V05）。
 *
 * <h3>为什么不能"同时起两个线程碰运气"</h3>
 * <p>契约原话：<i>「用屏障先建旧快照再让另事务提交；Σ预留≤实物，不能只同时起线程碰运气」</i>。
 * 返工 R1 的缺陷形状是：分配决策读的是 REPEATABLE READ 的**旧快照**（普通 SELECT），
 * 而只锁住 {@code inventory} 行并不能让这个 SELECT 变成当前读。要**可复现**地打中它，
 * 必须构造出"被测事务已经建立读视图 → 另一个事务提交 → 被测事务继续按旧值决策"这条时序。</p>
 *
 * <h3>本类怎么造这条时序（不动被测代码）</h3>
 * <ol>
 *   <li>用一条**裸 JDBC 连接**开一个事务 T2：先按 {@code reserveForItem} 同样的取锁顺序锁住
 *       (站,商品) 的 {@code inventory} 行，再写入一条**未提交**的活跃凭据/实物增量
 *       —— 这就是"另一个并发事务已经做完、还没提交"的等价形态；</li>
 *   <li>主线程发起**真实的 HTTP 请求**（被测事务）：它会在同一把行锁上排队，
 *       而 `information_schema.innodb_trx` 里能**看到**它处于 {@code LOCK WAIT}
 *       —— 等到这一刻才放行 T2，时序是确定的，不是碰运气；</li>
 *   <li>放行 T2（提交）→ 被测事务拿到锁继续跑：正确的实现必须**当前读**到 T2 刚提交的那一行。</li>
 * </ol>
 *
 * <p>被测事务确实已建立读视图（这正是缺陷能发生的前提）：下单路径在取锁之前有若干普通读
 * （幂等命中查询、客户/商品/库存缓存、可用量提示），外派路径进 {@code transferForOrder} 前也要
 * 先 {@code requireOrder} 读订单。若把分配决策写回普通 SELECT，这两条用例都会红（旧值 0 或旧值
 * 从未提交的那份），而当前读下它们必须看到 T2 提交后的值。</p>
 */
@DisplayName("库存分配 · 屏障式并发（V01/V02/V05）")
class InventoryReservationBarrierIntegrationTest extends AbstractIntegrationTest {

    private long stationA;
    private long stationB;
    private long product;
    private long product2;
    private long customer;
    private long address;
    private long managerA;
    private long managerB;

    private void seed() {
        stationA = createStation("屏障站A");
        stationB = createStation("屏障站B");
        product = createProduct("瓶装水550ml", 2, "20.00", "0.00", 0, "0.00");
        product2 = createProduct("瓶装水1.5L", 2, "25.00", "0.00", 0, "0.00");
        createInventory(stationA, product, 10);
        createInventoryRecord(stationA, product, 10, "INIT", 0);
        createInventory(stationB, product, 10);
        createInventoryRecord(stationB, product, 10, "INIT", 0);
        createInventory(stationA, product2, 10);
        createInventoryRecord(stationA, product2, 10, "INIT", 0);
        customer = createCustomer("屏障客户", "barrier-openid");
        address = createAddress(customer, "屏障小区1号");
        createCustomerStationConfig(customer, stationA, 1);
        managerA = createStaff("A站站长", "STATION_MANAGER", stationA, 1);
        managerB = createStaff("B站站长", "STATION_MANAGER", stationB, 1);
    }

    private String tokenA() {
        return staffToken(managerA, "STATION_MANAGER", stationA);
    }

    private String tokenB() {
        return staffToken(managerB, "STATION_MANAGER", stationB);
    }

    /* ==================== V01：不同订单并发争同站同商品 ==================== */

    @Test
    @DisplayName("V01：旧快照下的下单——当前读必须看见已提交的预留，Σ预留不得超过实物")
    void orderCreationMustCurrentReadCommittedReservation() throws Exception {
        seed();

        // 另一张"已经在途、但凭据还没提交"的单：走夹具建单 + 建明细，凭据由 T2 在事务里插
        long otherOrder = createOrderFull(customer, address, stationA, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        long otherItem = createOrderItemFull(otherOrder, product, "瓶装水550ml", 8, 8, "20.00", "0.00");

        Connection t2 = openCompetingTx(stationA, product);
        Api created;
        try {
            // 在 T2 里插一条**预留 8 桶**的活跃凭据（未提交）——T2 的时序等价于另一笔并发下单已做完
            insertReservationOn(t2, stationA, product, otherOrder, otherItem, 8);

            // 被测事务：真实下单 8 桶（可用量只剩 2，所以要带 confirmShortage —— 缺货预订是允许的）
            Future<Api> pending = callAsync(() -> placeOrder(8, "barrier-v01", true));
            awaitLockWait(pending);

            t2.commit();
            created = pending.get(30, TimeUnit.SECONDS);
        } finally {
            // 断言失败 / 屏障超时也要放掉这把锁，否则会把行锁泄漏给同类的下一个用例
            //（close() 对未提交事务等价于回滚，对已提交事务是空操作）
            t2.close();
        }
        assertTrue(created.isSuccess(), "当前读之下可用量=10−8=2，应当允许缺货下单，实际=" + created);
        long orderId = longOf("SELECT id FROM orders ORDER BY id DESC LIMIT 1");

        assertEquals(2, intOf("SELECT reserved_qty FROM inventory_reservation "
                        + "WHERE order_item_id=(SELECT id FROM order_item WHERE order_id=?) AND status=1", orderId),
                "必须**当前读**到 T2 刚提交的 8 桶 ⇒ 本单只能预留 2（旧快照会预留 8，Σ=16）");
        assertEquals(10, reservedTotal(stationA, product),
                "Σ活跃预留必须等于实物 10（8+2）；旧实现这里是 16 = 把不存在的货卖了两次");
        assertEquals(10, physical(stationA, product), "下单只预留，不动实物");
        assertEquals(2, intOf("SELECT deducted_qty FROM order_item WHERE order_id=?", orderId),
                "order_item.deducted_qty 是活跃凭据的镜像，也要是 2");
    }

    /* ==================== V02：换站重建凭据时遇到旧快照 ==================== */

    @Test
    @DisplayName("V02：换站重建——目标站的可用量必须当前读，Σ预留不得超过目标站实物")
    void transferMustCurrentReadTargetStationAvailability() throws Exception {
        seed();

        // 被测单：A 站下单 5 桶（HTTP，真实预留 5）
        assertTrue(placeOrder(5, "barrier-v02", false).isSuccess(), "A 站下单 5 桶");
        long orderId = longOf("SELECT id FROM orders ORDER BY id DESC LIMIT 1");
        assertEquals(5, reservedTotal(stationA, product), "A 站先占住 5 桶");

        // 另一张"已经在 B 站履约、凭据还没提交"的单
        long otherOrder = createOrderCrossStation(customer, address, stationA, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00");
        long otherItem = createOrderItemFull(otherOrder, product, "瓶装水550ml", 10, 8, "20.00", "0.00");

        Connection t2 = openCompetingTx(stationB, product);
        Api out;
        try {
            insertReservationOn(t2, stationB, product, otherOrder, otherItem, 8);

            // 被测事务：A 站定向外派给 B（进 transferForOrder 前已读过订单 ⇒ 旧快照已建立）
            Future<Api> pending = callAsync(() -> post("/api/delivery/orders/transfer/" + orderId + "/outsource",
                    tokenA(), "{\"targetStationId\":" + stationB + ",\"reason\":\"屏障用例\"}"));
            awaitLockWait(pending);

            t2.commit();
            out = pending.get(30, TimeUnit.SECONDS);
        } finally {
            t2.close();   // 见 V01 的注释：别把行锁泄漏给下一个用例
        }
        assertTrue(out.isSuccess(), "外派应成功，实际=" + out);

        assertEquals(2, intOf("SELECT reserved_qty FROM inventory_reservation WHERE order_id=? AND status=1", orderId),
                "B 站实物 10 − 已提交的 8 = 2 ⇒ 本单在 B 只能重建 2（旧快照会算出 10 可用 ⇒ 重建 5）");
        assertEquals(10, reservedTotal(stationB, product),
                "B 站 Σ活跃预留不得超过实物 10（旧实现这里是 13）");
        assertEquals(0, reservedTotal(stationA, product), "A 站的旧凭据必须释放");
        assertEquals(10, physical(stationB, product), "换站只搬承诺，不动实物");
        assertEquals(2, intOf("SELECT deducted_qty FROM order_item WHERE order_id=?", orderId),
                "镜像必须跟着换站后的凭据走");
    }

    /* ==================== V02b：补货（入库 → 补位）写入口 ==================== */

    @Test
    @DisplayName("V02b：入库补位 —— 另一笔事务在排队期间提交了实物与预留，补位必须按当前读分配")
    void inboundBackfillMustSeeConcurrentlyCommittedStockAndReservation() throws Exception {
        seed();
        // 把 A 站清零，制造"等货"的现场（流水一起减，保持 V1-4 等式）
        jdbc.update("UPDATE inventory SET quantity=0 WHERE station_id=? AND product_id=?", stationA, product);
        createInventoryRecord(stationA, product, -10, "ADJUST", 0);

        // 等货的单：需求 10、只能预留 0（confirmShortage）
        assertTrue(placeOrder(10, "barrier-v02b", true).isSuccess(), "缺货预订下单");
        long waiting = longOf("SELECT id FROM orders ORDER BY id DESC LIMIT 1");
        assertEquals(0, reservedTotal(stationA, product), "此时没有货可预留");

        // 另一张单的明细（T2 会给它建预留）—— 订单本身用夹具先提交好
        long otherOrder = createOrderFull(customer, address, stationA, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        long otherItem = createOrderItemFull(otherOrder, product, "瓶装水550ml", 20, 20, "20.00", "0.00");

        // T2 = "另一笔已经做完、还没提交的补货+预留"：锁住 inventory 行 → 加 20 实物 → 建 20 预留
        //（在 T2 自己的连接上执行，否则主线程会被 T2 的行锁挡住）
        Connection t2 = openCompetingTx(stationA, product);
        Api inbound;
        try {
            updateOn(t2, "UPDATE inventory SET quantity = quantity + 20 WHERE station_id=? AND product_id=?",
                    stationA, product);
            insertRecordOn(t2, stationA, product, 20, "并发那笔补货（障壁夹具）");
            insertReservationOn(t2, stationA, product, otherOrder, otherItem, 20);

            // 被测事务：站长入库 10 —— 它必须先拿到 inventory 行锁，于是排在 T2 后面
            Future<Api> pending = callAsync(() -> post("/api/inventory/inbound?stationId=" + stationA,
                    tokenA(), "{\"items\":[{\"productId\":" + product + ",\"quantity\":10}]}"));
            awaitLockWait(pending);
            t2.commit();
            inbound = pending.get(30, TimeUnit.SECONDS);
        } finally {
            t2.close();   // 见 V01 的注释：别把行锁泄漏给下一个用例
        }
        assertTrue(inbound.isSuccess(), "入库应成功，实际=" + inbound);

        assertEquals(30, physical(stationA, product), "实物 0 + 20（并发那笔）+ 10（本笔）= 30");
        assertEquals(30, reservedTotal(stationA, product),
                "Σ活跃预留必须等于实物：另一张单 20 + 等货单补齐的 10");
        assertEquals(10, intOf("SELECT reserved_qty FROM inventory_reservation WHERE order_id=? AND status=1", waiting),
                "等货单必须按当前读拿到刚入库的 10 桶");
        assertEquals(10, intOf("SELECT deducted_qty FROM order_item WHERE order_id=?", waiting),
                "镜像要跟着补位一起走");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory i "
                        + "LEFT JOIN (SELECT station_id, product_id, SUM(delta) s FROM inventory_record "
                        + "           GROUP BY station_id, product_id) r "
                        + "  ON r.station_id = i.station_id AND r.product_id = i.product_id "
                        + "WHERE i.quantity <> COALESCE(r.s, 0)"),
                "V1-4 等式：库存数量必须等于库存流水合计");

        // ⚠️ 如实记录这条屏障的**力度**（别把它当旧实现的反例）：入库路径第一条语句就是加锁写，
        //    读视图建立于**取锁之后**，所以"普通 SELECT 求和"在这个注入下也会看到已提交的 20。
        //    它证明的是：补货这个写入口在并发提交下仍满足 Σ预留 ≤ 实物、并把货按需求分给等货的单。
        //    真正能打成旧实现反例的是 V01（下单路径在取锁前有若干普通读，读视图早于那次提交）。
    }

    /* ==================== V05：多商品反序请求 ==================== */

    @Test
    @DisplayName("V05：两张单的商品顺序相反 —— 锁按 product_id 升序取，不构成锁环，两单都成功")
    void reversedProductOrderDoesNotFormLockCycle() throws Exception {
        seed();

        // 甲：[p1, p2]；乙反着写：[p2, p1]。若照请求顺序逐条取锁，这就是一个固定锁环。
        List<Api> results = fireTogether(List.of(
                () -> placeOrderMulti("barrier-v05-a", product, 4, product2, 4),
                () -> placeOrderMulti("barrier-v05-b", product2, 4, product, 4)));

        for (Api r : results) {
            assertEquals(0, r.code(), "反序请求必须都能成功（死锁会回滚其中一个 ⇒ code=500），实际=" + r);
        }
        assertEquals(8, reservedTotal(stationA, product), "p1 上两张单各预留 4");
        assertEquals(8, reservedTotal(stationA, product2), "p2 上两张单各预留 4");
        assertEquals(10, physical(stationA, product), "实物不动");
        assertEquals(10, physical(stationA, product2), "实物不动");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory i "
                        + "LEFT JOIN (SELECT station_id, product_id, SUM(delta) s FROM inventory_record "
                        + "           GROUP BY station_id, product_id) r "
                        + "  ON r.station_id = i.station_id AND r.product_id = i.product_id "
                        + "WHERE i.quantity <> COALESCE(r.s, 0)"),
                "V1-4 等式：库存数量必须等于库存流水合计");
    }

    /* ==================== 工具 ==================== */

    private int physical(long stationId, long productId) {
        return intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", stationId, productId);
    }

    private int reservedTotal(long stationId, long productId) {
        return intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND product_id=? AND status=1", stationId, productId);
    }

    private Api placeOrder(int qty, String key, boolean confirmShortage) {
        return post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + stationA + ",\"addressId\":" + address + ",\"paymentMethod\":2,"
                        + "\"idempotencyKey\":\"" + key + "\""
                        + (confirmShortage ? ",\"confirmShortage\":true" : "")
                        + ",\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}");
    }

    private Api placeOrderMulti(String key, long p1, int q1, long p2, int q2) {
        return post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + stationA + ",\"addressId\":" + address + ",\"paymentMethod\":2,"
                        + "\"idempotencyKey\":\"" + key + "\",\"items\":["
                        + "{\"productId\":" + p1 + ",\"quantity\":" + q1 + "},"
                        + "{\"productId\":" + p2 + ",\"quantity\":" + q2 + "}]}");
    }

    /**
     * T2：另一个"已开始、未提交"的并发事务（裸 JDBC，不经被测代码）。
     * 取锁顺序与被测实现一致：先锁 (站,商品) 的 inventory 行 —— 否则构造出的时序不代表真实竞争。
     */
    private Connection openCompetingTx(long stationId, long productId) throws Exception {
        Connection c = jdbc.getDataSource().getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT quantity FROM inventory WHERE station_id=? AND product_id=? FOR UPDATE")) {
            ps.setLong(1, stationId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "屏障前提：该 (站,商品) 必须有库存行");
            }
        }
        return c;
    }

    private void insertReservationOn(Connection c, long stationId, long productId,
                                     long orderId, long orderItemId, int reservedQty) throws Exception {
        // need_qty/need_time 是 v65 起的 NOT NULL 列：T2 造的凭据也要带上快照（= 真相源的值）
        Integer need = intOf("SELECT quantity FROM order_item WHERE id=?", orderItemId);
        java.time.LocalDateTime needTime = jdbc.queryForObject(
                "SELECT create_time FROM orders WHERE id=?", java.time.LocalDateTime.class, orderId);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO inventory_reservation(order_id, order_item_id, product_id, station_id, "
                        + "need_qty, need_time, reserved_qty, shipped_qty, released_qty, status) "
                        + "VALUES (?,?,?,?,?,?,?,0,0,1)")) {
            ps.setLong(1, orderId);
            ps.setLong(2, orderItemId);
            ps.setLong(3, productId);
            ps.setLong(4, stationId);
            ps.setInt(5, need == null ? reservedQty : need);
            ps.setObject(6, needTime);
            ps.setInt(7, reservedQty);
            ps.executeUpdate();
        }
    }

    /** 在**指定的那条连接**上执行一条写语句（T2 的夹具必须走这里，否则会被 T2 自己的行锁挡住）。 */
    private void updateOn(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * 在 T2 的连接上补一条库存流水。
     * <p>⚠️ 并发那笔"补货"必须**实物与流水成对**（V1-4 等式：`quantity == Σ inventory_record.delta`）——
     * 只加实物不写流水的夹具不是"另一笔合法的补货"，而是一个会打红对账的自造差异（本用例第一版就这么错过）。</p>
     */
    private void insertRecordOn(Connection c, long stationId, long productId, int delta, String note)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO inventory_record(station_id, product_id, delta, type, ref_id, note) "
                        + "VALUES (?,?,?,'INBOUND',NULL,?)")) {
            ps.setLong(1, stationId);
            ps.setLong(2, productId);
            ps.setInt(3, delta);
            ps.setString(4, note);
            ps.executeUpdate();
        }
    }

    /**
     * 等"确实有一笔事务卡在行锁上"再放行 T2 —— 契约明确反对"同时起线程碰运气"：
     * 没有这一步，被测事务可能还没开始、或已经跑完，用例就变成了测 MySQL 而不是测代码。
     *
     * <p>两种失败都要**说清是哪一种**（否则排查成本全落在下一个人身上）：
     * ① 被测请求提前返回了（没进入等待行锁）→ 把它的响应原样贴出来；
     * ② 超时了 → 把 {@code innodb_trx} 快照贴出来。</p>
     */
    private void awaitLockWait(Future<Api> pending) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (blockedOnLock()) {
                return;
            }
            if (pending.isDone()) {
                throw new IllegalStateException("屏障不成立：被测请求没有去等行锁，而是直接返回了 —— " + pending.get()
                        + "；当时的事务快照=" + jdbc.queryForList(
                                "SELECT trx_state, trx_query FROM information_schema.innodb_trx"));
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("屏障超时：20 秒内没有出现等待行锁的事务，本用例前提不成立（不要改成 sleep 硬等）；"
                + "当时的事务快照=" + jdbc.queryForList(
                        "SELECT trx_state, trx_query FROM information_schema.innodb_trx")
                + "；当时的连接快照=" + jdbc.queryForList(
                        "SELECT id, state, INFO FROM information_schema.processlist WHERE command <> 'Sleep'")
                + "；被测线程栈=" + stackDumpOfPendingRequest());
    }

    /**
     * "被测请求确实卡在锁上"的三个可观测信号，**任一**成立即可。
     *
     * <p>⚠️ 本机实测（2026-09-25）：**只看 `innodb_trx.trx_state = 'LOCK WAIT'` 会漏** —— 被测的那条
     * {@code select * from inventory ... for update} 明明被卡住 20 秒，`trx_state` 却始终是 `RUNNING`，
     * 于是屏障永远等不到、用例以"前提不成立"失败（排查靠线程栈才定位到）。所以这里加了两路兜底：
     * ① `innodb_trx` 出现 `LOCK WAIT`；② `performance_schema.data_locks` 出现 `WAITING` 的锁；
     * ③ **连接快照里有一条"不是自己的"查询已跑了 ≥2 秒**（阻塞在锁上的连接就长这样：`command=Query`、
     * `Time` 一直涨）—— 限定在**当前测试库**上，免得被同机别处的长查询误判。</p>
     */
    private boolean blockedOnLock() {
        Integer n = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT') "
                        + "+ (SELECT COUNT(*) FROM performance_schema.data_locks WHERE LOCK_STATUS = 'WAITING') "
                        + "+ (SELECT COUNT(*) FROM information_schema.processlist "
                        + "    WHERE command = 'Query' AND id <> CONNECTION_ID() AND TIME >= 2 "
                        + "      AND DB = DATABASE())", Integer.class);
        return n != null && n > 0;
    }

    /** 屏障超时时把"被测请求卡在哪"直接打出来（没有它只能靠猜，排查成本全落在下一个人身上）。 */
    private String stackDumpOfPendingRequest() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            StackTraceElement[] st = e.getValue();
            boolean relevant = false;
            for (StackTraceElement f : st) {
                if (f.getClassName().startsWith("com.example.aquaflow") || f.getClassName().startsWith("java.net.http")
                        || e.getKey().getName().startsWith("pool-")) {
                    relevant = true;
                    break;
                }
            }
            if (!relevant) {
                continue;
            }
            sb.append("\n  [").append(e.getKey().getName()).append('/').append(e.getKey().getState()).append(']');
            for (int i = 0; i < Math.min(st.length, 14); i++) {
                sb.append("\n    ").append(st[i]);
            }
        }
        return sb.toString();
    }

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
