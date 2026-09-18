package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送桶异常全链路：<b>少收空桶 → 异常单 → 站长处置补偿 → 对账仍守恒</b>。
 *
 * <p>这是欠桶的**业务源头**：配送员上门时应收 N 个空桶、实收 M 个（M&lt;N），
 * 差额就是客户欠的桶。链路是三段：</p>
 * <ol>
 *   <li>完成配送时申报回桶明细 → {@code OrderBarrelExceptionService.recordReturn} 落一条
 *       {@code order_barrel_exception}（状态 {@code STAFF_RECORDED}）并在订单上打异常标记
 *       （{@code exception_category} / {@code barrel_exception_id} / {@code barrel_discrepancy} /
 *       {@code exception_count}）；桶账侧 over 同步增加（欠桶，见 `BarrelLedgerService.applyDelivery`）。</li>
 *   <li>站长处置：{@code POST /api/manager/exceptions/{id}/handle}，动作 APPROVE/MODIFY 会在**同一次调用内**
 *       直接执行补偿；IGNORE 只置 {@code IGNORED}，不动任何账。</li>
 *   <li>补偿落账：退水票走 {@code TicketAccountService}、退现金/减押金走押金唯一入口
 *       （{@code DepositType.MANUAL_GRANT}=9 人工补录押金，余额增加）、调桶资产走 {@code BarrelAssetService}。</li>
 * </ol>
 *
 * <p>被锁死的口径：补偿只能由站长下发的参数决定、**不能重复刷**（终态守卫 + CAS）、
 * 补偿动过账之后对账 5 项仍必须全 0。</p>
 *
 * <p><b>造数纪律</b>：客户手上的桶权益必须由**真实配送流程**产生（第一单买 2 桶、押金入账），
 * 不能直接 SQL 塞 {@code customer_barrel_lot}/{@code customer_barrel_asset} —— 那样没有对应的
 * 物理流水与押金流水，守恒对账 SE5/SE6 必然报差异，用例就会"证明"一个不存在的问题。</p>
 */
@DisplayName("配送异常全链路 · 少收空桶 → 异常单 → 站长处置 → 对账守恒")
class BarrelExceptionFlowIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 50, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    private String completeBody(long itemId, int expected, int actual, boolean collected) {
        return "{\"collected\":" + collected + ",\"itemReturns\":[{\"orderItemId\":" + itemId
                + ",\"expected\":" + expected + ",\"actual\":" + actual
                + ",\"reasons\":[{\"key\":\"customer_kept\",\"qty\":" + (expected - actual) + "}]}]}";
    }

    /**
     * 第一单：客户从零开始买 2 个桶（押金 60 现场收款入账）。这一单把「权益 2」和它的
     * 押金流水、物理流水都造齐，后续用例才有干净的基线。
     */
    private void firstOrderBuysTwoBarrels() {
        long order = createOrderFull(customer, addr, station, product,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */,
                "40.00", "60.00", "100.00", true /* 首次桶装水订单 */, 2);
        long itemId = createOrderItem(order, product, "桶装水18.9L", 2, "20.00", "30.00", 1);
        // 下单时算出的 shortage=2 记在「配送中」，送达时由 applyDelivery 转成权益
        createBarrelInTransit(customer, station, product, 2, "30.00", order, "PENDING");

        Api res = post("/api/delivery/orders/" + order + "/complete", mgrToken(),
                completeBody(itemId, 2, 0, true));
        assertTrue(res.isSuccess(), "首单完成配送应成功，实际=" + res);
        assertEquals(2, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND status=1", customer), "首单后应有 2 个桶权益");
        assertEquals(0, over(), "首单买 2 送 2 收 0 → over 应为 0（不欠）");
        assertEquals(0, depositBalance().compareTo(new BigDecimal("60.00")), "首单押金 60 应已入账");
    }

    /**
     * 第二单（本类的主角）：客户已有 2 个桶权益，这次又要 2 桶，配送员**一个空桶都没收回来**。
     *
     * <p>本单 {@code shortage = max(0, 2 − 2) = 0}，不新购权益也不收押金，于是
     * {@code over} 的净变化 = 送出 2 − 收回 0 − 新购 0 = <b>+2（欠 2 个空桶）</b>，
     * 与异常单上的 {@code discrepancy=2} 严格对应 —— 这条对应关系正是本类要锁的。</p>
     *
     * @return 订单 id
     */
    private long deliverShortByTwo() {
        long order = createOrderFull(customer, addr, station, product,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */,
                "40.00", "0.00", "40.00", false, 2 /* delivery_bucket_qty = 应收 2 */);
        long itemId = createOrderItem(order, product, "桶装水18.9L", 2, "20.00", "0.00", 1);

        Api res = post("/api/delivery/orders/" + order + "/complete", mgrToken(),
                completeBody(itemId, 2, 0, true));
        assertTrue(res.isSuccess(), "完成配送应成功，实际=" + res);
        return order;
    }

    private long onlyExceptionId() {
        return longOf("SELECT id FROM order_barrel_exception ORDER BY id DESC LIMIT 1");
    }

    private String statusOf(long exceptionId) {
        return jdbc.queryForObject("SELECT status FROM order_barrel_exception WHERE id=?", String.class, exceptionId);
    }

    private int over() {
        return intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product);
    }

    private BigDecimal depositBalance() {
        return decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                + "WHERE customer_id=? AND station_id=?", customer, station);
    }

    private int manualGrantRecords() {
        return intOf("SELECT COUNT(*) FROM deposit_record WHERE customer_id=? AND type=9", customer);
    }

    private Api handle(long exceptionId, String body) {
        return post("/api/manager/exceptions/" + exceptionId + "/handle", mgrToken(), body);
    }

    @Test
    @DisplayName("少收 2 个空桶：异常单落库 + 订单打标记 + 欠桶同步 +2，且站长列表能看到")
    void shortReturnCreatesExceptionAndMarksOrder() {
        seed();
        firstOrderBuysTwoBarrels();
        long order = deliverShortByTwo();
        long exId = onlyExceptionId();

        assertEquals(2, intOf("SELECT discrepancy FROM order_barrel_exception WHERE id=?", exId),
                "差额=应收2−实收0");
        assertEquals("RETURN_SHORT", jdbc.queryForObject(
                "SELECT category FROM order_barrel_exception WHERE id=?", String.class, exId), "类别=少回");
        assertEquals("STAFF_RECORDED", statusOf(exId), "新异常应停在「配送员已录入」，等站长处置");
        assertEquals(2, intOf("SELECT delivery_qty FROM order_barrel_exception WHERE id=?", exId), "应收 2");
        assertEquals(0, intOf("SELECT return_qty FROM order_barrel_exception WHERE id=?", exId), "实收 0");
        assertEquals(2, intOf("SELECT suggested_ticket_qty FROM order_barrel_exception WHERE id=?", exId),
                "默认补偿优先级第一档是退水票，建议 2 张");

        assertEquals(2, over(), "欠桶同步 +2（送出 2 − 收回 0 − 新购 0）");

        assertEquals("RETURN_SHORT", jdbc.queryForObject(
                "SELECT exception_category FROM orders WHERE id=?", String.class, order), "订单应打上异常类别");
        assertEquals(exId, longOf("SELECT barrel_exception_id FROM orders WHERE id=?", order), "订单应关联异常单");
        assertEquals(2, intOf("SELECT barrel_discrepancy FROM orders WHERE id=?", order), "订单应记差额");
        assertEquals(1, intOf("SELECT exception_count FROM orders WHERE id=?", order), "异常计数应自增一次");

        // 站长端能看到（前端异常页调的就是这个接口；文案由后端下发，前端不自建映射表）
        Api list = get("/api/manager/exceptions?status=STAFF_RECORDED", mgrToken());
        assertTrue(list.isSuccess(), "站长异常列表应成功，实际=" + list);
        JsonNode row = null;
        for (JsonNode r : list.data().path("records")) {
            if (r.path("id").asLong() == exId) row = r;
        }
        assertTrue(row != null, "列表里应出现刚录入的异常：" + list.data());
        assertEquals("STAFF_RECORDED", row.path("status").asText());
        assertTrue(row.path("statusText").asText().length() > 0, "状态文案应由后端下发");
        assertTrue(row.path("categoryText").asText().length() > 0, "类别文案应由后端下发");
        // 页面要标红"还等着处理"的那几条：判据必须由后端给布尔，前端既不比对中文、也不自带状态表
        assertTrue(row.path("pending").asBoolean(), "待处理的异常单 pending 应为 true");
    }

    @Test
    @DisplayName("站长 APPROVE 退现金：押金入账、流水留痕、异常置已执行，对账仍全 0")
    void approveRefundsCashAndKeepsReconciliationBalanced() {
        seed();
        firstOrderBuysTwoBarrels();
        deliverShortByTwo();
        long exId = onlyExceptionId();

        Api res = handle(exId, "{\"action\":\"APPROVE\",\"refundCashAmount\":30.00,\"managerNote\":\"少回2桶，补30\"}");
        assertTrue(res.isSuccess(), "站长批准应成功，实际=" + res);

        assertEquals("EXECUTED", statusOf(exId), "APPROVE 会在同一次调用里执行补偿");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE id=? AND executed_at IS NOT NULL",
                exId), "应记录执行时间");
        assertEquals(0, depositBalance().compareTo(new BigDecimal("90.00")),
                "押金 60（首单）+ 30（补偿）应 = 90.00");
        assertEquals(1, manualGrantRecords(), "应有一条「人工补录押金(9)」流水");
        assertEquals(0, decimalOf("SELECT amount FROM deposit_record WHERE customer_id=? AND type=9", customer)
                        .compareTo(new BigDecimal("30.00")), "流水金额应为正值 30.00（方向由类型决定）");

        Api rec = get("/api/manager/reconciliation", mgrToken());
        assertTrue(rec.isSuccess(), "对账应成功，实际=" + rec);
        assertEquals(0, rec.data().path("totalDiff").asInt(), "补偿动账后对账必须仍无差异：" + rec.data());
        assertEquals(0, rec.data().path("checks").path("SE1_depositAccount").asInt(),
                "SE1：押金余额必须等于流水合计（补偿走的是押金唯一入口）");
        assertEquals(0, rec.data().path("checks").path("SE5_physicalConservation").asInt(),
                "SE5：占用必须与物理流水自洽（本用例的权益来自真实配送流程）");
    }

    @Test
    @DisplayName("补偿不可重复刷：重复 handle / execute 都被拒，押金不再增加")
    void repeatHandleAndExecuteAreRejected() {
        seed();
        firstOrderBuysTwoBarrels();
        deliverShortByTwo();
        long exId = onlyExceptionId();

        assertTrue(handle(exId, "{\"action\":\"APPROVE\",\"refundCashAmount\":30.00}").isSuccess(), "首次批准应成功");

        Api again = handle(exId, "{\"action\":\"APPROVE\",\"refundCashAmount\":30.00}");
        assertFalse(again.isSuccess(), "同一条异常不得被处理两次，实际=" + again);

        Api exec = post("/api/manager/exceptions/" + exId + "/execute", mgrToken(), null);
        assertFalse(exec.isSuccess(), "已执行(EXECUTED)的异常不得重复执行，实际=" + exec);
        assertTrue(exec.message() != null && exec.message().contains("不允许执行"),
                "拒绝原因应指向状态不允许执行，实际=" + exec.message());

        assertEquals(0, depositBalance().compareTo(new BigDecimal("90.00")), "押金只能补一次，不得翻倍");
        assertEquals(1, manualGrantRecords(), "补录流水也只能有一条");
    }

    @Test
    @DisplayName("站长 IGNORE：置已忽略，且一分钱都不动")
    void ignoreTouchesNoAccounts() {
        seed();
        firstOrderBuysTwoBarrels();
        deliverShortByTwo();
        long exId = onlyExceptionId();

        Api res = handle(exId, "{\"action\":\"IGNORE\",\"managerNote\":\"客户已电话说明，下月一起还\"}");
        assertTrue(res.isSuccess(), "忽略应成功，实际=" + res);

        assertEquals("IGNORED", statusOf(exId), "应置已忽略");
        assertEquals(0, depositBalance().compareTo(new BigDecimal("60.00")),
                "忽略不得动押金（仍是首单那 60）");
        assertEquals(0, manualGrantRecords(), "忽略不得产生补录流水");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=?", customer),
                "忽略不得产生任何水票流水");
        assertEquals(2, over(), "忽略不改变欠桶事实：客户依然欠 2 个桶");
    }

    @Test
    @DisplayName("跨租户：他站站长处置不了本站异常，账目与状态都不变")
    void otherStationManagerCannotHandle() {
        seed();
        firstOrderBuysTwoBarrels();
        deliverShortByTwo();
        long exId = onlyExceptionId();
        BigDecimal balanceBefore = depositBalance();

        long station2 = createStation("S2");
        long mgr2 = createStaff("M2", "STATION_MANAGER", station2, 1);
        String token2 = staffToken(mgr2, "STATION_MANAGER", station2);

        Api handled = post("/api/manager/exceptions/" + exId + "/handle", token2,
                "{\"action\":\"APPROVE\",\"refundCashAmount\":999.00}");
        assertFalse(handled.isSuccess(), "他站站长不得处置本站异常，实际=" + handled);
        Api exec = post("/api/manager/exceptions/" + exId + "/execute", token2, null);
        assertFalse(exec.isSuccess(), "他站站长不得执行本站补偿，实际=" + exec);
        assertFalse(get("/api/manager/exceptions/" + exId, token2).isSuccess(), "他站站长不得读取本站异常详情");

        assertEquals("STAFF_RECORDED", statusOf(exId), "状态不得被他人推进");
        assertEquals(0, depositBalance().compareTo(balanceBefore), "账目不得被他人改动");
    }

    @Test
    @DisplayName("退水票必须有商品：缺 adjustProductId 时拒绝并回滚，补全后水票到账")
    void refundTicketsRequiresProductThenCreditsAccount() {
        seed();
        firstOrderBuysTwoBarrels();
        deliverShortByTwo();
        long exId = onlyExceptionId();

        // 只给数量、不给商品 → 补偿做不到。旧实现只 log.warn 就照样标 EXECUTED（站长以为退了票），
        // 现在必须失败回滚，让站长补全重试。
        Api bad = handle(exId, "{\"action\":\"APPROVE\",\"refundTicketQty\":2}");
        assertFalse(bad.isSuccess(), "缺 adjustProductId 应被拒，实际=" + bad);
        assertTrue(bad.message() != null && bad.message().contains("adjustProductId"),
                "拒绝原因应指明缺商品，实际=" + bad.message());
        assertEquals("STAFF_RECORDED", statusOf(exId), "被拒后异常必须回滚到未处置，可重试");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_record WHERE customer_id=?", customer), "被拒不得留流水");

        Api ok = handle(exId, "{\"action\":\"APPROVE\",\"refundTicketQty\":2,\"adjustProductId\":" + product + "}");
        assertTrue(ok.isSuccess(), "补全商品后应成功，实际=" + ok);

        assertEquals("EXECUTED", statusOf(exId), "应置已执行");
        assertEquals(2, intOf("SELECT remain_quantity FROM ticket_account "
                        + "WHERE customer_id=? AND product_id=? AND station_id=?", customer, product, station),
                "水票账户应 +2（无账户时自动建账）");
        assertEquals(2, intOf("SELECT increase_qty FROM ticket_record WHERE customer_id=? AND product_id=?",
                customer, product), "水票流水应记增加 2");
        assertEquals(2, over(), "退水票不改变欠桶（欠的是桶，不是票）");
    }
}
