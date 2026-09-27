package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存预留的**补位 / 护栏 / 出库完整性**验收（返工契约 §5 的 V03 / V04 / V06 / V10）。
 *
 * <p>四条断言各自对应返工项：</p>
 * <ul>
 *   <li><b>V03 释放补位</b>（R4）：取消/拒单释放掉的货必须**按业务需求时间**补给它自己的等待单
 *       —— 首版只在"入库"时补位，取消释放那条路径天然漏了，站长只能靠"假入库"绕开。</li>
 *   <li><b>V04 换站后的两站都补位</b>（R2）：目标站按**实际可用量**重建凭据（不是照抄原预留量），
 *       旧站释放出来的货补给它自己的等待单；缺货量必须真实（能算出来，不是"差不多"）。</li>
 *   <li><b>V06 盘点与镜像</b>（R5）：把实物盘到低于已预留量必须**明确拒绝并列出占用中的订单**，
 *       不许静默成功；补货之后凭据 / 明细镜像 / 缺货提示三者一致。</li>
 *   <li><b>V10 出库完整性</b>（§3 第 4 条）：凭据缺失 / 错站 / 漏明细都是**明确失败**，
 *       且订单、库存、桶、工资不发生部分提交。</li>
 * </ul>
 *
 * <p>商品一律用**非桶装**（不涉押金/桶权益），把变量集中在库存这一件事上。</p>
 */
@DisplayName("库存预留 · 补位/护栏/完整性（V03/V04/V06/V10）")
class InventoryBackfillIntegrationTest extends AbstractIntegrationTest {

    private long stationA;
    private long stationB;
    private long product;
    private long customer;
    private long address;
    private long managerA;
    private long managerB;
    private long riderA;
    private long riderB;

    private void seed(int stockA, int stockB) {
        stationA = createStation("补位站A");
        stationB = createStation("补位站B");
        product = createProduct("瓶装水550ml", 2, "20.00", "0.00", 0, "0.00");
        createInventory(stationA, product, stockA);
        createInventoryRecord(stationA, product, stockA, "INIT", 0);
        createInventory(stationB, product, stockB);
        createInventoryRecord(stationB, product, stockB, "INIT", 0);
        customer = createCustomer("补位客户", "backfill-openid");
        address = createAddress(customer, "补位小区1号");
        createCustomerStationConfig(customer, stationA, 1);
        managerA = createStaff("A站站长", "STATION_MANAGER", stationA, 1);
        managerB = createStaff("B站站长", "STATION_MANAGER", stationB, 1);
        riderA = createStaff("A站配送员", "DELIVERY", stationA, 1);
        riderB = createStaff("B站配送员", "DELIVERY", stationB, 1);
    }

    private String tokenA() {
        return staffToken(managerA, "STATION_MANAGER", stationA);
    }

    /** B 站站长 token（[2026-09-26] 起"换站后由新站派单"要用它）。 */
    private String tokenB() {
        return staffToken(managerB, "STATION_MANAGER", stationB);
    }

    private Api placeOrder(int qty, String key) {
        return placeOrder(qty, key, false);
    }

    private Api placeOrder(int qty, String key, boolean confirmShortage) {
        return post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + stationA + ",\"addressId\":" + address + ",\"paymentMethod\":2,"
                        + "\"idempotencyKey\":\"" + key + "\""
                        + (confirmShortage ? ",\"confirmShortage\":true" : "")
                        + ",\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}");
    }

    private long lastOrderId() {
        return longOf("SELECT id FROM orders ORDER BY id DESC LIMIT 1");
    }

    private int reservedAt(long stationId, long orderId) {
        return intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE order_id=? AND station_id=? AND status=1", orderId, stationId);
    }

    private int physical(long stationId) {
        return intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", stationId, product);
    }

    /** 该单"缺货待补"总量（与 InventoryReservationServiceImpl.shortageOfOrder 同口径）。 */
    private int shortage(long orderId) {
        return intOf("SELECT COALESCE(SUM(oi.quantity - r.reserved_qty),0) FROM inventory_reservation r "
                + "JOIN order_item oi ON oi.id = r.order_item_id "
                + "WHERE r.order_id=? AND r.status=1", orderId);
    }

    private int recordDelta() {
        return intOf("SELECT COALESCE(SUM(delta),0) FROM inventory_record WHERE station_id=? AND product_id=?",
                stationA, product);
    }

    private int inventoryRecordCount() {
        return intOf("SELECT COUNT(*) FROM inventory_record");
    }

    private void assertInventoryMatchesRecords(String when) {
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory i "
                        + "LEFT JOIN (SELECT station_id, product_id, SUM(delta) s FROM inventory_record "
                        + "           GROUP BY station_id, product_id) r "
                        + "  ON r.station_id = i.station_id AND r.product_id = i.product_id "
                        + "WHERE i.quantity <> COALESCE(r.s, 0)"),
                when + "：库存数量必须等于库存流水合计（对账等式 V1-4）");
    }

    /* ==================== V03 释放补位 ==================== */

    @Test
    @DisplayName("V03：甲占满 10、乙等 5；甲取消后乙拿到 5，实物与流水都没变，乙能完成")
    void releaseBackfillsWaitingOrderWithoutTouchingPhysical() {
        seed(10, 0);

        assertTrue(placeOrder(10, "v03-jia").isSuccess(), "甲占满 10");
        long jia = lastOrderId();
        assertTrue(placeOrder(5, "v03-yi", true).isSuccess(), "乙确认缺货后下单");
        long yi = lastOrderId();
        assertEquals(0, reservedAt(stationA, yi), "货被甲占着 ⇒ 乙只能先留下一条 0 预留的凭据（需求被记下来）");

        int recordsBefore = inventoryRecordCount();
        int physicalBefore = physical(stationA);

        // 甲取消（走真实取消链：释放预留，**不回补实物**）
        assertEquals(0, put("/api/orders/" + jia + "/customer-cancel", customerToken(customer), null).code(),
                "甲取消");

        assertEquals(5, reservedAt(stationA, yi), "释放出来的货必须按 FIFO 补给它自己的等待单（首版漏了这半句）");
        assertEquals(0, reservedAt(stationA, jia), "甲已经释放");
        assertEquals(physicalBefore, physical(stationA), "取消只释放，实物一点不动");
        assertEquals(recordsBefore, inventoryRecordCount(), "释放/补位**不得**新增任何实物流水（假入库）");
        assertInventoryMatchesRecords("V03 取消后");

        // 乙能完成：补足后出库 5
        // [2026-09-26] 配送员只能接**派给自己**的单（产品裁定），链路补一步「站长派单」
        assertEquals(0, post("/api/delivery/orders/assign/" + yi, tokenA(),
                "{\"deliveryStaffId\":" + riderA + "}").code(), "站长派单");
        assertEquals(0, post("/api/delivery/orders/" + yi + "/accept",
                staffToken(riderA, "DELIVERY", stationA), "{}").code(), "乙接单");
        Api done = post("/api/delivery/orders/" + yi + "/complete",
                staffToken(riderA, "DELIVERY", stationA), "{}");
        assertTrue(done.isSuccess(), "补位后乙应能完成配送，实际=" + done);
        assertEquals(5, physical(stationA), "出库 5：10 − 5 = 5");
        assertInventoryMatchesRecords("V03 完成后");
    }

    /* ==================== V04 换站：两站各自的补位与真实缺货量 ==================== */

    @Test
    @DisplayName("V04：外派到 B —— B 按实际可用量重建、A 释放的货补给它自己的等待单、缺货量真实")
    void transferRebuildsAtTargetAndBackfillsOwnerStation() {
        seed(10, 3);

        assertTrue(placeOrder(10, "v04-x").isSuccess(), "X 占满 A 的 10");
        long x = lastOrderId();
        assertTrue(placeOrder(6, "v04-y", true).isSuccess(), "Y 在 A 站等货");
        long y = lastOrderId();
        assertEquals(0, reservedAt(stationA, y), "A 已被 X 占满");

        Api out = post("/api/delivery/orders/transfer/" + x + "/outsource", tokenA(),
                "{\"targetStationId\":" + stationB + ",\"reason\":\"V04\"}");
        assertEquals(0, out.code(), "外派应成功，实际=" + out);

        assertEquals(3, reservedAt(stationB, x), "B 站实物只有 3 ⇒ 只能重建 3（照抄原预留量 10 就是超卖）");
        assertEquals(7, shortage(x), "缺货量必须真实：10 − 3 = 7");
        assertEquals(6, reservedAt(stationA, y), "A 释放出来的 10 桶必须按 FIFO 补给等货的 Y");
        assertEquals(0, reservedAt(stationA, x), "X 在 A 的凭据必须释放");
        assertEquals(10, physical(stationA), "换站只搬承诺，不动实物");
        assertEquals(3, physical(stationB), "换站只搬承诺，不动实物");
        assertInventoryMatchesRecords("V04 换站后");

        // 缺货提示真实：此时的 X 归 B 站履约，B 站只有 3 桶货 —— 接单可以（缺货可预订），
        // 但完成配送必须被拒，且要说清是预留不足（不许静默少扣着把单结了）
        // [2026-09-26] 先由 B 站站长派单，配送员才能接（配送员只能接派给自己的单）
        assertEquals(0, post("/api/delivery/orders/assign/" + x, tokenB(),
                "{\"deliveryStaffId\":" + riderB + "}").code(), "B 站站长派单");
        assertEquals(0, post("/api/delivery/orders/" + x + "/accept",
                staffToken(riderB, "DELIVERY", stationB), "{}").code(), "B 站配送员接单");
        Api blocked = post("/api/delivery/orders/" + x + "/complete",
                staffToken(riderB, "DELIVERY", stationB), "{}");
        assertFalse(blocked.isSuccess(), "B 站的货不够，不许静默少扣着把单结了，实际=" + blocked);
        assertTrue(blocked.message().contains("还缺"), "拒绝原因要说清还缺几桶（C3：可读文案），实际=" + blocked.message());
        assertTrue(blocked.message().contains(String.valueOf(x)), "拒绝文案要带订单号，实际=" + blocked.message());
        assertEquals(3, physical(stationB), "被拒后实物不得变动");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", x), "被拒后订单仍停在配送中(2)");
    }

    /* ==================== V06 盘点护栏 + 镜像一致 ==================== */

    @Test
    @DisplayName("V06：盘点不得低于已预留量（列出占用订单）；边界内允许下调；补货后凭据/镜像/缺货提示一致")
    void stockAdjustmentCannotGoBelowReservedAndMirrorsStayInSync() {
        seed(10, 0);

        assertTrue(placeOrder(8, "v06-jia").isSuccess(), "甲预留 8");
        long jia = lastOrderId();
        assertEquals(8, reservedAt(stationA, jia));
        assertEquals(10, physical(stationA));

        // 实物 10 / 已预留 8，盘点到 3：必须明确拒绝，并告诉站长是哪些单占着
        Api bad = post("/api/manager/catalog/" + product + "/stock", tokenA(),
                "{\"target\":3,\"note\":\"盘亏\"}");
        assertFalse(bad.isSuccess(), "盘到 3 会让实物低于已预留的 8，绝不能静默成功，实际=" + bad);
        assertEquals(1, bad.code(), "这必须是可读业务拒绝（code=1），不是 500，实际=" + bad);
        assertTrue(bad.message().contains("#" + jia), "拒绝文案要列出占用中的订单号，实际=" + bad.message());
        assertEquals(10, physical(stationA), "被拒后实物不得变动");
        assertEquals(8, reservedAt(stationA, jia), "被拒后预留不得变动");

        // 边界之内允许下调：盘到 9 ≥ 已预留的 8，差额 −1 落 ADJUST 流水
        Api ok = post("/api/manager/catalog/" + product + "/stock", tokenA(),
                "{\"target\":9,\"note\":\"实盘 9\"}");
        assertEquals(0, ok.code(), "盘到 9（≥ 已预留 8）应当允许，实际=" + ok);
        assertEquals(9, physical(stationA));
        assertEquals(-1, intOf("SELECT COALESCE(SUM(delta),0) FROM inventory_record "
                + "WHERE station_id=? AND product_id=? AND type='ADJUST'", stationA, product), "下调要落 ADJUST 流水");
        assertInventoryMatchesRecords("V06 盘到 9 后");

        // 乙此时只能拿到 1（9 − 8），Σ预留 = 9 → 再盘到 8 就必须被拒
        assertTrue(placeOrder(5, "v06-yi", true).isSuccess(), "乙确认缺货后下单");
        long yi = lastOrderId();
        assertEquals(1, reservedAt(stationA, yi), "可用量只剩 1 ⇒ 乙先预留 1");
        Api bad2 = post("/api/manager/catalog/" + product + "/stock", tokenA(), "{\"target\":8,\"note\":\"盘亏\"}");
        assertFalse(bad2.isSuccess(), "盘到 8 低于 Σ预留 9，必须拒绝，实际=" + bad2);
        assertEquals(1, bad2.code(), "实际=" + bad2);
        assertEquals(9, physical(stationA), "被拒后实物不得变动");

        // 补货到 21 → 补位把乙缺的 4 桶补上；三个派生量必须同时对上
        assertEquals(0, post("/api/inventory/inbound?stationId=" + stationA, tokenA(),
                "{\"items\":[{\"productId\":" + product + ",\"quantity\":12}]}").code(), "入库 12");
        assertEquals(21, physical(stationA), "9 + 12 = 21");
        assertEquals(5, reservedAt(stationA, yi), "乙的缺货必须被补上（1 + 4）");
        assertEquals(0, shortage(yi), "补货后乙不再缺货");
        assertEquals(8, intOf("SELECT deducted_qty FROM order_item WHERE order_id=?", jia),
                "甲：镜像 = 活跃凭据预留量");
        assertEquals(5, intOf("SELECT deducted_qty FROM order_item WHERE order_id=?", yi),
                "乙：镜像也必须是 5（首版补位时漏同步这一列）");
        // 派生展示一致：Σ预留 ≤ 实物（E11）、预留量在 [0, 需求量] 内（E14）
        assertEquals(0, intOf("SELECT COUNT(*) FROM ("
                        + "SELECT r.station_id, r.product_id FROM inventory_reservation r "
                        + "JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id "
                        + "WHERE r.status = 1 GROUP BY r.station_id, r.product_id "
                        + "HAVING SUM(r.reserved_qty) > MAX(i.quantity)) x"),
                "E11：不得出现 Σ预留 > 实物");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_reservation r "
                        + "JOIN order_item oi ON oi.id = r.order_item_id "
                        + "WHERE r.status = 1 AND (r.reserved_qty < 0 OR r.reserved_qty > oi.quantity)"),
                "E14：预留量必须落在 [0, 需求量] 内");
        assertInventoryMatchesRecords("V06 补货后");
    }

    /* ==================== V10 出库完整性 ==================== */

    @Test
    @DisplayName("V10a：在途单没有凭据 —— 完成配送必须明确失败，且订单/库存/工资零变化")
    void completionWithoutCredentialFailsWithoutPartialCommit() {
        seed(10, 0);
        long order = createOrderFull(customer, address, stationA, product,
                2 /* 配送中 */, 1, 2 /* 现金 */, "40.00", "0.00", "40.00", false, 0);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", riderA, order);
        createOrderItemFull(order, product, "瓶装水550ml", 2, 2, "20.00", "0.00");
        // 刻意**不建**凭据（模拟迁移漏行 / 手工改库）

        Api done = post("/api/delivery/orders/" + order + "/complete",
                staffToken(riderA, "DELIVERY", stationA), "{}");
        assertFalse(done.isSuccess(), "没有凭据就必须拒绝出库，实际=" + done);
        assertEquals(1, done.code(), "应当是可读业务拒绝，实际=" + done);
        assertTrue(done.message().contains("备货记录不完整"), "文案要指向备货记录不完整（C3：可读 + 带订单号），实际=" + done.message());
        assertTrue(done.message().contains(String.valueOf(order)), "拒绝文案要带订单号，实际=" + done.message());
        assertFalse(done.message().contains("凭据"), "C3：面向一线的文案不得出现「凭据」这类内部术语，实际=" + done.message());

        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "订单状态不得前进");
        assertEquals(10, physical(stationA), "实物不得变动");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_record WHERE ref_id=? AND type='CONSUME'", order),
                "不得留下出库流水");
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=?", order),
                "不得产生计件工资（收益只能在状态 CAS 成功之后产生）");
    }

    @Test
    @DisplayName("V10b：凭据挂在非履约站 —— 完成配送必须明确失败，不得去扣另一站的货")
    void completionWithCredentialAtWrongStationFails() {
        seed(10, 10);
        long order = createOrderCrossStation(customer, address, stationA, stationB, product,
                2 /* 配送中 */, 1, 2, "40.00", "0.00", "40.00");
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", riderB, order);
        long item = createOrderItemFull(order, product, "瓶装水550ml", 2, 2, "20.00", "0.00");
        // 凭据错站（手工改库的形状）：挂在归属站 A，而本单当前履约站是 B
        createReservation(order, item, product, stationA, 2, 1);

        Api done = post("/api/delivery/orders/" + order + "/complete",
                staffToken(riderB, "DELIVERY", stationB), "{}");
        assertFalse(done.isSuccess(), "凭据站别不符必须拒绝，实际=" + done);
        assertEquals(1, done.code(), "应当是可读业务拒绝，实际=" + done);
        assertTrue(done.message().contains("原来的水站"), "文案要指向凭据站别（C3：不暴露站 ID），实际=" + done.message());
        assertTrue(done.message().contains(String.valueOf(order)), "拒绝文案要带订单号，实际=" + done.message());
        assertFalse(done.message().contains("v64"), "C3：不得把迁移脚本版本号说给一线，实际=" + done.message());
        assertEquals(10, physical(stationA), "不得去扣 A 站的货");
        assertEquals(10, physical(stationB), "不得去扣 B 站的货");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "订单状态不得前进");
    }

    @Test
    @DisplayName("V10c：两条明细只建了一条凭据 —— 覆盖检查必须拒绝（漏明细不算完整）")
    void completionWithMissingCredentialOnOneItemFails() {
        seed(10, 0);
        long order = createOrderFull(customer, address, stationA, product,
                2, 1, 2, "40.00", "0.00", "40.00", false, 0);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", riderA, order);
        long item1 = createOrderItemFull(order, product, "瓶装水550ml", 1, 1, "20.00", "0.00");
        createOrderItemFull(order, product, "瓶装水550ml", 1, 1, "20.00", "0.00");
        createReservation(order, item1, product, stationA, 1, 1);   // 只给第一条建

        Api done = post("/api/delivery/orders/" + order + "/complete",
                staffToken(riderA, "DELIVERY", stationA), "{}");
        assertFalse(done.isSuccess(), "明细 2 条 / 凭据 1 条必须拒绝，实际=" + done);
        assertTrue(done.message().contains("备货记录不完整"), "实际=" + done.message());
        assertEquals(10, physical(stationA), "实物不得变动");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_reservation WHERE order_id=? AND status=1", order),
                "被拒后原有凭据不得被改动");
    }
}
