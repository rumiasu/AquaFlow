package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归锁：**非桶装水商品（{@code category != 1}）完成配送时不得污染桶账**。
 *
 * <p><b>为什么单独建一个类</b>（2026-09-19）：给平台通用库补「一次性桶」（4.5L–15L，
 * 免押金、不回收）时，采用「归入 {@code category=2}」的方案来绕开桶逻辑。
 * 下单侧确实是干净的 —— {@code OrderServiceImpl:367} 只数 {@code category=1}，
 * 于是 {@code delivery_bucket_qty = null}、{@code first_barrel_order = 0}、押金 0。
 * 但**配送完成侧未必干净**：{@code BarrelLedgerService.applyDelivery} 的
 * 「本单送出」是按 {@code order_item} 数量统计的，**完全不看 category**：
 *
 * <pre>
 *   deliveredByProduct.merge(it.getProductId(), it.getQuantity(), Integer::sum);
 *   ...
 *   int delta = delivered - returned - rightPurchase;
 * </pre>
 *
 * <p>也就是说，只要订单明细里有 2 件某个商品，「送出 2」就会被记进 over。
 * 若该商品其实是一次性桶（无桶可回收、无桶可持有），就会虚增「客户欠桶」，
 * 并在 {@code owed != 0} 时经 {@code orderBarrelExceptionService.recordReturn}
 * 生成一条**假桶异常单**——正是「归类为瓶装水」想避免的那种污染。</p>
 *
 * <p><b>本类的价值</b>：它把「非桶装水走完整个配送闭环后桶账是否为零」这件事
 * 从推断变成实测。</p>
 *
 * <p><b>实测与修复（2026-09-19）</b>：首轮实测证实假设<b>不成立</b> ——「归类为瓶装水」只挡住了下单侧：
 * 下单侧 3 项断言全绿（{@code delivery_bucket_qty=NULL} / 押金 0 / 不打首单标记），
 * 但配送侧 {@code over} 实测为 <b>2</b>（期望 0）。**当批已按方案 A 修复**
 * （{@code BarrelLedgerService.applyDelivery} 只统计 {@code category=1} 明细，判据唯一实现在
 * {@code util/BarrelScope}），本类即为该修复的验收标准。</p>
 *
 * <p>方案 A 优于方案 B 的地方正是本类下面的混合单用例：B（以 {@code delivery_bucket_qty == null}
 * 为闸跳过桶账）在"一单同时含桶装水与一次性桶"时闸门是开的，仍会污染。</p>
 */
@DisplayName("非桶装水商品的桶账边界 · 完成配送后不得虚增 over / 生成桶异常单")
class DisposableBarrelLedgerBoundaryIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long manager;
    private long customer;
    private long address;

    private void seed() {
        station = createStation("一次性桶站");
        manager = createStaff("站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("客户", "disposable-bucket-openid");
        address = createAddress(customer, "某小区1号");
        // 现金单（paymentMethod=2）不要求先有 PAID 流水即可完成配送，便于隔离桶账变量
        createCustomerStationConfig(customer, station, 1);
    }

    private String mgrToken() {
        return staffToken(manager, "STATION_MANAGER", station);
    }

    private String cusToken() {
        return customerToken(customer);
    }

    /** 用真实 HTTP 下单路径建单（而不是直接 SQL 造单），确保下单侧的桶判据真的生效。 */
    private long placeOrder(long productId, int qty, String idempotencyKey) {
        String body = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"" + idempotencyKey
                + "\",\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}";
        Api res = post("/api/orders/create", cusToken(), body);
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        return res.data().path("orderId").asLong();
    }

    /** 模拟派单：待配送(1) → 配送中(2)。completeDelivery 要求订单处于配送中。 */
    private void dispatch(long orderId) {
        jdbc.update("UPDATE orders SET status = 2 WHERE id = ?", orderId);
    }

    /**
     * 完成配送，回桶明细显式声明「一个空桶都没收回」——这正是配送员的真实操作。
     * <p>{@code collected=true} 让现金单一次性闭环到 已完成(4)；
     * 不传时按 {@code DeliveryCompleteIntegrationTest} 的口径只到 已送达(3)。
     * 桶账（{@code applyDelivery}）在两种情况下都会执行，与状态无关。</p>
     */
    private Api complete(long orderId, int expected, int actual) {
        long itemId = longOf("SELECT id FROM order_item WHERE order_id = ? ORDER BY id LIMIT 1", orderId);
        String body = "{\"collected\":true,\"itemReturns\":[{\"orderItemId\":" + itemId
                + ",\"expected\":" + expected + ",\"actual\":" + actual
                + ",\"reasons\":[{\"key\":\"customer_kept\",\"qty\":" + (expected - actual) + "}]}]}";
        return post("/api/delivery/orders/" + orderId + "/complete", mgrToken(), body);
    }

    private int overOf(long productId) {
        return intOf("SELECT IFNULL(MAX(over_qty), 0) FROM customer_barrel_over "
                + "WHERE customer_id = ? AND station_id = ? AND product_id = ?", customer, station, productId);
    }

    private int exceptionsOf(long orderId) {
        return intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id = ?", orderId);
    }

    // =========================================================================
    // 一、下单侧：category=2 确实不记桶（这一半我已逐行核对过，这里落成断言）
    // =========================================================================

    @Test
    @DisplayName("下单侧：一次性桶(category=2) 押金 0、delivery_bucket_qty 为空、不打首单买桶标记")
    void disposableBarrelOrderCarriesNoBarrelSnapshot() {
        seed();
        long product = createProduct("农夫山泉 12L 一次性桶", 2, "22.00", "0.00", 1, "22.00");
        createInventoryFull(station, product, 50, 1, "22.00");

        long order = placeOrder(product, 2, "disp-snapshot-1");

        assertEquals(0, decimalOf("SELECT deposit_amount FROM orders WHERE id = ?", order)
                        .compareTo(BigDecimal.ZERO),
                "一次性桶不得收押金（OrderServiceImpl:361 的 else 分支按 deposit×qty 收取，deposit=0 才是 0）");
        assertNull(jdbc.queryForObject("SELECT delivery_bucket_qty FROM orders WHERE id = ?",
                        Integer.class, order),
                "delivery_bucket_qty 必须为空 —— 它是回桶核对的唯一总闸");
        assertEquals(0, intOf("SELECT first_barrel_order FROM orders WHERE id = ?", order),
                "一次性桶订单不得打上「本站第一笔买桶订单」");
    }

    // =========================================================================
    // 二、配送侧：这才是真问题所在
    // =========================================================================

    @Test
    @DisplayName("★ 配送侧：一次性桶(category=2) 完成配送后 over 必须为 0、不得生成桶异常单")
    void completingDisposableBarrelOrderLeavesBarrelLedgerUntouched() {
        seed();
        long product = createProduct("畅饮吧 15L 一次性桶", 2, "22.00", "0.00", 1, "22.00");
        createInventoryFull(station, product, 50, 1, "22.00");

        long order = placeOrder(product, 2, "disp-ledger-1");
        dispatch(order);

        Api res = complete(order, 2, 0);
        assertTrue(res.isSuccess(), "完成配送应成功，实际=" + res);

        // 用 assertAll：让三条断言一次性全报出来，避免"改一个才发现下一个"
        assertAll("一次性桶完成配送后，桶账必须完全干净",
                () -> assertEquals(4, intOf("SELECT status FROM orders WHERE id = ?", order),
                        "订单应闭环为 已完成(4)"),
                () -> assertEquals(0, overOf(product),
                        "一次性桶没有可持有的桶，完成配送后 over 必须为 0（over = 物理在手 − 权益）"),
                () -> assertEquals(0, exceptionsOf(order),
                        "一次性桶无桶可还，不得生成桶异常单 —— 否则站长要人工处置一条不存在的欠桶"),
                () -> assertEquals(0, intOf("SELECT COUNT(*) FROM customer_barrel_asset "
                                + "WHERE customer_id = ? AND station_id = ? AND product_id = ?",
                        customer, station, product),
                        "一次性桶不得产生桶资产（权益真相源必须干净）"),
                () -> assertEquals(0, intOf("SELECT COUNT(*) FROM customer_barrel_lot "
                                + "WHERE customer_id = ? AND station_id = ? AND product_id = ?",
                        customer, station, product),
                        "一次性桶不得产生押金条"));
    }

    // =========================================================================
    // 四、混合单（方案 A 相对方案 B 的价值）：一单同时含循环桶与一次性桶时，
    //     桶账只记循环桶那一部分 —— 以 delivery_bucket_qty 为闸的修法在这里会漏。
    // =========================================================================

    @Test
    @DisplayName("★ 混合单：桶装水 2 桶 + 一次性桶 3 件，桶账只记桶装水（over 只按桶算）")
    void mixedOrderOnlyCountsReusableBarrels() {
        seed();
        long barrel = createProduct("普利思 18.9L 循环桶", 1, "12.00", "50.00", 1, "12.00");
        long disposable = createProduct("怡宝 12.8L 一次性桶", 2, "18.70", "0.00", 1, "18.70");
        createInventoryFull(station, barrel, 50, 1, "12.00");
        createInventoryFull(station, disposable, 50, 1, "18.70");

        // 一单两件商品：下单侧要能同时装下"桶装水的缺桶押金"与"一次性桶的免押"
        String body = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"mixed-1\",\"items\":["
                + "{\"productId\":" + barrel + ",\"quantity\":2},"
                + "{\"productId\":" + disposable + ",\"quantity\":3}]}";
        Api created = post("/api/orders/create", cusToken(), body);
        assertTrue(created.isSuccess(), "混合单应能创建，实际=" + created);
        long order = created.data().path("orderId").asLong();

        // 桶装水缺 2 桶 → 押金 2×50；一次性桶 3 件不产生任何押金
        assertEquals(0, decimalOf("SELECT deposit_amount FROM orders WHERE id = ?", order)
                        .compareTo(new BigDecimal("100.00")),
                "混合单押金必须只由循环桶的缺桶数决定（100=2×50），一次性桶不摊押金");

        dispatch(order);
        Api res = complete(order, 2, 0);
        assertTrue(res.isSuccess(), "完成配送应成功，实际=" + res);

        assertAll("混合单的桶账必须只反映循环桶",
                () -> assertEquals(2, intOf("SELECT IFNULL(SUM(quantity), 0) FROM customer_barrel_asset "
                                + "WHERE customer_id = ? AND station_id = ? AND product_id = ?",
                        customer, station, barrel),
                        "循环桶 2 桶买下权益"),
                () -> assertEquals(0, overOf(barrel),
                        "循环桶首单权益 2 = 送出 2 → 不欠桶"),
                () -> assertEquals(0, intOf("SELECT COUNT(*) FROM customer_barrel_over "
                                + "WHERE customer_id = ? AND station_id = ? AND product_id = ?",
                        customer, station, disposable),
                        "一次性桶 3 件**不得**在 over 表里留下任何行（连 0 行都不该有）"));
    }

    // =========================================================================
    // 三、对照：同样 2 件，循环桶(category=1) 必须照常走桶账
    //     没有这一条，上面那条可能只是"系统坏到什么都不记"而侥幸变绿。
    // =========================================================================

    @Test
    @DisplayName("对照：同为 2 件，循环桶(category=1) 建出 2 个桶权益与押金条 —— 证明上一条不是空跑")
    void barrelProductStillBuildsBarrelRights() {
        seed();
        long product = createProduct("普利思 天然泉水 18.9L 桶装水", 1, "12.00", "50.00", 1, "12.00");
        createInventoryFull(station, product, 50, 1, "12.00");

        long order = placeOrder(product, 2, "ctrl-ledger-1");
        dispatch(order);

        Api res = complete(order, 2, 0);
        assertTrue(res.isSuccess(), "完成配送应成功，实际=" + res);

        // 首单：权益 0 → shortage=2 → 2 个在途桶 → 送达转成 2 个桶权益 + 2 张押金条。
        // 桶账恒等式 delta = delivered(2) − returned(0) − rightPurchase(2) = 0，故 over 为 0
        // —— 这不是"没记账"，而是"买桶的权益正好抵掉送出的实物"。
        assertEquals(2, intOf("SELECT IFNULL(SUM(quantity), 0) FROM customer_barrel_asset "
                        + "WHERE customer_id = ? AND station_id = ? AND product_id = ?", customer, station, product),
                "循环桶必须建出 2 个桶权益（客户买下了 2 个桶）");
        assertEquals(2, intOf("SELECT IFNULL(SUM(remain_qty), 0) FROM customer_barrel_lot "
                        + "WHERE customer_id = ? AND station_id = ? AND product_id = ?", customer, station, product),
                "循环桶必须建出 2 张押金条（退桶时的退款依据）");
        assertEquals(0, overOf(product),
                "首单买桶的权益正好抵掉送出的实物，over 应为 0（客户不欠桶）");
    }

    @Test
    @DisplayName("★ 对照二：循环桶复购且未还空桶 → over 必须增长并生成桶异常单")
    void barrelProductOnRepurchaseRecordsOwedBuckets() {
        seed();
        long product = createProduct("普利思 天然泉水 18.9L 桶装水", 1, "12.00", "50.00", 1, "12.00");
        createInventoryFull(station, product, 50, 1, "12.00");

        // 首单：买下 2 个桶（权益 2）
        long first = placeOrder(product, 2, "ctrl-first");
        dispatch(first);
        assertTrue(complete(first, 2, 0).isSuccess(), "首单完成配送应成功");

        // 复购：权益已有 2 → shortage=0 → 不建在途桶；客户手上还端着 2 个桶且没还
        long second = placeOrder(product, 2, "ctrl-second");
        dispatch(second);
        Api res = complete(second, 2, 0);
        assertTrue(res.isSuccess(), "复购完成配送应成功，实际=" + res);

        assertEquals(2, overOf(product),
                "复购送出 2 个、收回 0 个、无新购权益 → 客户欠 2 个桶，over 必须为 2");
        assertEquals(1, exceptionsOf(second),
                "循环桶少回 2 个必须生成桶异常单 —— 这条与一次性桶那条形成正反对照");
    }
}
