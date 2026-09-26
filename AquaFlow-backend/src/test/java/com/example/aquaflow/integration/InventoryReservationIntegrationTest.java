package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存预留与履约凭据（2026-09-25 架构评审问题 4）的验收算例。
 *
 * <p>设计正本：{@code docs/design/28-库存预留与履约凭据.md}（§8 的 E1–E9）。两个被修掉的真实场景：</p>
 * <ul>
 *   <li><b>跨站外派后取消</b>：旧实现"下单扣 A、取消按当时履约站 B 回补" ⇒ 实测 A=8、B=12
 *       （A 少掉的永不回来、B 凭空多 2），而对账等式抓不到（缺的事件两边同时缺）。</li>
 *   <li><b>缺货下单</b>：库存 3 下单 10，旧实现只记 {@code deducted_qty=3}，剩下 7 桶**永不落账**
 *       —— 补货后完成配送也不补扣。</li>
 * </ul>
 *
 * <p>新模型：<b>下单预留</b>（占可用量、不动实物）→ <b>入库按 FIFO 补预留</b> →
 * <b>完成配送出库</b>（实物 −，锚定当时履约站；预留不足直接拒绝）→ <b>换站搬凭据</b> →
 * <b>取消释放</b>（不回补库存）。</p>
 *
 * <p>本类刻意用**非桶装**商品（不涉押金/桶权益），把变量集中在"库存归属"这一件事上；
 * 桶账与欠桶的回归在别的用例里。</p>
 */
@DisplayName("库存预留与履约凭据（问题4）")
class InventoryReservationIntegrationTest extends AbstractIntegrationTest {

    /* ==================== 夹具 ==================== */

    private long station;
    private long product;
    private long customer;
    private long address;
    private long manager;
    private long rider;

    /** 建一个"非桶装 + 已上架有库存 + 客户可货到付款"的最小环境。 */
    private void seed(int initialStock) {
        station = createStation("预留站A");
        product = createProduct("瓶装水550ml", 2, "20.00", "0.00", 0, "0.00");
        createInventory(station, product, initialStock);
        // 期初流水：让「quantity == Σ inventory_record.delta」这条对账等式从一开始就成立
        createInventoryRecord(station, product, initialStock, "INIT", 0);
        customer = createCustomer("预留客户", "resv-openid");
        address = createAddress(customer, "预留小区1号");
        createCustomerStationConfig(customer, station, 1);   // 开通货到付款，便于走真实下单
        manager = createStaff("预留站长", "STATION_MANAGER", station, 1);
        rider = createStaff("预留配送员", "DELIVERY", station, 1);
    }

    private Api placeOrder(int qty, String key) {
        return placeOrder(qty, key, false);
    }

    private Api placeOrder(int qty, String key, boolean confirmShortage) {
        return post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + station + ",\"addressId\":" + address + ",\"paymentMethod\":2,"
                        + "\"idempotencyKey\":\"" + key + "\"" + (confirmShortage ? ",\"confirmShortage\":true" : "")
                        + ",\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}");
    }

    private long lastOrderId() {
        return longOf("SELECT id FROM orders ORDER BY id DESC LIMIT 1");
    }

    private String managerToken() {
        return staffToken(manager, "STATION_MANAGER", station);
    }

    private int qty() {
        return intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", station, product);
    }

    private int reserved() {
        return intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND product_id=? AND status=1", station, product);
    }

    private int activeReservations(long orderId) {
        return intOf("SELECT COUNT(*) FROM inventory_reservation WHERE order_id=? AND status=1", orderId);
    }

    /* ==================== E1 同站完整履约 ==================== */

    @Test
    @DisplayName("E1：下单只预留不动实物；完成配送才出库（锚定履约站）")
    void sameStationHappyPath_reserveThenShip() {
        seed(10);

        Api created = placeOrder(2, "resv-e1");
        assertTrue(created.isSuccess(), "下单应成功，实际=" + created);
        long order = lastOrderId();

        // 下单：实物**没动**，凭据占住了 2 桶
        assertEquals(10, qty(), "下单不得再扣实物（旧实现这里是 8）");
        assertEquals(2, reserved(), "必须留下一条预留凭据");
        assertEquals(2, intOf("SELECT deducted_qty FROM order_item WHERE order_id=?", order),
                "order_item.deducted_qty 的口径已改为『下单实际预留在库量』");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_record WHERE ref_id=? AND type='CONSUME'", order),
                "下单不得写 CONSUME 流水（实物没动）");

        // 接单 → 完成配送（非桶装：没有回桶核对，完成即出库）
        assertEquals(0, post("/api/delivery/orders/" + order + "/accept",
                staffToken(rider, "DELIVERY", station), "{}").code(), "配送员接单");
        Api done = post("/api/delivery/orders/" + order + "/complete",
                staffToken(rider, "DELIVERY", station), "{}");
        assertTrue(done.isSuccess(), "完成配送应成功，实际=" + done);

        assertEquals(8, qty(), "完成配送才出库：10 − 2 = 8");
        assertEquals(0, activeReservations(order), "凭据要翻成已出库");
        assertEquals(2, intOf("SELECT shipped_qty FROM inventory_reservation WHERE order_id=?", order));
        assertEquals(-2, intOf("SELECT COALESCE(SUM(delta),0) FROM inventory_record "
                + "WHERE ref_id=? AND type='CONSUME'", order), "出库必须写一条 -2 的 CONSUME 流水");
        assertInventoryMatchesRecords("E1 完成后");
    }

    /* ==================== E2 + E8 缺货补齐与完成时的硬门槛 ==================== */

    @Test
    @DisplayName("E2/E8：缺货下单 → 完成被拒 → 入库补预留 → 再完成成功（那 7 桶不再凭空消失）")
    void shortageOrder_backfilledByInbound_thenShippable() {
        seed(3);

        Api created = placeOrder(10, "resv-e2", true);
        assertTrue(created.isSuccess(), "确认缺货后应能下单，实际=" + created);
        long order = lastOrderId();

        assertEquals(3, qty(), "下单不动实物");
        assertEquals(3, reserved(), "只预留到可用量（3）");
        assertEquals(7, intOf("SELECT COALESCE(SUM(oi.quantity - r.reserved_qty),0) FROM inventory_reservation r "
                + "JOIN order_item oi ON oi.id = r.order_item_id WHERE r.order_id=? AND r.status=1", order),
                "缺 7 桶必须被凭据记下来（旧实现这 7 桶根本不落账）");

        // 接单后直接完成 → 必须被拦（E8：预留不足不许静默少扣）
        assertEquals(0, post("/api/delivery/orders/" + order + "/accept",
                staffToken(rider, "DELIVERY", station), "{}").code(), "配送员接单");
        Api blocked = post("/api/delivery/orders/" + order + "/complete",
                staffToken(rider, "DELIVERY", station), "{}");
        assertFalse(blocked.isSuccess(), "预留不足时必须拒绝完成，实际=" + blocked);
        assertTrue(blocked.message().contains("还缺"), "拒绝原因要说清还缺几桶（C3：可读文案），实际=" + blocked.message());
        assertTrue(blocked.message().contains(String.valueOf(order)), "拒绝文案要带订单号，实际=" + blocked.message());
        assertEquals(3, qty(), "被拒后实物不得变动");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "被拒后订单仍停在配送中(2)");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_record WHERE ref_id=? AND type='CONSUME'", order),
                "被拒后不得留下任何出库流水");

        // 站长入库 10 → 新货按 FIFO 补给等货的单
        Api inbound = post("/api/inventory/inbound?stationId=" + station, managerToken(),
                "{\"items\":[{\"productId\":" + product + ",\"quantity\":10}]}");
        assertTrue(inbound.isSuccess(), "入库应成功，实际=" + inbound);
        assertEquals(13, qty(), "入库后实物 3 + 10 = 13");
        assertEquals(10, reserved(), "入库必须把缺的 7 桶补给该单（3 + 7 = 10）");

        // 再完成 → 出库 10，实物剩 3
        Api done = post("/api/delivery/orders/" + order + "/complete",
                staffToken(rider, "DELIVERY", station), "{}");
        assertTrue(done.isSuccess(), "补足后应能完成，实际=" + done);
        assertEquals(3, qty(), "出库 10 后剩 3");
        assertInventoryMatchesRecords("E2 完成后");
    }

    /* ==================== E3 + E5 跨站外派后取消（问题 4a 的场景①） ==================== */

    @Test
    @DisplayName("E3/E5：A 下单 → 外派 B → 取消：凭据跟着搬到 B 再释放，两站实物都不动")
    void crossStationDispatchThenCancel_doesNotDriftStock() {
        seed(10);
        long stationB = createStation("预留站B");
        createInventory(stationB, product, 10);
        createInventoryRecord(stationB, product, 10, "INIT", 0);
        long managerB = createStaff("B站站长", "STATION_MANAGER", stationB, 1);

        Api created = placeOrder(2, "resv-e3");
        assertTrue(created.isSuccess(), created.toString());
        long order = lastOrderId();
        assertEquals(2, reserved(), "A 站先占住 2 桶");

        // A 站定向外派给 B
        Api out = post("/api/delivery/orders/transfer/" + order + "/outsource", managerToken(),
                "{\"targetStationId\":" + stationB + ",\"reason\":\"跨站外派\"}");
        assertEquals(0, out.code(), "外派应成功，实际=" + out);

        assertEquals(0, intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND status=1", station), "A 站的活跃预留必须释放");
        assertEquals(2, intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND status=1", stationB), "B 站必须重建这份预留（货跟着履约站走）");
        assertEquals(1, activeReservations(order), "任一时刻只允许一份活跃凭据（换站 = 旧站释放 + 新站重建）");
        assertEquals(10, qty(), "A 站实物不得变动");
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                stationB, product), "B 站实物也不得变动（换站只是搬承诺，不是搬货）");

        // 客户取消（状态 1 → 当场走取消链）
        Api cancel = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);
        assertEquals(0, cancel.code(), "取消应成功，实际=" + cancel);

        // ⚠️ 这正是旧实现出错的地方：旧代码会把 2 桶"补"到 B，得到 A=8、B=12
        assertEquals(10, qty(), "A 站实物始终是 10（旧实现这里会永久少 2）");
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                stationB, product), "B 站实物始终是 10（旧实现这里会凭空多 2）");
        assertEquals(0, activeReservations(order), "取消必须释放凭据");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_record WHERE ref_id=? AND type='REFUND_RESTORE'",
                order), "取消**不得**再回补库存（实物从没减过）");
        assertInventoryMatchesRecords("E3 取消后（A 站）");
    }

    /* ==================== E4 A→B→A 召回 ==================== */

    @Test
    @DisplayName("E4：A→B 外派后 A 召回：凭据搬回 A 站")
    void recallMovesReservationBackToOwnerStation() {
        seed(10);
        long stationB = createStation("召回站B");
        createInventory(stationB, product, 10);
        createInventoryRecord(stationB, product, 10, "INIT", 0);
        long managerB = createStaff("B站站长", "STATION_MANAGER", stationB, 1);

        placeOrder(2, "resv-e4");
        long order = lastOrderId();
        assertEquals(0, post("/api/delivery/orders/transfer/" + order + "/outsource", managerToken(),
                "{\"targetStationId\":" + stationB + "}").code());
        assertEquals(2, intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND status=1", stationB));

        // 还没被接单 ⇒ 归属站可以召回
        Api recall = post("/api/delivery/orders/" + order + "/cancel-dispatch", managerToken(), "{}");
        assertEquals(0, recall.code(), "召回应成功，实际=" + recall);

        assertEquals(2, reserved(), "预留必须搬回 A 站");
        assertEquals(0, intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND status=1", stationB), "B 站不得再持有这份预留");
        assertEquals(1, activeReservations(order), "始终只有一份活跃凭据");
        assertEquals(10, qty(), "A 站实物不变");
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                stationB, product), "B 站实物不变");
        assertInventoryMatchesRecords("E4 召回后");
    }

    /* ==================== E6 并发抢单 ==================== */

    @Test
    @DisplayName("E6：两站并发抢同一张池中单 —— 只有一个抢到，凭据只落在一个站")
    void concurrentClaim_leavesReservationOnWinnerOnly() throws Exception {
        seed(10);
        long stationB = createStation("抢单站B");
        long stationC = createStation("抢单站C");
        createInventory(stationB, product, 10);
        createInventory(stationC, product, 10);
        createInventoryRecord(stationB, product, 10, "INIT", 0);
        createInventoryRecord(stationC, product, 10, "INIT", 0);
        long managerB = createStaff("B站站长", "STATION_MANAGER", stationB, 1);
        long managerC = createStaff("C站站长", "STATION_MANAGER", stationC, 1);
        long riderB = createStaff("B站配送员", "DELIVERY", stationB, 1);
        long riderC = createStaff("C站配送员", "DELIVERY", stationC, 1);

        placeOrder(2, "resv-e6");
        long order = lastOrderId();
        // 放入抢单池（非桶装、不涉押金 ⇒ 允许入池）；入池即回归属站
        Api pool = post("/api/delivery/orders/transfer/" + order + "/outsource", managerToken(),
                "{\"reason\":\"放池\"}");
        assertEquals(0, pool.code(), "放池应成功，实际=" + pool);
        assertEquals(2, reserved(), "在池中 ⇒ 预留留在归属站 A");

        java.util.List<Api> results = fireTogether(java.util.List.of(
                () -> post("/api/delivery/orders/" + order + "/claim-pool",
                        staffToken(managerB, "STATION_MANAGER", stationB),
                        "{\"deliveryStaffId\":" + riderB + "}"),
                () -> post("/api/delivery/orders/" + order + "/claim-pool",
                        staffToken(managerC, "STATION_MANAGER", stationC),
                        "{\"deliveryStaffId\":" + riderC + "}")));

        long ok = results.stream().filter(Api::isSuccess).count();
        assertEquals(1, ok, "并发抢单只允许一个成功，实际=" + results);
        assertEquals(1, activeReservations(order), "凭据必须恰好一份（唯一键兜底）");
        long holder = longOf("SELECT station_id FROM inventory_reservation WHERE order_id=? AND status=1", order);
        assertTrue(holder == stationB || holder == stationC, "凭据必须落在抢到的那一站，实际=" + holder);
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_reservation WHERE order_id=? AND status=1 "
                + "AND station_id=?", order, station), "归属站不得再持有这份预留");
        long orderStation = longOf("SELECT delivery_station_id FROM orders WHERE id=?", order);
        assertEquals(orderStation, holder, "凭据所在站必须与订单的履约站一致");
        assertInventoryMatchesRecords("E6 抢单后");
    }

    /* ==================== E7 重复提交 ==================== */

    @Test
    @DisplayName("E7：同幂等键重复提交只建一次预留（唯一键兜底）")
    void duplicateSubmit_createsSingleReservation() {
        seed(10);

        assertTrue(placeOrder(2, "resv-e7").isSuccess(), "首次下单");
        assertTrue(placeOrder(2, "resv-e7").isSuccess(), "重复提交应返回原单而不是报错");

        assertEquals(1, intOf("SELECT COUNT(*) FROM orders"), "只应存在一张订单");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_reservation WHERE status=1"), "只应有一条活跃凭据");
        assertEquals(2, reserved(), "预留量不得翻倍");
        assertEquals(10, qty(), "实物不动");
    }

    /* ==================== 工具 ==================== */

    /** 对账等式 V1-4：每个(站,商品)的 quantity 必须等于其库存流水合计。 */
    private void assertInventoryMatchesRecords(String when) {
        int mismatched = intOf("SELECT COUNT(*) FROM inventory i "
                + "LEFT JOIN (SELECT station_id, product_id, SUM(delta) AS s FROM inventory_record "
                + "GROUP BY station_id, product_id) r "
                + "ON r.station_id = i.station_id AND r.product_id = i.product_id "
                + "WHERE i.quantity <> COALESCE(r.s, 0)");
        assertEquals(0, mismatched, when + "：库存数量必须等于库存流水合计（对账等式 V1-4）");
    }

    /** 真并发小助手（只在 E6 需要；跑法与 ConcurrencyIntegrationTest 同形）。 */
    private java.util.List<Api> fireTogether(java.util.List<java.util.concurrent.Callable<Api>> tasks)
            throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(tasks.size());
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(tasks.size());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Api>> futures = new java.util.ArrayList<>();
        try {
            for (java.util.concurrent.Callable<Api> t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return t.call();
                }));
            }
            ready.await(10, java.util.concurrent.TimeUnit.SECONDS);
            start.countDown();
            java.util.List<Api> out = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Api> f : futures) {
                out.add(f.get(20, java.util.concurrent.TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
