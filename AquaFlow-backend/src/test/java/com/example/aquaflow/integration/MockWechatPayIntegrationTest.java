package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信支付<b>模拟渠道</b>（{@code app.payment.mock-wechat-pay=true}）。
 *
 * <p>产品口径（2026-09-20）：「<b>点击即成功，只取代真实支付这一下，别的都按真实标准</b>」。
 * 所以本类<b>不</b>验证"能不能付成功"——那是开关的字面含义；它盯的是
 * <b>被取代的到底只有那一下</b>：</p>
 * <ol>
 *   <li><b>渠道可见</b>：{@code POST /api/payments/quote} 下发的 methods 里微信必须
 *       {@code enabled=true}，且文案写明是<b>模拟</b>（避免联调的人以为真接了支付）；</li>
 *   <li><b>副作用照跑</b>：置为已付款之后，订单必须<b>自动进入站长/配送员视野</b> ——
 *       这条判据是查询时按 {@code payment_status=2} 现算的，能过说明
 *       {@code markPaidIfCollectable} 走了同一条真实路径，而不是只改了一张流水表；</li>
 *   <li><b>押金照记</b>：押金入账（{@code applyDepositOnPaid}）在真实微信链路上挂在
 *       "支付已成功"之后，模拟渠道必须一样落 {@code deposit_record} ——
 *       漏掉就是 AGENTS §8.4 那条老坑（票付了、押金账户是 0，退桶退不出钱）；</li>
 *   <li><b>退款联动</b>：模拟收到的钱要能模拟退回去，且备注写明"模拟"，
 *       否则测试期的资金记录会被后人当成真实凭据；</li>
 *   <li><b>买水票不用等水站</b>（2026-09-26 产品口径「水票购买不需要水站同意，直接微信收款就行」）：
 *       在线购票走的是无订单支付，原先只会落一条待收款流水，于是模拟渠道下这笔钱"已经收到"
 *       却要站长去「待确认收款」点一下 —— 界面看起来正是"买票要水站审批"。现在当场确认并入账。</li>
 * </ol>
 *
 * <p><b>与既有用例的分工</b>：{@code PaidBeforeDispatchIntegrationTest.unpaidWechatOrderIsInvisibleUntilPaid}
 * 锁的是<b>开关关闭</b>时（生产默认）的老行为：微信单停在待收款、要靠站长手工确认。
 * 那个用例<b>没有</b>加本类的 {@code @TestPropertySource}，跑的是默认值 false ——
 * 两者互为对照，正是"开关只影响该影响的东西"的证据。</p>
 */
@TestPropertySource(properties = "app.payment.mock-wechat-pay=true")
class MockWechatPayIntegrationTest extends AbstractIntegrationTest {

    /** 建站 + 站长 + 客户 + 商品 + 库存 + 地址，返回 {station, manager, customer, product, address}。 */
    private long[] seed() {
        long station = createStation("模拟微信站");
        long manager = createStaff("模拟微信站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("模拟微信客户", "mockwechat-openid");
        long product = createProduct("模拟微信水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 100);
        long address = createAddress(customer, "模拟微信地址");
        return new long[]{station, manager, customer, product, address};
    }

    /** 一张微信单：待收款(1) + 微信(1) + 水费 10 押金 30 合计 40。 */
    private long wechatOrder(long[] s) {
        return createOrderFull(s[2], s[4], s[0], s[3], 1, 1, 1 /* 微信 */,
                "10.00", "30.00", "40.00", true, 1);
    }

    /**
     * 该订单是否出现在某个列表端点返回的数组里。
     * <p>必须按 JSON 结构比对：早先版本用 {@code body().toString().contains("\"id\":" + orderId)}，
     * 订单 1 会被 id=12 的记录误命中 —— 这种"假绿"比红更贵。</p>
     */
    private boolean inList(String path, String token, long orderId) {
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

    /** 取一列字符串（基类只提供 intOf/longOf/decimalOf）。查不到返回 null。 */
    private String strOrNull(String sql, Object... args) {
        java.util.List<String> rows = jdbc.queryForList(sql, String.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    @DisplayName("报价：微信项变为可选，且文案明确标注是模拟渠道")
    void quoteExposesWechatAsMockChannel() {
        long[] s = seed();
        Api res = post("/api/payments/quote", customerToken(s[2]),
                "{\"stationId\":" + s[0] + ",\"paymentMethod\":1,"
                        + "\"items\":[{\"productId\":" + s[3] + ",\"quantity\":1}]}");
        assertEquals(0, res.code(), "报价应成功: " + res);

        JsonNode methods = res.data().get("methods");
        JsonNode wechat = null;
        for (JsonNode m : methods) {
            if (m.get("id").asInt() == 1) {
                wechat = m;
            }
        }
        assertTrue(wechat != null, "methods 里必须有微信这一项（前端不自带映射表）");
        assertTrue(wechat.get("enabled").asBoolean(),
                "模拟渠道开启时微信必须可选，否则顾客根本点不到: " + wechat);
        assertTrue(wechat.get("desc").asText().contains("模拟"),
                "文案必须写明是模拟，否则联调的人会以为真接了微信支付: " + wechat.get("desc").asText());

        // [2026-09-26 返工契约 P0-b] 报价还要把**收款渠道能力**单独下发一份（wechatPay）：
        // 客户端的判据是"建单成功后要不要对同一张单发起 createPayment"，这件事只有服务端知道
        // （唯一实现在 PayMethod.payChannel）。前端若按 id===1 自己猜，生产上开关是关的，
        // 客户建完单就会对着一个必然失败的付款请求；反过来只建单不付款，订单会停在待收款、
        // 站长端看不见 —— 那正是这次要修的缺陷。
        JsonNode capability = res.data().get("wechatPay");
        assertTrue(capability != null && !capability.isNull(),
                "报价必须下发 wechatPay 渠道能力：前端据此决定建单后发不发付款请求: " + res.data());
        assertEquals(1, capability.path("method").asInt(), "渠道能力要标明是哪种支付方式: " + capability);
        assertTrue(capability.path("enabled").asBoolean(),
                "模拟渠道开着 ⇒ enabled 必须为 true，否则前端不会发起同单付款: " + capability);
        assertTrue(capability.path("simulated").asBoolean(),
                "simulated 必须为 true：这笔钱没有真实渠道，界面必须照实说: " + capability);
    }

    @Test
    @DisplayName("点一下就成功：流水置已付款、订单置已付，且订单自动进入站长待分配")
    void wechatPaymentSucceedsAndOrderBecomesVisible() {
        long[] s = seed();
        long order = wechatOrder(s);
        String mgr = staffToken(s[1], "STATION_MANAGER", s[0]);

        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order), "下单时还没付钱");
        assertTrue(!inList("/api/delivery/orders/station-pending", mgr, order),
                "没收到钱的单不该出现在站长待分配里（与开关关闭时同一判据）");

        Api created = post("/api/payments", customerToken(s[2]),
                "{\"orderId\":" + order + ",\"paymentMethod\":1}");
        assertEquals(0, created.code(), "模拟微信支付应成功: " + created);

        assertEquals(2, intOf("SELECT status FROM payment_record WHERE order_id=?", order),
                "支付流水必须落在 已付款(2)，而不是待收款(1)");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "订单必须置为已付款 —— 这条只能由 markPaidIfCollectable 写入，"
                        + "能过说明模拟渠道走的是真实那条路");

        // 判据是查询时现算的（payment_status=2），所以这条同时证明了"钱到了就进视野"没被绕过
        assertTrue(inList("/api/delivery/orders/station-pending", mgr, order),
                "已付款后必须自动出现在站长待分配里");
    }

    @Test
    @DisplayName("买水票不用等水站：模拟渠道下微信购票当场到账，站长「待确认收款」里不留东西")
    void wechatTicketPurchaseCreditsWithoutStationAction() {
        long[] s = seed();
        String mgr = staffToken(s[1], "STATION_MANAGER", s[0]);
        // seed() 那款水没开水票；购票判据在 TicketTierService.usesCustomTicket（站级库存的水票开关 + 水票价）
        long product = createProduct("模拟微信水票", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(s[0], product, 100, 1, "8.00");

        Api res = post("/api/tickets/purchase", customerToken(s[2]),
                "{\"productId\":" + product + ",\"quantity\":3,\"paymentMethod\":1,"
                        + "\"stationId\":" + s[0] + ",\"idempotencyKey\":\"mock-ticket-buy-1\"}");

        assertEquals(0, res.code(), "购票应成功: " + res);
        long paymentId = res.data().path("paymentId").asLong();
        // [2026-09-26 产品口径]「水票购买不需要水站同意，直接微信收款就行」——
        // 所以这里回给客户端的必须已经是已付款(2)，而不是"等站长点确认"的待收款(1)。
        assertEquals(2, res.data().path("status").asInt(),
                "模拟渠道下买票必须当场是已付款(2)，否则界面只能提示「等待到账」，看着就像要水站审批");
        assertEquals(2, intOf("SELECT status FROM payment_record WHERE id=?", paymentId),
                "流水必须落在已付款(2)");
        assertEquals(3, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                        + "AND product_id=?", s[2], s[0], product),
                "水票必须当场到账 3 张（模拟渠道跳过的是「真实付款」这一下，不是入账那一步）");
        // 入账必须过批次账（E8）：账户余额 == Σ 批次余量，且批次单价 = 实付均价（8.00 × 3 = 24.00）
        assertEquals(8.00, decimalOf("SELECT unit_price FROM ticket_lot WHERE customer_id=? AND station_id=? "
                        + "AND product_id=?", s[2], s[0], product).doubleValue(), 0.001,
                "批次单价必须是实付均价，否则退票时会按错价退钱");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_account a WHERE a.customer_id=? AND a.station_id=? "
                        + "AND a.product_id=? AND a.right_amount <> (SELECT coalesce(sum(l.remain_qty * l.unit_price), 0) "
                        + "FROM ticket_lot l WHERE l.customer_id=a.customer_id AND l.station_id=a.station_id "
                        + "AND l.product_id=a.product_id)", s[2], s[0], product), 0,
                "对账等式 E8：余额价值必须等于 Σ 批次余量 × 批次单价");

        // 站长侧没有任何待办 —— 这正是"不需要水站同意"的落地形态
        Api pending = get("/api/payments/pending", mgr);
        assertEquals(0, pending.code(), "待确认收款列表应可读: " + pending);
        for (JsonNode node : pending.data()) {
            assertTrue(node.path("id").asLong() != paymentId,
                    "已到账的购票流水不该出现在站长「待确认收款」里: " + node);
        }
        // 想"补确认"也不行：已付款的流水不得再确认一次（否则就是重复入账）
        assertNotEquals(0, put("/api/payments/" + paymentId + "/confirm", mgr, null).code(),
                "已付款的流水不得再确认一次");
        assertEquals(3, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                        + "AND product_id=?", s[2], s[0], product), "重复确认后水票不得再涨");
    }

    @Test
    @DisplayName("押金照记：模拟付款同样落 deposit_record（漏了就是退桶退不出钱的老坑）")
    void mockPaymentStillCreditsDeposit() {
        long[] s = seed();
        long order = wechatOrder(s);

        assertEquals(0, post("/api/payments", customerToken(s[2]),
                "{\"orderId\":" + order + ",\"paymentMethod\":1}").code());

        int vouchers = intOf("SELECT COUNT(*) FROM deposit_record WHERE related_order_id=? AND amount > 0", order);
        assertTrue(vouchers > 0,
                "押金 ¥30 必须随模拟付款入账（真实链路上它挂在\"支付成功\"之后，模拟不能少这一步）");
    }

    @Test
    @DisplayName("幂等不变：同一订单再发一次微信支付是「返回原流水」而不是新建（一单一条活跃流水）")
    void mockChannelDoesNotWeakenIdempotency() {
        long[] s = seed();
        long order = wechatOrder(s);
        String cus = customerToken(s[2]);

        assertEquals(0, post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":1}").code());
        long firstId = longOf("SELECT id FROM payment_record WHERE order_id=?", order);

        // ⚠️ 期望是 code=0 而**不是**拒绝：createPayment 开头那段的契约就是
        // 「该订单已有『已支付』或『待收款』记录时直接返回，不再新建」（[DEF-3]，
        // uk_payment_order_status 与"退款另立负金额流水"冲突后已删，防重责任回到应用层）。
        // 我第一版把它写成"必须拒绝"，红了一次 —— 是期望错，不是实现错，故此处按真实契约断言。
        // 真正要守的是**没有第二条流水**：那才是"一单一条活跃流水"的落地形态。
        assertEquals(0, post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":1}").code(),
                "重复发起应幂等返回原记录");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=?", order),
                "重复发起不得留下第二条流水 —— 模拟渠道不放宽这条约束");
        assertEquals(firstId, longOf("SELECT id FROM payment_record WHERE order_id=?", order),
                "返回的必须还是原来那一条");
        assertEquals(2, intOf("SELECT status FROM payment_record WHERE id=?", firstId), "且它仍是已付款");
    }

    @Test
    @DisplayName("退款联动：模拟收到的钱能模拟退回，且备注写明是模拟")
    void refundOfMockWechatPaymentSucceedsAndIsLabelledMock() {
        long[] s = seed();
        long order = wechatOrder(s);
        String mgr = staffToken(s[1], "STATION_MANAGER", s[0]);
        String cus = customerToken(s[2]);

        assertEquals(0, post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":1}").code());
        long paymentId = longOf("SELECT id FROM payment_record WHERE order_id=? AND status=2", order);

        Api refund = put("/api/payments/" + paymentId + "/refund", mgr,
                "{\"note\":\"模拟渠道退款用例\"}");
        assertEquals(0, refund.code(), "模拟渠道下微信退款应放行: " + refund);
        assertEquals(3, intOf("SELECT status FROM payment_record WHERE id=?", paymentId), "原流水应转已退款");

        // 负金额冲正流水由共用方法生成，形状与取消链一致（这正是"别的都按真实标准"的体现）。
        // ⚠️ 按 order_id 找那一条 amount<0 的**新**流水 —— 它不是原流水（原流水已被置已退款），
        // 拿 paymentId 去查 note 会得到 null（第一版就是这么红的）。
        String note = strOrNull("SELECT note FROM payment_record WHERE order_id=? AND amount < 0", order);
        assertTrue(note != null && note.contains("模拟"),
                "退款凭据必须写明是模拟渠道，否则测试数据会被后人当成真实资金记录: " + note);
    }
}
