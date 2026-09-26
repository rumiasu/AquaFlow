package com.example.aquaflow.integration;

import com.example.aquaflow.service.ReconciliationService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存预留纳入对账体系（返工契约 §5：「把预留完整性纳入现有对账体系」）。
 *
 * <p>为什么库存那条守恒式（V1-4：{@code quantity == Σ inventory_record.delta}）不够：
 * 预留**不动实物、不写流水**，所以"实物账平"完全可能与"已经卖出去的货"矛盾。四条新检查覆盖的
 * 正是它看不见的那一面（全平台 E11–E14 / 站长端按站 SE7–SE10，判据同源）：</p>
 * <ul>
 *   <li>E11 预留超实物（Σ活跃预留 &gt; 在库实物）= 把不存在的货卖了；</li>
 *   <li>E12 在途单(1/2)的明细没有活跃凭据 = 完成配送时会被拦下，但没人知道是哪几单；</li>
 *   <li>E13 凭据挂错站（≠ 当前履约站）= 换站没搬凭据；</li>
 *   <li>E14 预留量越界（负数或超过需求量）= 补位加数算错 / 明细被改过。</li>
 * </ul>
 *
 * <p>本类**直接造出这四种形状**（都靠 SQL 造数，不走业务入口 —— 正常业务已经不可能产出它们了），
 * 断言对账能抓到；同时先断言"健康状态下一律为 0"，免得把合法状态也算成差异
 * （本仓在这件事上踩过三次：核销、无订单购票、已送达的在途桶记录，见 docs/architecture/03 §9）。</p>
 */
@DisplayName("库存预留 · 对账 E11–E14 / SE7–SE10")
class InventoryReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    private long stationA;
    private long stationB;
    private long product;
    private long customer;
    private long address;
    private long managerA;
    private long managerB;
    private long riderB;

    private void seed() {
        stationA = createStation("对账站A");
        stationB = createStation("对账站B");
        product = createProduct("瓶装水550ml", 2, "20.00", "0.00", 0, "0.00");
        createInventory(stationA, product, 10);
        createInventoryRecord(stationA, product, 10, "INIT", 0);
        createInventory(stationB, product, 10);
        createInventoryRecord(stationB, product, 10, "INIT", 0);
        customer = createCustomer("对账客户", "recon-openid");
        address = createAddress(customer, "对账小区1号");
        createCustomerStationConfig(customer, stationA, 1);
        managerA = createStaff("对账站长A", "STATION_MANAGER", stationA, 1);
        managerB = createStaff("对账站长B", "STATION_MANAGER", stationB, 1);
        riderB = createStaff("对账配送员B", "DELIVERY", stationB, 1);
    }

    private int global(String key) {
        Integer v = reconciliationService.runReconcileV2().get(key);
        return v == null ? 0 : v;
    }

    @SuppressWarnings("unchecked")
    private int stationScoped(long stationId, String key) {
        Map<String, Object> out = reconciliationService.stationCheck(stationId);
        Map<String, Integer> checks = (Map<String, Integer>) out.get("checks");
        Integer v = checks.get(key);
        return v == null ? 0 : v;
    }

    /** 一张真实的、健康的在途单：HTTP 下单（自动带凭据），作为"不该报差异"的基线。 */
    private void placeHealthyOrder(int qty, String key) {
        Api created = post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + stationA + ",\"addressId\":" + address + ",\"paymentMethod\":2,"
                        + "\"idempotencyKey\":\"" + key + "\""
                        + ",\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}");
        assertTrue(created.isSuccess(), "健康基线单应能下单，实际=" + created);
    }

    @Test
    @DisplayName("健康状态：E11–E14 与 SE7–SE10 一律为 0（合法状态不得算成差异）")
    void healthyStateHasNoReservationDiffs() {
        seed();
        placeHealthyOrder(3, "recon-healthy");

        assertEquals(0, global("E11_reservedExceedsStock"));
        assertEquals(0, global("E12_inflightItemsWithoutCredential"));
        assertEquals(0, global("E13_credentialWrongStation"));
        assertEquals(0, global("E14_reservedOutOfRange"));
        assertEquals(0, global("E15_needSnapshotMismatch"));
        assertEquals(0, global("E16_credentialWithoutInventoryRow"));
        for (String k : new String[]{"SE7_reservedExceedsStock", "SE8_inflightItemsWithoutCredential",
                "SE9_credentialWrongStation", "SE10_reservedOutOfRange",
                "SE11_needSnapshotMismatch", "SE12_credentialWithoutInventoryRow"}) {
            assertEquals(0, stationScoped(stationA, k), k + " 在健康状态下必须为 0");
            assertEquals(0, stationScoped(stationB, k), k + "（B 站）在健康状态下必须为 0");
        }
    }

    @Test
    @DisplayName("E11/SE7：活跃预留超过在库实物必须被抓到")
    void reservedExceedingStockIsDetected() {
        seed();
        long order = createOrderFull(customer, address, stationA, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        long item = createOrderItemFull(order, product, "瓶装水550ml", 5, 5, "20.00", "0.00");
        createReservation(order, item, product, stationA, 5, 1);
        jdbc.update("UPDATE inventory SET quantity=1 WHERE station_id=? AND product_id=?", stationA, product);

        assertTrue(global("E11_reservedExceedsStock") >= 1, "E11 必须抓到「预留 5 / 实物 1」");
        assertTrue(stationScoped(stationA, "SE7_reservedExceedsStock") >= 1, "SE7 是 E11 的按站版");
        assertEquals(0, global("E14_reservedOutOfRange"), "这条不该顺带报 E14（预留量本身没越界）");
    }

    @Test
    @DisplayName("E12/SE8：在途单的明细没有活跃凭据必须被抓到")
    void inflightItemWithoutCredentialIsDetected() {
        seed();
        long order = createOrderFull(customer, address, stationA, product,
                2 /* 配送中 */, 1, 2, "40.00", "0.00", "40.00", false, 0);
        createOrderItemFull(order, product, "瓶装水550ml", 2, 0, "20.00", "0.00");
        // 刻意不建凭据（迁移漏行 / 手工改库的形状）

        assertTrue(global("E12_inflightItemsWithoutCredential") >= 1, "E12 必须抓到在途缺凭据");
        assertTrue(stationScoped(stationA, "SE8_inflightItemsWithoutCredential") >= 1, "SE8 是 E12 的按站版");
        assertEquals(0, global("E11_reservedExceedsStock"), "没有凭据 ⇒ 不该报预留超实物");

        // 已送达(3)/已完成(4)/已取消(5) 的历史单不算：它们本来就不该有活跃凭据
        jdbc.update("UPDATE orders SET status=4 WHERE id=?", order);
        assertEquals(0, global("E12_inflightItemsWithoutCredential"), "已完成的历史单不得算成差异");
    }

    @Test
    @DisplayName("E13/SE9：凭据挂在非履约站必须被抓到（站别判据 = coalesce(履约站, 归属站)）")
    void credentialAtWrongStationIsDetected() {
        seed();
        // 归属站 A、履约站 B 的单，凭据却留在 A —— 换站没搬凭据的形状
        long order = createOrderCrossStation(customer, address, stationA, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00");
        long item = createOrderItemFull(order, product, "瓶装水550ml", 2, 2, "20.00", "0.00");
        createReservation(order, item, product, stationA, 2, 1);

        assertTrue(global("E13_credentialWrongStation") >= 1, "E13 必须抓到凭据挂错站");
        assertTrue(stationScoped(stationA, "SE9_credentialWrongStation") >= 1,
                "SE9：货挂在本站而单已换到别站（要让位）—— 本站也该看见");
        assertTrue(stationScoped(stationB, "SE9_credentialWrongStation") >= 1,
                "SE9：本站该履约而货挂在别站（要拉回凭据）");
        assertEquals(0, global("E11_reservedExceedsStock"), "B 站实物 10 ≥ 预留 0、A 站 10 ≥ 2，不该报 E11");
    }

    @Test
    @DisplayName("E14/SE10：预留量越界（负数或超过需求量）必须被抓到")
    void reservedOutOfRangeIsDetected() {
        seed();
        long order = createOrderFull(customer, address, stationA, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        long item = createOrderItemFull(order, product, "瓶装水550ml", 1, 1, "20.00", "0.00");
        createReservation(order, item, product, stationA, 3, 1);   // 需求量 1、预留 3

        assertTrue(global("E14_reservedOutOfRange") >= 1, "E14 必须抓到预留超过需求量");
        assertTrue(stationScoped(stationA, "SE10_reservedOutOfRange") >= 1, "SE10 是 E14 的按站版");
        assertEquals(0, global("E13_credentialWrongStation"), "站别是对的，不该顺带报 E13");
    }

    @Test
    @DisplayName("E15/SE11：需求量快照与真相源不一致必须被抓到（补位只读快照，分叉就会按错量分配）")
    void needSnapshotMismatchIsDetected() {
        seed();
        long order = createOrderFull(customer, address, stationA, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        long item = createOrderItemFull(order, product, "瓶装水550ml", 5, 5, "20.00", "0.00");
        long cred = createReservation(order, item, product, stationA, 5, 1);
        // 手工把快照改成与明细不一致（模拟"绕过服务写库 / 迁移算错"）
        jdbc.update("UPDATE inventory_reservation SET need_qty=3 WHERE id=?", cred);

        assertTrue(global("E15_needSnapshotMismatch") >= 1, "E15 必须抓到快照 ≠ 明细量");
        assertTrue(stationScoped(stationA, "SE11_needSnapshotMismatch") >= 1, "SE11 是 E15 的按站版");
    }

    @Test
    @DisplayName("E16/SE12：活跃凭据挂在没有库存行的 (站,商品) 上必须被抓到（E11 内连接看不见它）")
    void credentialWithoutInventoryRowIsDetected() {
        seed();
        // 造一张挂在**没有库存行**的站上的凭据：把 A 站的库存行删掉
        long order = createOrderFull(customer, address, stationA, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        long item = createOrderItemFull(order, product, "瓶装水550ml", 2, 2, "20.00", "0.00");
        createReservation(order, item, product, stationA, 2, 1);
        jdbc.update("DELETE FROM inventory WHERE station_id=? AND product_id=?", stationA, product);

        assertTrue(global("E16_credentialWithoutInventoryRow") >= 1,
                "E16 必须抓到'有承诺、没有实物行'");
        assertTrue(stationScoped(stationA, "SE12_credentialWithoutInventoryRow") >= 1, "SE12 是 E16 的按站版");
        assertEquals(0, global("E11_reservedExceedsStock"),
                "★ 这正是 E11 抓不到的形状：它是 join inventory，缺库存行的凭据整行被过滤掉");
    }

    @Test
    @DisplayName("E16 反例：外派到「没配这个商品」的站是合法状态（凭据 reserved=0）—— 不得算成差异")
    void credentialAtStationWithoutInventoryRowButZeroReservedIsLegal() {
        seed();
        // 把这张单外派到 stationB，而 stationB **没有**这个商品的库存行（可用量 0）
        long order = createOrderCrossStation(customer, address, stationA, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00");
        long item = createOrderItemFull(order, product, "瓶装水550ml", 5, 0, "20.00", "0.00");
        createReservation(order, item, product, stationB, 0, 1);   // 缺货待补：凭据在 B、预留 0
        jdbc.update("DELETE FROM inventory WHERE station_id=? AND product_id=?", stationB, product);

        assertEquals(0, global("E16_credentialWithoutInventoryRow"),
                "★ 只按「有没有库存行」判会把这种合法状态（外派到没配该商品的站）算成差异 —— "
                        + "判据必须带 reserved_qty > 0（本仓在这件事上踩过三次）");
        assertEquals(0, stationScoped(stationB, "SE12_credentialWithoutInventoryRow"), "按站版同理");
        assertEquals(0, global("E11_reservedExceedsStock"), "预留 0 ⇒ 更没有超实物");

        // 反面对照：一旦它真的承诺了数量（reserved > 0），就必须被抓到
        jdbc.update("UPDATE inventory_reservation SET reserved_qty=3 WHERE order_id=? AND status=1", order);
        assertTrue(global("E16_credentialWithoutInventoryRow") >= 1,
                "凭空承诺（没有库存行却 reserved>0）必须被抓到");
    }

    @Test
    @DisplayName("告警分级：预留不平走 SYSTEM（系统管理员），且不带站别（不给站长看）")
    void reservationDiffsAreRoutedToSystemNotStation() {
        seed();
        // 造一条"在途但缺凭据"的差异（E12）
        long order = createOrderFull(customer, address, stationA, product,
                2, 1, 2, "40.00", "0.00", "40.00", false, 0);
        createOrderItemFull(order, product, "瓶装水550ml", 2, 0, "20.00", "0.00");

        assertEquals(0, intOf("SELECT COUNT(*) FROM alert_log WHERE alert_type='SYSTEM'"), "前置：还没有系统告警");
        reconciliationService.dailyReconcile();

        // §11.7 的分级：E11–E14 属**客户/库存账**，与 V1-4 同级 ⇒ SYSTEM；station_id 必须为 NULL
        assertTrue(intOf("SELECT COUNT(*) FROM alert_log WHERE alert_type='SYSTEM'") >= 1,
                "对账不平必须落 SYSTEM 告警");
        assertEquals("NULL", jdbc.queryForObject(
                        "SELECT IFNULL(station_id,'NULL') FROM alert_log WHERE alert_type='SYSTEM' ORDER BY id DESC LIMIT 1",
                        String.class),
                "系统告警的 station_id 必须为 NULL —— 站长端只读 /api/manager/alerts，带站别等于把库存账泄露给他站");
        assertTrue(jdbc.queryForObject("SELECT content FROM alert_log WHERE alert_type='SYSTEM' ORDER BY id DESC LIMIT 1",
                        String.class).contains("E12"),
                "告警正文要带上差异键名（E12 = 在途明细缺凭据），值班的人才知道去查什么");
    }
}
