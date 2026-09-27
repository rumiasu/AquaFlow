package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信渠道<b>关闭</b>时（生产默认：{@code app.payment.mock-wechat-pay=false}）报价里的渠道能力。
 *
 * <p><b>这是 {@link MockWechatPayIntegrationTest} 的对照组</b>，本类刻意<b>不加</b>
 * {@code @TestPropertySource}，跑的就是默认值 false。两者合起来才证明"开关只影响该影响的东西"。</p>
 *
 * <p><b>为什么值得单独锁一条（2026-09-26 返工契约 P0-b）</b>：客户端的
 * {@code proceedToPayment} 现在按报价下发的 {@code wechatPay.enabled} 决定"建单成功后要不要对
 * 同一张单发起 {@code createPayment}"。这条判据一旦在生产上为 true，客户点微信建完单就会拿到
 * 一条必然停在待收款(1) 的流水，而界面还可能照着"响应成功"报成已付 —— 那正是本仓最忌讳的
 * "界面说做了、账上没动"。所以这里必须钉死：<b>开关关着 ⇒ enabled=false 且 simulated=false</b>。</p>
 */
class WechatChannelDisabledIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("渠道关闭：报价的 wechatPay.enabled=false，且微信项在 methods 里不可选")
    void quoteReportsWechatChannelDisabled() {
        long station = createStation("微信关闭站");
        long customer = createCustomer("微信关闭客户", "wechat-disabled-openid");
        long product = createProduct("微信关闭水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 100);

        Api res = post("/api/payments/quote", customerToken(customer),
                "{\"stationId\":" + station + ",\"paymentMethod\":1,"
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}");
        assertEquals(0, res.code(), "报价应成功: " + res);

        JsonNode capability = res.data().get("wechatPay");
        assertTrue(capability != null && !capability.isNull(),
                "键必须始终存在（前端不必判 undefined，否则会长出第二套默认值）: " + res.data());
        assertEquals(1, capability.path("method").asInt(), "渠道能力要标明是哪种支付方式: " + capability);
        assertFalse(capability.path("enabled").asBoolean(),
                "真实渠道未接入且模拟开关关闭 ⇒ 绝不能对客户端说微信可用: " + capability);
        assertFalse(capability.path("simulated").asBoolean(),
                "模拟开关关着时不能声称是模拟渠道: " + capability);

        // 与 methods 那一项同源：两边一致才说明判据只有一处
        JsonNode wechat = null;
        for (JsonNode m : res.data().get("methods")) {
            if (m.path("id").asInt() == 1) {
                wechat = m;
            }
        }
        assertTrue(wechat != null, "微信这一项要**灰着显示**（客户先知道还有这条路），不能整项消失");
        assertFalse(wechat.path("enabled").asBoolean(), "渠道没接入时微信项必须不可选: " + wechat);
        assertEquals(capability.path("enabled").asBoolean(), wechat.path("enabled").asBoolean(),
                "wechatPay.enabled 与 methods 里微信项的 enabled 必须同源（同一个开关，不许两处各判一次）");
    }
}
