package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业身份的**平台默认阈值**（v51）：没配过的水站按 {@code app.enterprise.large-order-barrels}=30 桶提示。
 *
 * <p>为什么要单独一个类：{@code EnterpriseIdentityIntegrationTest} 把默认桶数压到 10 是为了省造数，
 * 那样就**测不到真实默认值 30**。而"30 桶"是产品原话里的数（「水超过 30 桶就可以吧」），
 * 改配置默认值却没人发现，等于这条裁定悄悄失效 —— 所以这里用默认属性原样钉一遍边界：
 * <b>29 桶不提示、30 桶提示</b>（阈值语义是"达到即提示"，要严格"超过"就填 31）。</p>
 */
@TestPropertySource(properties = "app.enterprise.enabled=true")
@DisplayName("企业身份（平台默认阈值）：29 桶不提示、30 桶提示")
class EnterpriseIdentityDefaultThresholdIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("未配置的水站按平台默认 30 桶提示；29 桶不提示（边界是「达到」不是「超过」）")
    void defaultThresholdIsThirtyBarrels() {
        long station = createStation("默认阈值站");
        long customer = createCustomer("默认阈值客户", "ent-default-openid");
        long address = createAddress(customer, "默认阈值小区1号");
        long water = createProduct("默认阈值桶装水", 1, "20.00", "50.00", 0, "0.00");
        createInventoryFull(station, water, 500, 0, "0.00");
        createCustomerStationConfig(customer, station, 0);
        String cus = customerToken(customer);

        assertTrue(quote(cus, station, address, water, 29).path("enterpriseHint").isNull(),
                "29 桶 < 平台默认 30 桶，不该提示");
        JsonNode thirty = quote(cus, station, address, water, 30);
        assertFalse(thirty.path("enterpriseHint").isNull(), "30 桶达到平台默认阈值，应提示");
        assertTrue(thirty.path("enterpriseHint").asText("").contains("30 桶"),
                "文案要报本单桶数，实际=" + thirty.path("enterpriseHint").asText(""));
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_enterprise_config WHERE station_id=?", station),
                "本用例刻意不落任何配置行 —— 走的就是「没配过 → 平台默认」那条路");
    }

    private JsonNode quote(String cus, long station, long address, long product, int qty) {
        Api res = post("/api/payments/quote", cus, "{\"stationId\":" + station + ",\"paymentMethod\":2,"
                + "\"addressId\":" + address + ",\"items\":[{\"productId\":" + product
                + ",\"quantity\":" + qty + "}]}");
        assertEquals(0, res.code(), "报价应成功: " + res);
        return res.data();
    }
}
