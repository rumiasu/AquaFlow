package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 货到付款的**客户级约束**（v48，2026-09-18 产品裁定）。
 *
 * <p>产品原话：「如果做也要对<b>首单和大额</b>订单设限（特殊允许的客户可以大额）」，
 * 并且这些约束「最好是给站长定，在设置是否允许货到付款时<b>就给弹出来</b>」。</p>
 *
 * <p>四层判据（顺序即优先级，唯一实现在 {@code PaymentServiceImpl.offlinePaymentBlockReason}）：
 * 开关 → <b>欠款即停</b>（有逾期未结的现金单）→ <b>首单不给</b>（默认，站长可放开）→
 * <b>单笔上限</b>（NULL = 不限 = "特殊客户可大额"）。本类逐层钉住，并断言<b>报价与下单同一判据</b>
 * —— 分叉就会出现"报价页能选货到付款、提交却被拒"。</p>
 */
@DisplayName("货到付款约束 · 首单不给 / 欠款即停 / 单笔上限（特殊客户可放宽）")
class OfflinePaymentConstraintIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long mgr;
    private long customer;
    private long address;
    private long goods;
    private String cus;
    private String mgrToken;

    private void seed() {
        station = createStation("赊账站");
        mgr = createStaff("赊账站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("赊账客户", "cod-openid");
        address = createAddress(customer, "赊账小区1号");
        // 非桶装水：不触发押金/桶权益那条规则，把本类要盯的货到付款约束解耦
        goods = createProduct("赊账饮水机", 2, "20.00", "0.00", 0, "0.00");
        createInventoryFull(station, goods, 100, 0, "0.00");
        // 客户是本站建档的（绑定行存在、货到付款默认关闭）—— 站长"开通货到付款"的前提就是这个绑定；
        // 没有绑定的客户连开通接口都不给碰（并集判据，2026-09-18 修过的那条越权闸门）
        createCustomerStationConfig(customer, station, 0);
        cus = customerToken(customer);
        mgrToken = staffToken(mgr, "STATION_MANAGER", station);
    }

    /** 站长开通货到付款（enabled=1），并给定上限与首单策略；limit 传 null = 不限。 */
    private void enableCod(String limit, int allowFirstOrder) {
        String limitJson = limit == null ? "null" : limit;
        assertEquals(0, put("/api/customers/" + customer + "/offline-payment", mgrToken,
                "{\"offlinePaymentEnabled\":1,\"singleLimit\":" + limitJson
                        + ",\"allowFirstOrder\":" + allowFirstOrder + "}").code(), "开通货到付款应成功");
    }

    private Api placeCash(String key) {
        return post("/api/orders/create", cus, "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"" + key + "\","
                + "\"items\":[{\"productId\":" + goods + ",\"quantity\":1}]}");
    }

    @Test
    @DisplayName("首单默认不给货到付款；站长放开后同一个客户就能下（这就是「弹窗里配」的那一项）")
    void firstOrderBlockedByDefaultAndCanBeAllowed() {
        seed();
        enableCod(null, 0);
        Api first = placeCash("cod-first");
        assertNotEquals(0, first.code(), "首单默认不给货到付款");
        assertTrue(first.message() != null && first.message().contains("首单"), "文案要说明是首单，实际=" + first.message());

        enableCod(null, 1);
        assertEquals(0, placeCash("cod-first-allowed").code(), "站长放开首单后应可下单");

        // 放开之后就有了历史订单，再来的单不再是首单
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE customer_id=? AND station_id=?", customer, station),
                "只应落下第二张单");
    }

    @Test
    @DisplayName("单笔上限：超限被拒；改成不限（null）后同一张单能下 —— 这就是「特殊客户可大额」")
    void singleLimitBlocksAndCanBeRelaxed() {
        seed();
        enableCod("10.00", 1); // 单笔上限 10 元，而本单 20 元
        Api tooBig = placeCash("cod-limit");
        assertNotEquals(0, tooBig.code(), "超过单笔上限应被拒");
        assertTrue(tooBig.message() != null && tooBig.message().contains("单笔上限"),
                "文案要指向单笔上限，实际=" + tooBig.message());

        enableCod(null, 1); // 改成不限
        assertEquals(0, placeCash("cod-limit-relaxed").code(), "把上限改成不限后应可下单");
    }

    @Test
    @DisplayName("欠款即停：有逾期未结的现金单就不给新的赊账单；结清后自动恢复")
    void overdueArrearsBlockNewCreditOrders() {
        seed();
        enableCod(null, 1);
        assertEquals(0, placeCash("cod-old").code(), "先有一张正常的现金单");
        long old = longOf("SELECT id FROM orders WHERE idempotency_key=?", "cod-old");
        // 让它逾期：账期快照挪到昨天，钱还没收（payment_status = 1 待收款）
        jdbc.update("UPDATE orders SET due_date = date_sub(curdate(), interval 1 day) WHERE id = ?", old);
        jdbc.update("UPDATE orders SET payment_status = 1 WHERE id = ?", old);

        Api blocked = placeCash("cod-overdue");
        assertNotEquals(0, blocked.code(), "有逾期未结货款时不该再给赊账");
        assertTrue(blocked.message() != null && blocked.message().contains("逾期"),
                "文案要说明逾期未结，实际=" + blocked.message());

        // 结清（核销/收款后 payment_status = 2）→ 自动恢复
        jdbc.update("UPDATE orders SET payment_status = 2 WHERE id = ?", old);
        assertEquals(0, placeCash("cod-after-settle").code(), "结清后应恢复");
    }

    @Test
    @DisplayName("报价与下单同一判据：被拦时报价里货到付款选项收回并下发原因；开通后放行")
    void quoteUsesSameJudgement() {
        seed();
        // 未开通：报价里没有货到付款，且给出原因（此前这里只给 true/false，前端只能瞎猜）
        Api quote0 = post("/api/payments/quote", cus, "{\"stationId\":" + station + ",\"paymentMethod\":2,"
                + "\"addressId\":" + address
                + ",\"items\":[{\"productId\":" + goods + ",\"quantity\":1}]}");
        assertEquals(0, quote0.code(), "报价应成功: " + quote0);
        JsonNode q0 = quote0.data();
        assertFalse(q0.path("allowOfflinePayment").asBoolean(), "未开通时报价不该放出货到付款");
        assertNotNull(q0.path("offlinePaymentBlockReason").asText(null), "要给原因，而不是只给 false");
        assertTrue(q0.path("offlinePaymentBlockReason").asText("").contains("不支持"),
                "原因应指向「未开通」，实际=" + q0.path("offlinePaymentBlockReason").asText(""));

        // 开通 + 放行首单：报价放行
        enableCod(null, 1);
        Api quote1 = post("/api/payments/quote", cus, "{\"stationId\":" + station + ",\"paymentMethod\":2,"
                + "\"addressId\":" + address
                + ",\"items\":[{\"productId\":" + goods + ",\"quantity\":1}]}");
        assertEquals(0, quote1.code(), "报价应成功: " + quote1);
        JsonNode q1 = quote1.data();
        assertTrue(q1.path("allowOfflinePayment").asBoolean(), "开通并放行首单后报价应放出货到付款");
        assertTrue(q1.path("offlinePaymentBlockReason").isNull(), "可用时不下发原因");

        // 开通弹窗的依据：历史订单数 / 欠款 / 可用性一次给全
        JsonNode summary = get("/api/customers/" + customer + "/offline-payment/summary", mgrToken).data();
        assertEquals(1, summary.path("allowFirstOrder").asInt(), "弹窗要能看到「首单是否放行」的当前值");
        assertEquals(0, summary.path("overdueCount").asInt(), "此时没有逾期");
        assertEquals(0, summary.path("orderCount").asInt(), "此时还没有订单（= 首单）");
        assertTrue(summary.path("usable").asBoolean(), "已开通且放行首单 → 可用");
    }
}
