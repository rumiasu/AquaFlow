package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款必须<b>原路返回</b>（2026-09-18 产品裁定）—— 站长手工退款 {@code PUT /api/payments/{id}/refund}。
 *
 * <p>本类盯的是「钱到底有没有按原来的方式回去，以及有没有留下与之一致的凭据」，
 * 不是接口返回是否成功：一次 {@code code=0} 完全可能伴随着"界面说退了、账上没动"。
 * 三件事各自独立成用例：</p>
 * <ol>
 *   <li><b>凭据</b>：原流水转已退款之外，还必须<b>新增一条负金额冲正流水</b>
 *       （形状与取消订单链 {@code refundOrder} 完全一致）；</li>
 *   <li><b>水票</b>：水票支付的钱就是票 —— 退款后余额与批次必须回到退款前，且对账 E8 成立；</li>
 *   <li><b>微信</b>：渠道未接入，<b>必须拒绝</b>，绝不能把流水标成已退款而钱没退。</li>
 * </ol>
 *
 * <p>与既有用例的分工（避免重复）：
 * {@code DeliveryConsoleAndSelfServiceIntegrationTest.adHocPaymentRefundKeepsPaymentStatusForward}
 * 已锁「顾客不得退款 / 原流水转已退款 / 订单支付状态不倒滚 / 重复退款被拒」；
 * {@code CrossStationRefundIntegrationTest} 已锁「取消链的站别口径」。
 * 本类只补它<b>没有</b>的：负金额凭据、水票回补、微信拒绝、以及手工退款的跨站越权
 * （既有用例里没有这一条，见 {@code DeliveryConsoleAndSelfServiceIntegrationTest:254-281}）。</p>
 */
class PaymentRefundOriginalRouteIntegrationTest extends AbstractIntegrationTest {

    /**
     * 造一条**字段齐全**的已付款流水。
     *
     * <p>⚠️ 不要用基类的 {@code createPaymentRecord(orderId, customerId, stationId, amount, method, status)}：
     * 它只插这 6 列，{@code water_amount} / {@code barrel_deposit} / {@code excess_barrels}
     * 全落在库默认值上（0.00 / 0.00 / 0），于是"逐字段取负"根本无从验证 ——
     * 冲正流水写成 0 也照样"通过"（实测第一版就是这么假绿的）。
     * 本用例要验的正是金额拆分逐字段取负，所以必须自己写全字段。</p>
     */
    private long seedPaymentWithAmountBreakdown(long orderId, long customer, long station,
                                               String amount, int paymentMethod,
                                               String waterAmount, String barrelDeposit, int excessBarrels) {
        return insert("INSERT INTO payment_record(order_id, customer_id, station_id, amount, water_amount, "
                        + "barrel_deposit, excess_barrels, payment_method, status, transaction_no, operator_id, note, "
                        + "create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,2,'TX-SEED',NULL,'站长确认收款',NOW(),NOW())",
                orderId, customer, station, new BigDecimal(amount),
                new BigDecimal(waterAmount), new BigDecimal(barrelDeposit), excessBarrels, paymentMethod);
    }

    /**
     * 复核对账 E8 的两条等式（{@code docs/design/19} §6）：水票余额的真相源是 {@code ticket_lot}，
     * {@code ticket_account} 只是派生汇总。回补若绕开批次账，这里立刻不平。
     */
    private void assertTicketBookConsistent(long customerId, long stationId, long productId, String scene) {
        int accountQty = intOf("SELECT COALESCE(remain_quantity,0) FROM ticket_account "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customerId, stationId, productId);
        int lotQty = intOf("SELECT COALESCE(SUM(remain_qty),0) FROM ticket_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customerId, stationId, productId);
        assertEquals(accountQty, lotQty, scene + " · E8 数量等式：账户余额必须等于 Σ 批次剩余");

        BigDecimal accountAmount = decimalOf("SELECT COALESCE(right_amount,0) FROM ticket_account "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customerId, stationId, productId);
        BigDecimal lotAmount = decimalOf("SELECT COALESCE(SUM(remain_qty * unit_price),0) FROM ticket_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customerId, stationId, productId);
        assertTrue(accountAmount.subtract(lotAmount).abs().compareTo(new BigDecimal("0.009")) <= 0,
                scene + " · E8 金额等式：账户金额价值必须等于 Σ 剩余×批次单价（账户 " + accountAmount
                        + " vs 批次 " + lotAmount + "）");
    }

    @Test
    @DisplayName("手工退款：新增负金额冲正流水，形状与取消订单链一致，订单支付状态为已退款")
    void manualRefundWritesNegativeVoucher() {
        long station = createStation("手工退款站");
        long manager = createStaff("手工退款站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("手工退款客户", "manualrefund-openid");
        long product = createProduct("手工退款水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "手工退款地址");
        long orderId = createOrderFull(customer, address, station, product, 1, 2, 2 /* 现金 */,
                "10.00", "30.00", "40.00", true, 1);
        long paymentId = seedPaymentWithAmountBreakdown(orderId, customer, station, "40.00", 2 /* 现金 */,
                "10.00", "30.00", 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api refunded = put("/api/payments/" + paymentId + "/refund", mgr, "{\"note\":\"客户投诉多收\"}");
        assertEquals(0, refunded.code(), "手工退款: " + refunded);

        // ---- 原流水：已退款(3) ----
        assertEquals(3, intOf("SELECT status FROM payment_record WHERE id=?", paymentId),
                "原支付流水必须转已退款(3)");
        assertEquals(3, intOf("SELECT payment_status FROM orders WHERE id=?", orderId),
                "订单支付状态应为已退款(3)（既有用例已锁「不得倒滚」，这里只作为本用例的前置）");

        // ---- 新增凭据：负金额冲正流水 ----
        // 这里是本次修复的核心缺口：原文只把原流水改成"已退款"，资金流水里看不到这笔支出，
        // 对账等式2 也就无从复核"退了多少、按什么方式退的"。
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND amount < 0", orderId),
                "必须新增一条负金额退款流水（旧实现一条都没有）");
        assertEquals(0, decimalOf("SELECT amount FROM payment_record WHERE order_id=? AND amount < 0", orderId)
                        .compareTo(new BigDecimal("-40.00")),
                "冲正金额必须是原流水的负数");
        assertEquals(2, intOf("SELECT payment_method FROM payment_record WHERE order_id=? AND amount < 0", orderId),
                "支付方式必须沿用原流水（原路返回的凭据）");
        assertEquals(customer, longOf("SELECT customer_id FROM payment_record WHERE order_id=? AND amount < 0", orderId),
                "客户必须沿用原流水");
        assertEquals(station, longOf("SELECT station_id FROM payment_record WHERE order_id=? AND amount < 0", orderId),
                "水站必须沿用原流水（钱收在哪站就记哪站）");
        assertEquals(3, intOf("SELECT status FROM payment_record WHERE order_id=? AND amount < 0", orderId),
                "冲正流水状态必须是已退款(3)：对账等式2 的 p2d 项要求订单 payment_status=3 时存在 status=3 的流水");
        // 形状与 refundOrder 那条逐字段对齐：水费/押金/超出桶数一律取负
        assertEquals(0, decimalOf("SELECT water_amount FROM payment_record WHERE order_id=? AND amount < 0", orderId)
                        .compareTo(new BigDecimal("-10.00")), "水费金额取负");
        assertEquals(0, decimalOf("SELECT barrel_deposit FROM payment_record WHERE order_id=? AND amount < 0", orderId)
                        .compareTo(new BigDecimal("-30.00")), "桶押金取负");
        assertEquals(-1, intOf("SELECT excess_barrels FROM payment_record WHERE order_id=? AND amount < 0", orderId),
                "超出桶数取负");
        String note = jdbc.queryForObject(
                "SELECT note FROM payment_record WHERE order_id=? AND amount < 0", String.class, orderId);
        assertTrue(note != null && note.contains("手工退款") && note.contains("客户投诉多收"),
                "手工退款的 note 必须写清是手工退款及原因，实际=" + note);
        assertTrue(note.contains("现金") && note.contains("当面"),
                "现金退款的 note 必须体现「钱由站长当面退还」，实际=" + note);
        // 现金没有线上渠道，不该有人去调微信退款；这里同时锁"没多出别的流水"
        assertEquals(2, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=?", orderId),
                "该单应恰好两条流水：原流水 + 一条冲正");

        // ---- 对账等式2：不得残留差异 ----
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders o WHERE o.payment_status <> 2 "
                        + "AND EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 2)"),
                "等式2 p2b：已退款订单不该还挂着 status=2 的流水");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders o WHERE o.payment_status = 3 "
                        + "AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 3)"),
                "等式2 p2d：已退款订单必须有 status=3 的流水");
    }

    @Test
    @DisplayName("水票支付的手工退款：余额与批次回到退款前，且 E8（数量 + 金额）成立")
    void ticketPaidRefundRestoresTicketBook() {
        long station = createStation("水票退款站");
        long manager = createStaff("水票退款站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("水票退款客户", "ticketrefund-openid");
        long product = createProduct("水票退款水", 1, "10.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");
        long address = createAddress(customer, "水票退款地址");
        long orderId = createOrderFull(customer, address, station, product, 1, 2, 3 /* 水票 */,
                "10.00", "30.00", "40.00", true, 1);
        createOrderItem(orderId, product, "水票退款水", 1, "10.00", "30.00", 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 前置：客户账上 10 张、单一批次（单价 8.00）—— 用**显式单批次**而不是基类夹具，
        // 基类 createTicketAccount 的批次单价是 0.00（"测试夹具不关心票值"），
        // 那样就验不出"按**当时消耗的批次单价**还原"这条口径。
        // 状态：1 张被这张订单用掉 → 账户 9 张、批次 9 张、金额价值 72.00。
        long accountId = insert("INSERT INTO ticket_account(customer_id, product_id, station_id, remain_quantity, "
                        + "right_amount) VALUES (?,?,?,9,72.00)", customer, product, station);
        insert("INSERT INTO ticket_lot(lot_no, customer_id, station_id, product_id, unit_price, qty, remain_qty, "
                        + "source_type, price_source, is_migrated, status) "
                        + "VALUES ('LOT-REFUND-1',?,?,?,8.00,10,9,1,1,0,1)", customer, station, product);
        long paymentId = insert("INSERT INTO payment_record(order_id, customer_id, station_id, amount, "
                        + "water_amount, barrel_deposit, excess_barrels, payment_method, status, note, "
                        + "create_time, update_time) VALUES (?,?,?,40.00,10.00,30.00,1,3,2,'水票支付',NOW(),NOW())",
                orderId, customer, station);
        insert("INSERT INTO ticket_record(customer_id, product_id, station_id, increase_qty, decrease_qty, "
                        + "order_id, source, ticket_source, unit_price, create_time) "
                        + "VALUES (?,?,?,0,1,?,'消费',1,8.00,NOW())",
                customer, product, station, orderId);

        // 退款前的账（同时也是断言基线，避免把期望值写死在用例里）
        int qtyBefore = intOf("SELECT remain_quantity FROM ticket_account WHERE id=?", accountId);
        BigDecimal amountBefore = decimalOf("SELECT right_amount FROM ticket_account WHERE id=?", accountId);
        int lotBefore = intOf("SELECT COALESCE(SUM(remain_qty),0) FROM ticket_lot WHERE customer_id=? "
                + "AND station_id=? AND product_id=? AND status=1", customer, station, product);
        assertEquals(9, qtyBefore, "前置：客户账上应为 9 张（10 张买了、1 张被本单用掉）");
        assertEquals(9, lotBefore, "前置：批次账也是 9 张（账户余额的真相源就是它）");
        assertEquals(0, amountBefore.compareTo(new BigDecimal("72.00")), "前置：剩余价值 = 9 × 8.00");

        Api refunded = put("/api/payments/" + paymentId + "/refund", mgr, "{\"note\":\"客户投诉重复付款\"}");
        assertEquals(0, refunded.code(), "水票支付的手工退款: " + refunded);

        // ---- 水票原路回补 ----
        // 旧实现只把流水改成"已退款"，客户票一张不回来 —— 界面说退了、账上没动（AGENTS.md §8.15）。
        assertEquals(qtyBefore + 1, intOf("SELECT remain_quantity FROM ticket_account WHERE id=?", accountId),
                "水票余额必须回到退款前（+1 张）");
        assertEquals(lotBefore + 1, intOf("SELECT COALESCE(SUM(remain_qty),0) FROM ticket_lot WHERE customer_id=? "
                        + "AND station_id=? AND product_id=? AND status=1", customer, station, product),
                "批次账也必须同步回补（余额的真相源是 ticket_lot）");
        assertEquals(0, decimalOf("SELECT right_amount FROM ticket_account WHERE id=?", accountId)
                        .compareTo(amountBefore.add(new BigDecimal("8.00"))),
                "金额价值必须按**当时消耗的批次单价 8.00**还原，而不是退款时的当前价");
        assertTicketBookConsistent(customer, station, product, "手工退款后");

        // 回补必须过批次账、留可追溯的流水
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record WHERE order_id=? AND product_id=? "
                        + "AND increase_qty > 0 AND source = '退款'", orderId, product),
                "回补必须留下 source='退款' 的水票流水（这是幂等闸门的判据）");
        assertEquals(station, longOf("SELECT station_id FROM ticket_record WHERE order_id=? AND source='退款'",
                        orderId),
                "水票必须退回【归属站】——钱当初就收在归属站");

        // ---- 凭据 ----
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND amount = -40.00", orderId),
                "水票退款同样必须有负金额凭据");
        String note = jdbc.queryForObject(
                "SELECT note FROM payment_record WHERE order_id=? AND amount < 0", String.class, orderId);
        assertTrue(note.contains("水票") && note.contains("回补"), "note 应写清是水票退款并已回补水票：" + note);
        assertEquals(3, intOf("SELECT payment_status FROM orders WHERE id=?", orderId), "订单应为已退款(3)");

        // 顾客侧读到的余额也必须是回补后的值（不只是库里的数字对）
        Api tickets = get("/api/tickets?stationId=" + station, cus);
        assertEquals(0, tickets.code(), "顾客查水票: " + tickets);
        assertEquals(10, tickets.data().get(0).path("remainQuantity").asInt(),
                "顾客看到的余额也必须是 10 张");
    }

    @Test
    @DisplayName("微信流水的手工退款：拒绝（code=1），流水与订单状态都不许动")
    void wechatRefundIsRejectedAndLeavesRecordsUntouched() {
        long station = createStation("微信退款站");
        long manager = createStaff("微信退款站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("微信退款客户", "wechatrefund-openid");
        long product = createProduct("微信退款水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "微信退款地址");
        long orderId = createOrderFull(customer, address, station, product, 1, 2, 1 /* 微信 */,
                "10.00", "30.00", "40.00", true, 1);
        long paymentId = createPaymentRecord(orderId, customer, station, "40.00", 1, 2);

        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api rejected = put("/api/payments/" + paymentId + "/refund", mgr, "{\"note\":\"想退微信\"}");
        assertEquals(1, rejected.code(), "微信渠道未接入时必须给业务拒绝（code=1），实际=" + rejected);
        assertTrue(rejected.message().contains("微信") && rejected.message().contains("线下"),
                "文案必须说清渠道未接入、需线下退款并登记，实际=" + rejected.message());

        // 最重要的断言：拒绝之后账上必须**一点都没动**。
        // 旧实现会把流水标成已退款 —— 界面显示"已退款"、钱仍在客户账上没动，正是本仓最忌的"假装成功"。
        assertEquals(2, intOf("SELECT status FROM payment_record WHERE id=?", paymentId),
                "被拒后原流水必须仍是已付款(2)");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", orderId),
                "被拒后订单支付状态必须仍是已付款(2)");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=?", orderId),
                "被拒后不得留下任何冲正流水");
    }

    @Test
    @DisplayName("无订单的线上购票流水：手工退款被拒（code=1），票与流水都不许动")
    void orderlessTicketPurchaseRefundIsRejected() {
        long station = createStation("购票退款站");
        long manager = createStaff("购票退款站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("购票退款客户", "purchaserefund-openid");
        long product = createProduct("购票退款水", 1, "10.00", "30.00", 1, "8.00");
        createInventoryFull(station, product, 100, 1, "8.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 客户线上买 5 张，站长确认收款（水票入账），形成一条 order_id IS NULL 的已付款流水
        long paymentId = post("/api/tickets/purchase", cus,
                "{\"productId\":" + product + ",\"quantity\":5,\"paymentMethod\":2,\"stationId\":" + station
                        + ",\"idempotencyKey\":\"orderless-refund-1\"}")
                .data().path("paymentId").asLong();
        assertEquals(0, put("/api/payments/" + paymentId + "/confirm", mgr, null).code(), "站长确认收款");
        assertEquals(5, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, product), "前置：5 张已入账");

        // 「退款」= 把已入账的票扣回来，票可能已被用掉（余额不足必须拒绝而非透支），本批不实现。
        // 要求是**拒绝得明明白白**，绝不能静默只改流水状态 —— 那样界面显示"已退款"、票却还在客户账上。
        Api rejected = put("/api/payments/" + paymentId + "/refund", mgr, "{\"note\":\"客户要求退票\"}");
        assertEquals(1, rejected.code(), "无订单购票退款应给业务拒绝（code=1），实际=" + rejected);
        assertTrue(rejected.message().contains("水票"), "文案必须点明是水票场景：" + rejected.message());

        assertEquals(2, intOf("SELECT status FROM payment_record WHERE id=?", paymentId),
                "被拒后该购票流水必须仍是已付款(2)");
        assertEquals(5, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, product), "被拒后水票一张都不许动");
        assertTicketBookConsistent(customer, station, product, "拒绝购票退款后");
    }

    @Test
    @DisplayName("手工退款的越权：非法角色被拒；跨站单只有结算站（履约站）能退，归属站不能")
    void manualRefundIsRoleGuarded() {
        long station = createStation("越权退款站");
        long otherStation = createStation("越权他站");
        long manager = createStaff("越权退款站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("越权配送员", "DELIVERY", station, 1);
        long customer = createCustomer("越权退款客户", "authzrefund-openid");
        long product = createProduct("越权退款水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "越权退款地址");

        // ⚠️ 跨站外派单：归属站 = otherStation、履约站 = station。
        // [2026-09-18 口径二次修订] 产品裁定「水费 + 配送费 + 楼层费归**实际履约站**」，
        // 并新增显式列 orders.settle_station_id（结算站）。本单已指定履约站 → 结算站 = 履约站 = station，
        // 所以**本站站长（履约站）才是这笔钱的主人，他可以退**；归属站反而退不掉。
        long orderId = createOrderCrossStation(customer, address, otherStation, station, product,
                1, 2, 2, "10.00", "30.00", "40.00");
        long paymentId = createPaymentRecord(orderId, customer, otherStation, "40.00", 2, 2);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String del = staffToken(delivery, "DELIVERY", station);
        String cus = customerToken(customer);
        String ownerMgr = staffToken(createStaff("归属站站长", "STATION_MANAGER", otherStation, 1),
                "STATION_MANAGER", otherStation);

        assertNotEquals(0, put("/api/payments/" + paymentId + "/refund", cus, "{\"note\":\"顾客自己退\"}").code(),
                "顾客不得退款");
        assertNotEquals(0, put("/api/payments/" + paymentId + "/refund", del, "{\"note\":\"配送员退\"}").code(),
                "配送员不得退款");

        // 归属站（不是结算站）退不掉：钱不归它
        Api byOwner = put("/api/payments/" + paymentId + "/refund", ownerMgr, "{\"note\":\"归属站退跨站单\"}");
        assertNotEquals(0, byOwner.code(), "归属站不得退已经归履约站结算的单");
        assertEquals(2, intOf("SELECT status FROM payment_record WHERE id=?", paymentId),
                "被拒的退款不得改动流水状态");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=?", orderId),
                "被拒的退款不得留下冲正流水");

        // 结算站（履约站）可以退 —— 钱是它收的、也记它的账
        assertEquals(0, put("/api/payments/" + paymentId + "/refund", mgr, "{\"note\":\"结算站退\"}").code(),
                "结算站（履约站）站长必须能退本单的钱");
        assertEquals(3, intOf("SELECT status FROM payment_record WHERE id=?", paymentId),
                "退款后原流水应为已退款(3)");

        // 同站单（归属=履约=结算）不受影响：退得掉
        long ownOrderId = createOrderFull(customer, address, otherStation, product, 1, 2, 2,
                "10.00", "30.00", "40.00", true, 1);
        long ownPaymentId = createPaymentRecord(ownOrderId, customer, otherStation, "40.00", 2, 2);
        assertEquals(0, put("/api/payments/" + ownPaymentId + "/refund", ownerMgr, "{\"note\":\"本站单退\"}").code(),
                "同站单的站长退本站收的钱必须照常放行（不要把正常路径一起收紧掉）");

        // 生产形态再验一遍：payment_record.station_id 写的是履约站，而判权**不看这一列**（看订单结算站）。
        long prodShapeOrder = createOrderCrossStation(customer, address, otherStation, station, product,
                1, 2, 2, "10.00", "30.00", "40.00");
        long prodShapePayment = createPaymentRecord(prodShapeOrder, customer, station /* 生产形态：该列=履约站 */,
                "40.00", 2, 2);
        assertNotEquals(0, put("/api/payments/" + prodShapePayment + "/refund", ownerMgr,
                        "{\"note\":\"归属站退\"}").code(),
                "归属站依然退不掉：判权认订单结算站，不认 payment_record.station_id");
        assertEquals(2, intOf("SELECT status FROM payment_record WHERE id=?", prodShapePayment),
                "被拒的退款不得改动流水状态");
        assertEquals(0, put("/api/payments/" + prodShapePayment + "/refund", mgr,
                        "{\"note\":\"结算站退\"}").code(),
                "反过来结算站（履约站）必须退得掉 —— 否则跨站单两个站都退不了，等于功能不可用");
    }
}
