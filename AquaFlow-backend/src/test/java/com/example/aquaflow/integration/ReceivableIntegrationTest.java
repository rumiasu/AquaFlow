package com.example.aquaflow.integration;

import com.example.aquaflow.service.ReconciliationService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应收账款（2026-09-17，规格见 {@code docs/design/20} §1 的 A 层）。
 *
 * <p>盯四件事：</p>
 * <ol>
 *   <li><b>账期是快照</b>：下单时算一次写进 {@code orders.due_date}，事后改客户账期不影响历史单；</li>
 *   <li><b>只有挂账单才有应付日期</b>：现金单才有账期，微信/水票是即时结清；</li>
 *   <li><b>核销 ⟹ 已收款</b>，且核销是单向前进、可幂等重放；</li>
 *   <li><b>E10 对账能抓到"账销了、钱没到"</b> —— 这条不变量如果没人查，它等于不存在。</li>
 * </ol>
 */
@DisplayName("应收账款 · 账期快照 / 逾期 / 核销 / E10 不变量")
class ReceivableIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    private long orderIdOf(Api resp, String key) {
        assertEquals(0, resp.code(), "下单应成功: " + resp);
        return jdbc.queryForObject("SELECT id FROM orders WHERE idempotency_key=?", Long.class, key);
    }

    /** 现金(2) / 微信(1) 的下单体；账期规则只区分"是不是货到付款"。 */
    private Api order(String customerToken, long stationId, long addressId, long productId,
                      int qty, int payMethod, String key) {
        return post("/api/orders/create", customerToken,
                "{\"addressId\":" + addressId + ",\"stationId\":" + stationId
                        + ",\"paymentMethod\":" + payMethod + ",\"idempotencyKey\":\"" + key + "\","
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }

    /** 造一个"本站 + 已绑定客户 + 有商品库存"的最小自洽场景。 */
    private long[] scene(String name, String openid) {
        long station = createStation(name);
        long manager = createStaff(name + "长", "STATION_MANAGER", station, 1);
        long customer = createCustomer(name + "客户", openid);
        long address = createAddress(customer, name + "小区 1 号");
        long product = createProduct(name + "水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        createCustomerStationConfig(customer, station, 1);
        return new long[]{station, manager, customer, address, product};
    }

    @Test
    @DisplayName("账期快照：现金单才有应付日期，微信单没有；事后改账期不影响历史单")
    void dueDateIsSnapshottedAtCreation() {
        long[] s = scene("应收快照", "ar-snap");
        long station = s[0], manager = s[1], customer = s[2], address = s[3], product = s[4];
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        assertEquals(0, put("/api/manager/customers/" + customer + "/credit-terms",
                mgr, "{\"dueDays\":30}").code(), "站长应能设账期");

        // [v60] 算法变了：从"下单日 + N 天"改成"**当月最后一天** + N 天"（企业主流：本月消费、下月结账）。
        // 所以期望值不能写死 30 —— 按同一规则算出来，免得把某一天的巧合当成规格。
        int expect30 = (int) java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.now(),
                java.time.LocalDate.now()
                        .withDayOfMonth(java.time.LocalDate.now().lengthOfMonth()).plusDays(30));

        long cashOrder = orderIdOf(order(cus, station, address, product, 2, 2, "ar-snap-cash"), "ar-snap-cash");
        assertEquals(expect30, intOf("SELECT DATEDIFF(due_date, CURDATE()) FROM orders WHERE id=?", cashOrder),
                "现金单应带上账期快照（月底 + 30 天）");
        assertEquals(1, intOf("SELECT settlement_status FROM orders WHERE id=?", cashOrder),
                "新建单应为未结算(1)");

        // 微信是即时到账，不该产生应付日期 —— 否则应收台账里会混进一堆"其实已经收过钱"的单
        long wxOrder = orderIdOf(order(cus, station, address, product, 1, 1, "ar-snap-wx"), "ar-snap-wx");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE id=? AND due_date IS NOT NULL", wxOrder),
                "微信单是即时结清，不该有应付日期");

        // 账期是快照：改成 60 天，历史单的到期日必须一动不动（与地址/金额快照同源的理由）
        assertEquals(0, put("/api/manager/customers/" + customer + "/credit-terms",
                mgr, "{\"dueDays\":60}").code(), "改账期");
        assertEquals(expect30, intOf("SELECT DATEDIFF(due_date, CURDATE()) FROM orders WHERE id=?", cashOrder),
                "改账期不得改到历史单的到期日（要改存量得走 /credit-terms/recalculate）");
    }

    @Test
    @DisplayName("未设账期时不下发应付日期（存量水站行为不变）")
    void noCreditTermsMeansNoDueDate() {
        long[] s = scene("应收无账期", "ar-none");
        long station = s[0], customer = s[2], address = s[3], product = s[4];
        String cus = customerToken(customer);

        long id = orderIdOf(order(cus, station, address, product, 2, 2, "ar-none-1"), "ar-none-1");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE id=? AND due_date IS NOT NULL", id),
                "客户没设账期时现金单也不该有应付日期");
    }

    @Test
    @DisplayName("台账：待收款合计含该客户，逾期按应付日期判定并只提醒不改金额")
    void overviewShowsOutstandingAndOverdue() {
        long[] s = scene("应收台账", "ar-book");
        long station = s[0], manager = s[1], customer = s[2], address = s[3], product = s[4];
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);
        assertEquals(0, put("/api/manager/customers/" + customer + "/credit-terms",
                mgr, "{\"dueDays\":30}").code());

        long id = orderIdOf(order(cus, station, address, product, 2, 2, "ar-book-1"), "ar-book-1");
        // 断言一律拿"订单自己的金额"当基准，不硬编码计价规则（水费/押金/运费口径由别的用例锁定）
        java.math.BigDecimal amount = decimalOf("SELECT total_amount FROM orders WHERE id=?", id);
        assertTrue(amount.signum() > 0, "订单金额应大于 0，实际=" + amount);

        Api all = get("/api/manager/receivables", mgr);
        assertEquals(0, all.code(), "读台账");
        assertEquals(0, all.data().path("overdueAmount").decimalValue()
                .compareTo(java.math.BigDecimal.ZERO), "还没到期，逾期金额应为 0");
        assertEquals(1, all.data().path("customerCount").asInt(), "应有 1 个欠款客户");
        // 台账必须下发**客户级账期**：少了它，页面会把已设账期的客户一律显示成
        // 「未设账期（即时结清）」—— 不报错，但站长会按错的方式报价（静默误导）
        assertEquals(30, all.data().path("customers").get(0).path("dueDays").asInt(),
                "台账每行都应带上客户账期: " + all);
        assertEquals(0, all.data().path("outstandingAmount").decimalValue().compareTo(amount),
                "待收款合计应等于该订单金额: " + all);
        assertEquals(0, all.data().path("customers").get(0).path("outstandingAmount").decimalValue()
                .compareTo(amount), "客户级待收款应等于该订单金额: " + all);

        // 把到期日改成 5 天前 → 应判为逾期，且**不改任何金额**
        jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL 5 DAY) WHERE id=?", id);
        Api overdue = get("/api/manager/receivables", mgr);
        assertEquals(0, overdue.data().path("overdueAmount").decimalValue().compareTo(amount),
                "逾期金额应等于该订单金额: " + overdue);
        assertEquals(1, overdue.data().path("overdueCustomerCount").asInt());
        assertEquals(0, overdue.data().path("customers").get(0).path("overdueAmount").decimalValue()
                .compareTo(amount), "客户级逾期金额");
        assertEquals(5, overdue.data().path("customers").get(0).path("maxOverdueDays").asInt(),
                "最长逾期天数应为 5");
        // 逾期只提醒：金额一分不动
        assertEquals(0, decimalOf("SELECT total_amount FROM orders WHERE id=?", id).compareTo(amount),
                "逾期不得改动订单金额");

        // 只看逾期的明细过滤
        assertEquals(1, get("/api/manager/receivables/orders?onlyOverdue=true", mgr).data().size(),
                "逾期明细应有 1 单");
        assertEquals(1, get("/api/manager/receivables/orders?customerId=" + customer, mgr).data().size());
        assertEquals(0, get("/api/manager/receivables/orders?customerId=99999999", mgr).data().size(),
                "查别的客户应查不到本站的单");
    }

    @Test
    @DisplayName("核销：收款 + 核销一次完成；重复核销幂等；单向前进不倒退")
    void settleCollectsAndWritesOff() {
        long[] s = scene("应收核销", "ar-settle");
        long station = s[0], manager = s[1], customer = s[2], address = s[3], product = s[4];
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);
        assertEquals(0, put("/api/manager/customers/" + customer + "/credit-terms",
                mgr, "{\"dueDays\":30}").code());

        long id = orderIdOf(order(cus, station, address, product, 2, 2, "ar-settle-1"), "ar-settle-1");
        java.math.BigDecimal amount = decimalOf("SELECT total_amount FROM orders WHERE id=?", id);
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", id), "现金单下单即待收款(1)");
        assertEquals(1, intOf("SELECT settlement_status FROM orders WHERE id=?", id));

        String body = "{\"customerId\":" + customer + ",\"orderIds\":[" + id + "]}";
        Api settle = post("/api/manager/receivables/settle", mgr, body);
        assertEquals(0, settle.code(), "核销应成功: " + settle);
        assertEquals(1, settle.data().path("settledCount").asInt());
        assertEquals(1, settle.data().path("collectedCount").asInt(), "本次应同时完成收款");
        assertEquals(0, settle.data().path("settledAmount").decimalValue().compareTo(amount),
                "核销金额应等于该订单金额: " + settle);

        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", id), "钱应记为已付(2)");
        assertEquals(2, intOf("SELECT settlement_status FROM orders WHERE id=?", id), "应记为已结算(2)");

        // ⚠️ 核销必须**补写 PAID 支付流水**，不能只把 payment_status 改成 2。
        // 对账等式2 把「已付却查不到 PAID 流水」判为不平，只改状态的话站长每核销一单
        // 日结就报一次假警报，真问题会被淹没。本用例最初漏了这条断言，于是漏掉了这个缺陷。
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", id),
                "核销收款必须留下 PAID 支付流水（等式2 的凭证）");
        assertEquals(0, reconciliationKey("paymentStatus"),
                "核销后对账等式2 必须为 0（已付但无凭证 = 不平）");

        // 幂等重放：已核销的单跳过而不是报错（站长重复点一次不该看到红字），也不得重复计数
        Api again = post("/api/manager/receivables/settle", mgr, body);
        assertEquals(0, again.code(), "重复核销应幂等: " + again);
        assertEquals(0, again.data().path("settledCount").asInt(), "重复核销不得重复计数");
        assertEquals(1, again.data().path("alreadySettledCount").asInt());

        // 核销后该单不再出现在台账里（台账口径 = payment_status 待收款）
        assertEquals(0, get("/api/manager/receivables/orders?customerId=" + customer, mgr).data().size(),
                "已核销的单应退出待收款台账");
    }

    @Test
    @DisplayName("护栏：取消单不得核销、他站订单不得核销；E10 能抓到账销了钱没到")
    void settleGuardsAndE10Invariant() {
        long[] a = scene("应收护栏A", "ar-guard-a");
        long[] b = scene("应收护栏B", "ar-guard-b");
        long stationA = a[0], managerA = a[1], customerA = a[2], addressA = a[3], productA = a[4];
        long stationB = b[0], customerB = b[2], addressB = b[3], productB = b[4];
        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        String cusB = customerToken(customerB);

        // 他站订单：A 站站长不得核销 B 站的单（站别由 SQL 强制，不接受调用方传的归属）
        long orderB = orderIdOf(order(cusB, stationB, addressB, productB, 2, 2, "ar-guard-b1"), "ar-guard-b1");
        assertNotEquals(0, post("/api/manager/receivables/settle", mgrA,
                "{\"customerId\":" + customerB + ",\"orderIds\":[" + orderB + "]}").code(),
                "他站订单不得核销");
        assertEquals(1, intOf("SELECT settlement_status FROM orders WHERE id=?", orderB),
                "被拒的核销不得改动对方订单");

        // 已取消订单：钱已原路退回，核销它等于凭空抹掉一笔应收
        String cusA = customerToken(customerA);
        long orderA = orderIdOf(order(cusA, stationA, addressA, productA, 1, 2, "ar-guard-a1"), "ar-guard-a1");
        jdbc.update("UPDATE orders SET status = 5 WHERE id=?", orderA);
        assertNotEquals(0, post("/api/manager/receivables/settle", mgrA,
                "{\"customerId\":" + customerA + ",\"orderIds\":[" + orderA + "]}").code(),
                "已取消订单不得核销");
        assertEquals(1, intOf("SELECT settlement_status FROM orders WHERE id=?", orderA),
                "被拒后不得留下已核销状态");

        // 空入参
        assertNotEquals(0, post("/api/manager/receivables/settle", mgrA,
                "{\"customerId\":" + customerA + ",\"orderIds\":[]}").code(), "空订单列表应被拒");

        // 他站客户不得设账期、不得读账期
        assertNotEquals(0, put("/api/manager/customers/" + customerB + "/credit-terms",
                mgrA, "{\"dueDays\":30}").code(), "他站客户不得设账期");
        assertNotEquals(0, get("/api/manager/customers/" + customerB + "/credit-terms", mgrA).code(),
                "他站客户不得读账期");

        // ---- E10：核销必须蕴含已收款 ----
        // 正常路径（走接口核销）之后，E10 必须为 0
        long okOrder = orderIdOf(order(cusA, stationA, addressA, productA, 1, 2, "ar-guard-a2"), "ar-guard-a2");
        assertEquals(0, put("/api/manager/customers/" + customerA + "/credit-terms",
                mgrA, "{\"dueDays\":15}").code());
        assertEquals(0, post("/api/manager/receivables/settle", mgrA,
                "{\"customerId\":" + customerA + ",\"orderIds\":[" + okOrder + "]}").code());
        assertEquals(2, intOf("SELECT settlement_status FROM orders WHERE id=?", okOrder));
        assertEquals(0, e10(), "走正常核销后不应有 E10 差异");

        // 直接改库造出"账销了、钱没到" —— 对账必须抓到（抓不到的不变量等于不存在）
        jdbc.update("UPDATE orders SET payment_status = 1 WHERE id=?", okOrder);
        assertEquals(1, e10(), "账销了钱没到必须被 E10 抓到");

        // 反向（收了钱还没核销）是**正常的**：现金单送货上门当场收钱，站长之后才走月结。
        // 谁把 E10 改成双向比较，日结就会天天报不平。
        jdbc.update("UPDATE orders SET payment_status = 2, settlement_status = 1 WHERE id=?", okOrder);
        assertEquals(0, e10(), "收了钱但还没核销不得报不平（这是正常经营状态）");
    }

    private int e10() {
        Map<String, Integer> v2 = reconciliationService.runReconcileV2();
        Integer v = v2.get("E10_settledButUnpaid");
        assertTrue(v != null, "V2 应含 E10_settledButUnpaid，实际=" + v2.keySet());
        return v;
    }

    /**
     * 取 V1（押金/支付/桶/库存）里某一项的差异数。
     *
     * <p>本用例的造数只求"够用"（不塞库存流水），所以**只断言与核销有关的那一项**，
     * 不对 V1 全表做全零断言 —— 那测的是造数自洽性，不是产品。</p>
     */
    private int reconciliationKey(String key) {
        Map<String, Integer> v1 = reconciliationService.runReconcile();
        Integer v = v1.get(key);
        assertTrue(v != null, "V1 应含 " + key + "，实际=" + v1.keySet());
        return v;
    }
}
