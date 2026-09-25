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
 * 货到付款的**准入判据**（v48 立、v49 收敛为两层）。
 *
 * <p>2026-09-18 第四批产品裁定：「既然目前还由站长审核，首单是否放行（默认不给）、
 * 单笔上限（默认留空＝不限）这两个先不做了。」于是判据只剩两层：</p>
 * <ol>
 *   <li><b>开关</b>：该客户在该站是否被站长开通（货到付款没有站点级总闸，站长逐个客户审核开通
 *       —— 这本身就是第一道闸）；</li>
 *   <li><b>欠款即停</b>：该客户在本站有**逾期未结的现金单**就不给新的赊账单
 *       （判据只用现有列现算：待收款 + 未取消 + 现金 + 应付日期已过）。</li>
 * </ol>
 *
 * <p>本类同时钉住"报价与下单同一判据"：两处一旦分叉，就会出现"报价页能选货到付款、提交却被拒"。</p>
 */
@DisplayName("货到付款准入 · 开关 + 欠款即停（v49 已撤回首单/上限两项）")
class OfflinePaymentGuardIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long customer;
    private long address;
    private long goods;
    private String cus;
    private String mgrToken;

    private void seed() {
        station = createStation("赊账站");
        long mgr = createStaff("赊账站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("赊账客户", "cod-openid");
        address = createAddress(customer, "赊账小区1号");
        // 非桶装水：不触发押金/桶权益那条规则，把本类要盯的货到付款准入解耦
        goods = createProduct("赊账饮水机", 2, "20.00", "0.00", 0, "0.00");
        createInventoryFull(station, goods, 100, 0, "0.00");
        // 客户是本站建档的（绑定行存在、货到付款默认关闭）：站长"开通货到付款"的前提就是这个绑定
        createCustomerStationConfig(customer, station, 0);
        cus = customerToken(customer);
        mgrToken = staffToken(mgr, "STATION_MANAGER", station);
    }

    private void setCod(boolean enabled) {
        assertEquals(0, put("/api/customers/" + customer + "/offline-payment", mgrToken,
                "{\"offlinePaymentEnabled\":" + (enabled ? 1 : 0) + "}").code(), "设置货到付款开关应成功");
    }

    private Api placeCash(String key) {
        return post("/api/orders/create", cus, "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"" + key + "\","
                + "\"items\":[{\"productId\":" + goods + ",\"quantity\":1}]}");
    }

    private JsonNode quote() {
        Api res = post("/api/payments/quote", cus, "{\"stationId\":" + station + ",\"paymentMethod\":2,"
                + "\"addressId\":" + address
                + ",\"items\":[{\"productId\":" + goods + ",\"quantity\":1}]}");
        assertEquals(0, res.code(), "报价应成功: " + res);
        return res.data();
    }

    @Test
    @DisplayName("未开通不能下现金单；开通后立刻能下（不再有首单/上限这两层）")
    void switchIsTheOnlyGate() {
        seed();
        Api blocked = placeCash("cod-off");
        assertNotEquals(0, blocked.code(), "未开通货到付款时不能下现金单");
        assertTrue(blocked.message() != null && blocked.message().contains("货到付款"),
                "文案要指向货到付款，实际=" + blocked.message());

        setCod(true);
        assertEquals(0, placeCash("cod-on").code(),
                "开通即可下单 —— v49 撤回首单/上限后，站长开一次就该能用");
        assertEquals(0, placeCash("cod-on-2").code(), "再来一单同样可用（没有额度层）");
    }

    @Test
    @DisplayName("欠款即停：有逾期未结的现金单就不给新的赊账单；结清后自动恢复")
    void overdueArrearsBlockNewCreditOrders() {
        seed();
        setCod(true);
        assertEquals(0, placeCash("cod-old").code(), "先有一张正常的现金单");
        long old = longOf("SELECT id FROM orders WHERE idempotency_key=?", "cod-old");
        // 让它逾期：账期快照挪到昨天，钱还没收（待收款）
        jdbc.update("UPDATE orders SET due_date = date_sub(curdate(), interval 1 day), payment_status = 1 "
                + "WHERE id = ?", old);

        Api blocked = placeCash("cod-overdue");
        assertNotEquals(0, blocked.code(), "有逾期未结货款时不该再给赊账");
        assertTrue(blocked.message() != null && blocked.message().contains("逾期"),
                "文案要说明逾期未结，实际=" + blocked.message());

        // 结清后恢复
        jdbc.update("UPDATE orders SET payment_status = 2 WHERE id = ?", old);
        assertEquals(0, placeCash("cod-after-settle").code(), "结清后应恢复");
    }

    @Test
    @DisplayName("报价与下单同一判据；开通弹窗的依据（欠款/订单数/可用性）一次给全")
    void quoteSharesTheSameJudgementAndSummaryExposesArrears() {
        seed();
        // 未开通：报价里没有货到付款，并给出原因（不是只给 false 让前端瞎猜）
        JsonNode q0 = quote();
        assertFalse(q0.path("allowOfflinePayment").asBoolean(), "未开通时报价不该放出货到付款");
        assertNotNull(q0.path("offlinePaymentBlockReason").asText(null), "要给原因，而不是只给 false");
        assertTrue(q0.path("offlinePaymentBlockReason").asText("").contains("不支持"),
                "原因应指向「未开通」，实际=" + q0.path("offlinePaymentBlockReason").asText(""));

        setCod(true);
        JsonNode q1 = quote();
        assertTrue(q1.path("allowOfflinePayment").asBoolean(), "开通后报价应放出货到付款");
        assertTrue(q1.path("offlinePaymentBlockReason").isNull(), "可用时不下发原因");

        // 开通弹窗的依据：欠款/逾期/历史订单数/能否使用
        JsonNode summary = get("/api/customers/" + customer + "/offline-payment/summary", mgrToken).data();
        assertEquals(1, summary.path("offlinePaymentEnabled").asInt(), "弹窗要能看到当前开关值");
        assertEquals(0, summary.path("overdueCount").asInt(), "此时没有逾期");
        assertEquals(0, summary.path("orderCount").asInt(), "此时还没有订单");
        assertTrue(summary.path("usable").asBoolean(), "已开通且无欠款 → 可用");
        assertFalse(summary.has("singleLimit"), "v49 撤回后不该再下发单笔上限");
        assertFalse(summary.has("allowFirstOrder"), "v49 撤回后不该再下发首单放行");
    }
}
