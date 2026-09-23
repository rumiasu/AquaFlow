package com.example.aquaflow.support;

import com.example.aquaflow.service.ReconciliationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务链路（scenario）测试基类 —— 在 {@link AbstractIntegrationTest} 之上，把「一个能真实经营的水站」
 * 一次造好，并把订单全生命周期的每一步包成有业务语义的方法。
 *
 * <p><b>它和既有集成测试的分工</b>：既有用例是「环节断言」—— 每个类盯住一条规则、一个端点、
 * 一个分支（例如 {@code DeliveryCompleteIntegrationTest} 盯 {@code collected} 字段会不会被静默丢弃）。
 * 本基类服务的是另一层：<b>把链路整条走完，然后断言"走完之后系统仍然自洽"</b>。
 * 两者的缺口不同 —— 环节全绿而链路自相矛盾是可能的（同一份口径在 A 处写、在 B 处读，两边各自"正确"），
 * 那正是 {@code docs/audit/2026-09-16-场景测试矩阵.md} 里 S1~S9 的编号所指的东西。</p>
 *
 * <p><b>造数纪律（踩过一次，别再踩）</b>：客户资产（押金 / 桶权益 / 水票批次）<b>必须由真实 HTTP 流程产生</b>，
 * 不能用 SQL 直接塞 {@code customer_barrel_asset} / {@code customer_deposit_account}。
 * 直接用 SQL 塞出来的状态，对账的 SE5/SE6 立刻报差异 —— 那是<b>用例造了一个不可能的状态</b>，
 * 不是产品缺陷（矩阵 §8 第 168 行记着这次误判）。所以本基类只提供"下单/接单/送达/收款"这类入口，
 * 并<b>故意不提供</b>任何直接写桶账、写押金的夹具方法。</p>
 *
 * <p><b>库存必须成对造</b>：等式4 是 {@code inventory.quantity = SUM(inventory_record.delta)}，
 * 只插 {@code inventory} 不插流水会让对账不平。{@link #openStation} 已经成对处理。</p>
 */
public abstract class AbstractScenarioTest extends AbstractIntegrationTest {

    /**
     * 直接注入对账服务：{@code runReconcile()} / {@code runReconcileV2()} 是公开方法，
     * 能一次拿到 <b>全量</b>等式，比站长端 {@code /api/manager/reconciliation} 的
     * 「只暴露 SE1/SE3/SE4/SE5/SE6」更全（等式2 只在 03:00 的日结里算，见
     * {@code ReconciliationService} 的注释）。链路用例要的是全量。
     */
    @Autowired
    protected ReconciliationService reconciliationService;

    /**
     * 一个可用的经营现场。
     *
     * @param productName 桶装水商品名（{@code category=1}，会走押金与桶权益规则）
     */
    protected record World(long stationId, long managerId, long driverId, long productId,
                           long customerId, long addressId, int initialStock,
                           String managerToken, String driverToken, String customerToken,
                           String productName, String label) {
    }

    /**
     * 开出「一个能真实经营的水站」：水站 + 站长 + 配送员 + 桶装水商品 + 库存（含入库流水）
     * + 客户 + 地址 + <b>已开通货到付款</b>。
     *
     * <p>货到付款必须开通：未开通的客户下现金单会在<b>下单那一刻就被拒</b>，
     * 于是"现金单却未开通"这个状态在系统里根本不该存在（见 {@code PaidBeforeDispatchIntegrationTest}）。
     * 链路用例要测的是现金单走得通，所以这一步是前置而不是被测对象。</p>
     *
     * @param label 同时用作水站名、员工名、客户名与 openid 前缀 —— 一次用例里开多个站时必须各不相同
     */
    protected World openStation(String label, String price, String deposit, int stock) {
        long station = createStation(label);
        long manager = createStaff(label + "站长", "STATION_MANAGER", station, 1);
        long driver = createStaff(label + "配送员", "DELIVERY", station, 1);
        String productName = label + "桶装水18.9L";
        long product = createProduct(productName, 1, price, deposit, 1, "9.00");
        createInventoryFull(station, product, stock, 1, "9.00");
        // 成对造流水：只造 inventory 会让对账等式4 不平（见类注释）
        createInventoryRecord(station, product, stock, "in", product);
        long customer = createCustomer(label + "客户", label + "-openid");
        long address = createAddress(customer, label + "小区 1 号");
        createCustomerStationConfig(customer, station, 1);
        return new World(station, manager, driver, product, customer, address, stock,
                staffToken(manager, "STATION_MANAGER", station),
                staffToken(driver, "DELIVERY", station),
                customerToken(customer),
                productName, label);
    }

    /** 便捷重载：20 元/桶、30 元押金、库存 100。 */
    protected World openStation(String label) {
        return openStation(label, "20.00", "30.00", 100);
    }

    /* ==================== 链路步骤（全部走真实 HTTP） ==================== */

    /** 客户下单。payMethod 取 {@code PayMethod}：1 微信 / 2 现金 / 3 水票。 */
    protected Api placeOrder(World w, String idempotencyKey, int payMethod, int qty) {
        return post("/api/orders/create", w.customerToken(),
                "{\"addressId\":" + w.addressId()
                        + ",\"stationId\":" + w.stationId()
                        + ",\"paymentMethod\":" + payMethod
                        + ",\"idempotencyKey\":\"" + idempotencyKey + "\""
                        + ",\"items\":[{\"productId\":" + w.productId()
                        + ",\"quantity\":" + qty + "}]}");
    }

    /** 幂等键 → 订单 id。下单接口不直接回订单 id，故按幂等键反查。 */
    protected long orderIdOf(String idempotencyKey) {
        return longOf("SELECT id FROM orders WHERE idempotency_key=?", idempotencyKey);
    }

    /** 配送员接单（1 待配送 → 2 配送中）。 */
    protected Api acceptOrder(World w, long orderId) {
        return post("/api/delivery/orders/" + orderId + "/accept", w.driverToken(), "{}");
    }

    /** 站长把单分配给配送员（不接单，直接指派）。 */
    protected Api assignOrder(World w, long orderId) {
        return post("/api/delivery/orders/assign/" + orderId, w.managerToken(),
                "{\"deliveryStaffId\":" + w.driverId() + "}");
    }

    /**
     * 配送员完成配送（2 配送中 → 3 已送达，现场收款时直接 → 4 已完成）。
     *
     * @param expected 应收空桶数（服务端会自己按 order_item 反查商品，这里只为可读性）
     * @param returned 实收空桶数
     * @param collected 货到付款是否已现场收款；<b>false 与不传在这一层是等价的</b>，
     *                  要验"字段有没有被静默丢弃"请用 {@link #completeDeliveryNoCollectedField}
     */
    protected Api completeDelivery(World w, long orderId, int expected, int returned,
                                   boolean collected, String note) {
        long itemId = longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", orderId);
        StringBuilder body = new StringBuilder("{\"itemReturns\":[{\"orderItemId\":").append(itemId)
                .append(",\"expected\":").append(expected)
                .append(",\"actual\":").append(returned)
                .append(",\"reasons\":[]}]")
                .append(",\"collected\":").append(collected);
        if (note != null) {
            body.append(",\"note\":\"").append(note).append("\"");
        }
        body.append("}");
        return post("/api/delivery/orders/" + orderId + "/complete", w.driverToken(), body.toString());
    }

    /** 完成配送但<b>整段不下发 collected 字段</b> —— 用于对照"传了到底有没有用"（§8.15 的形状）。 */
    protected Api completeDeliveryNoCollectedField(World w, long orderId, int expected, int returned) {
        long itemId = longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", orderId);
        return post("/api/delivery/orders/" + orderId + "/complete", w.driverToken(),
                "{\"itemReturns\":[{\"orderItemId\":" + itemId
                        + ",\"expected\":" + expected + ",\"actual\":" + returned + ",\"reasons\":[]}]}");
    }

    /**
     * 站长/配送员确认线下（现金）收款 —— 「送达时没收，回头再收」这条链的正门。
     * 已在 已送达(3) 时会顺带闭环为 已完成(4)。
     */
    protected Api confirmOfflinePay(World w, long orderId) {
        return post("/api/delivery/orders/" + orderId + "/confirm-offline-pay", w.managerToken(), "{}");
    }

    /* ==================== 读取 ==================== */

    /** 该订单是否出现在某列表端点返回的数组里（列表看不到 ≠ 接口调不动，故两侧都要断言）。 */
    protected boolean inList(String path, String token, long orderId) {
        Api res = get(path, token);
        assertEquals(0, res.code(), path + " 应可读: " + res);
        JsonNode data = res.data();
        if (data == null || !data.isArray()) {
            return false;
        }
        for (JsonNode node : data) {
            if (node.path("id").asLong() == orderId) {
                return true;
            }
        }
        return false;
    }

    /* ==================== 不变量断言（链路用例的真正价值在这里） ==================== */

    /**
     * 全平台对账 V1 + V2 每一条等式都必须为 0。
     *
     * <p>这是链路用例最重的一条断言，也是它<b>不能被环节用例替代</b>的原因：
     * 环节用例各自只断言"我这一条等式"（E8 / E10 / E-PAY），而链路走完真正的风险是
     * <b>把别的等式弄不平</b> —— 一旦这样，每天 03:00 的日结会报不平并发出 SYSTEM 告警，
     * 真问题会被假警报淹没。</p>
     *
     * <p>失败信息里带全量 map：哪一项不平、差多少，一次运行就能定位。</p>
     */
    protected void assertReconcileBalanced(String what) {
        Map<String, Integer> v1 = reconciliationService.runReconcile();
        assertTrue(v1.size() >= 4, "V1 至少应含 押金/支付/桶/库存 四项，实际=" + v1.keySet());
        // 不先断言"总和为 0"：那样第一条失败信息只说"不平了"，说不出是哪一项。
        // 逐项断言的消息里已经带上全量 map，定位信息更全（实测靠它一眼看出是 barrelState）。
        for (Map.Entry<String, Integer> e : v1.entrySet()) {
            assertEquals(0, e.getValue(), what + "：V1 检查项 " + e.getKey() + " 应为 0，实际=" + e.getValue()
                    + "；全量=" + v1);
        }

        Map<String, Integer> v2 = reconciliationService.runReconcileV2();
        assertTrue(v2.size() >= 3, "V2 应含权益批次/占用恒等/物理守恒等项，实际=" + v2.keySet());
        for (Map.Entry<String, Integer> e : v2.entrySet()) {
            assertEquals(0, e.getValue(), what + "：V2 检查项 " + e.getKey() + " 应为 0，实际=" + e.getValue()
                    + "；全量=" + v2);
        }
    }

    /** 站长端即时对账（按站隔离的那一份）也必须零差异 —— 站长看得见的那块屏幕不能报红。 */
    protected void assertStationReconcileClean(World w, String what) {
        Api res = get("/api/manager/reconciliation", w.managerToken());
        assertEquals(0, res.code(), what + "：站长端对账应可读: " + res);
        assertEquals(0, res.data().path("totalDiff").asInt(),
                what + "：站长端 totalDiff 应为 0，实际=" + res.data());
    }

    /**
     * 库存守恒：{@code inventory.quantity == SUM(inventory_record.delta)}（对账等式4 的按商品版）。
     *
     * <p>单独再查一遍而不是只靠 {@link #assertReconcileBalanced}：等式4 是<b>全平台</b>聚合，
     * 用例里只有一站一商品时它照样能过，但过不了的时候这条能直接指出"是这个商品差了几件"。</p>
     */
    protected void assertInventoryConserved(World w, String what) {
        int onHand = intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                w.stationId(), w.productId());
        int flowSum = intOf("SELECT IFNULL(SUM(delta),0) FROM inventory_record WHERE station_id=? AND product_id=?",
                w.stationId(), w.productId());
        assertEquals(flowSum, onHand, what + "：库存实物量必须等于流水净和（等式4）");
    }

    /**
     * 桶账闭环：「配送中」不留 PENDING 残行，且恒等式 <b>占用 = 权益 + over</b> 成立。
     *
     * <p>占用口径取<b>并集</b>（权益批次 ∪ 配送中 PENDING ∪ over）—— 只遍历
     * {@code customer_barrel_asset} 是本仓惯犯（AGENTS.md §8.16，第 4 次）。这里权益取
     * {@code customer_barrel_lot.remain_qty}（真相源），不是 {@code customer_barrel_asset.quantity}
     * （派生汇总，由对账 SE3 保证两者一致）。</p>
     */
    protected void assertBarrelLedgerClosed(World w, String what) {
        int pending = intOf("SELECT IFNULL(SUM(qty),0) FROM customer_barrel_in_transit "
                + "WHERE customer_id=? AND station_id=? AND status='PENDING'", w.customerId(), w.stationId());
        assertEquals(0, pending, what + "：送达完成后不该残留「配送中(PENDING)」的桶");

        int rights = intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND status=1", w.customerId(), w.stationId());
        int over = intOf("SELECT IFNULL(SUM(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=?", w.customerId(), w.stationId());
        int asset = intOf("SELECT IFNULL(SUM(quantity),0) FROM customer_barrel_asset "
                + "WHERE customer_id=? AND station_id=?", w.customerId(), w.stationId());
        assertEquals(rights, asset, what + "：权益汇总必须等于批次净和（对账 SE3 的口径）");
        assertTrue(rights + over >= 0, what + "：占用 = 权益 + over 不得为负（物理护栏），实际权益="
                + rights + " over=" + over);
    }

    /**
     * 「正常写入必须落 {@code settle_station_id}」。
     *
     * <p>读取侧一律 {@code coalesce(settle_station_id, delivery_station_id, station_id)}，
     * 末级只是<b>防御</b>：真靠 coalesce 兜住说明写侧漏了，钱会记到别人头上。</p>
     */
    protected void assertSettleStationBooked(long orderId, long expectedStationId, String what) {
        Long settle = jdbc.queryForObject("SELECT settle_station_id FROM orders WHERE id=?", Long.class, orderId);
        assertNotNull(settle, what + "：写侧必须落 settle_station_id（coalesce 只是读取侧的防御）");
        assertEquals(expectedStationId, settle.longValue(), what + "：结算站不对，钱会记到别人头上");
    }

    /** 已付款的单必须<b>恰好一条</b> PAID 流水，且金额等于订单应收合计。 */
    protected void assertPaymentBookedOnce(long orderId, String expectedAmount, String what) {
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", orderId),
                what + "：已付款必须恰有一条 PAID 流水（多了会重复计账，少了日结无凭证）");
        assertEquals(0, decimalOf("SELECT amount FROM payment_record WHERE order_id=? AND status=2", orderId)
                        .compareTo(new BigDecimal(expectedAmount)),
                what + "：收款金额应取订单应收合计 " + expectedAmount);
    }

    /** 押金账户余额（不存在时按 0 算）。 */
    protected BigDecimal depositBalance(World w) {
        return decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                + "WHERE customer_id=? AND station_id=?", w.customerId(), w.stationId());
    }
}
