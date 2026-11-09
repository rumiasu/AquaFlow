package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 水票仅抵水费；其他费用另付，不支持部分票补水费。 */
@DisplayName("水票支付 · 结算页抵扣预览")
class TicketPayPreviewIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("票够只抵水费，其他费用另付")
    void fullyCoveredMeansNothingToPay() {
        long station = createStation("预览足票站");
        long customer = createCustomer("预览足票客户", "tpp-full-openid");
        long address = createAddress(customer, "预览足票小区1号");
        // 站级水票价 18.00 = 一张票抵一桶的钱
        long water = createProduct("预览足票水", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, water, 100, 1, "18.00");
        createTicketAccount(customer, station, water, 10);
        String cus = customerToken(customer);

        // 2 桶：水费 36.00 + 押金 60.00（首单无桶，2×30）
        JsonNode d = quote(cus, station, address, water, 2, 3);
        JsonNode tp = d.path("ticketPay");
        assertFalse(tp.isNull() || tp.isMissingNode(), "水票支付必须下发抵扣预览: " + d);
        assertTrue(tp.path("fullyCovered").asBoolean(), "10 张票 ≥ 2 桶，水费可用票结清");
        assertEquals(2, tp.path("needQty").asInt());
        assertEquals(2, tp.path("coverQty").asInt());
        assertEquals(0, tp.path("shortfallQty").asInt());

        BigDecimal total = d.path("totalAmount").decimalValue();
        assertEquals(0, new BigDecimal("96.00").compareTo(total), "前置：2 桶合计 36+60=96.00");
        assertEquals(0, new BigDecimal("36.00").compareTo(tp.path("coverAmount").decimalValue()),
                "coverAmount 是被票抵掉的那部分**水费**（账目口径）");
        // 新规则：票不支付押金或服务费；当前旧桶账fixture押金60另付。
        assertEquals(0, new BigDecimal("60.00").compareTo(tp.path("payableAmount").decimalValue()),
                "水票只抵水费，其他应付60元；没有获授权服务费渠道时明确阻断");
        assertTrue(tp.path("hint").asText("").contains("水票仅抵水费"), "文案要说清水票只抵水费: " + tp.path("hint"));

        // ★ 真实订单金额**不能**因展示而变：前端拿 payableAmount 只渲染，提交金额仍由后端按订单重算
        assertEquals(0, new BigDecimal("96.00").compareTo(d.path("totalAmount").decimalValue()),
                "totalAmount 仍是真实订单金额（展示值不得回写它）");
    }

    @Test
    @DisplayName("票不够：不部分抵水费，选择整单付款或补齐水票")
    void shortfallFallsBackToFullAmount() {
        long station = createStation("预览缺票站");
        long customer = createCustomer("预览缺票客户", "tpp-short-openid");
        long address = createAddress(customer, "预览缺票小区1号");
        long water = createProduct("预览缺票水", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, water, 100, 1, "18.00");
        createTicketAccount(customer, station, water, 1);   // 只有 1 张，本单要 3 桶
        String cus = customerToken(customer);

        JsonNode d = quote(cus, station, address, water, 3, 3);
        JsonNode tp = d.path("ticketPay");
        assertFalse(tp.path("fullyCovered").asBoolean(), "1 张票抵不了 3 桶");
        assertEquals(3, tp.path("needQty").asInt());
        assertEquals(0, tp.path("coverQty").asInt(),
                "「不够也不拿统一票补差额」且 consumeTicket 要求余额 ≥ 整行数量 → 这一行一张都抵不了");
        assertEquals(3, tp.path("shortfallQty").asInt());

        BigDecimal total = d.path("totalAmount").decimalValue();
        assertEquals(0, total.compareTo(tp.path("payableAmount").decimalValue()),
                "票不够 → 本单用不了票，要付就是全额");
        String hint = tp.path("hint").asText("");
        assertTrue(hint.contains("不支持部分水票抵水费"), "必须说清不支持部分票补水费: " + hint);
        assertTrue(hint.contains("本次未扣票"), "必须说清票不会被扣: " + hint);

        // 逐行提示仍在 warnings 里（与结构化数据同源），且**不能是 null 元素**（null 会进 JSON 数组）
        JsonNode warnings = d.path("warnings");
        assertTrue(warnings.isArray() && warnings.size() >= 1, "票不够必须给可读提示: " + warnings);
        for (JsonNode w : warnings) {
            assertTrue(w.isTextual() && !w.asText().isEmpty(), "warnings 元素必须是文本: " + w);
        }
    }

    @Test
    @DisplayName("按站级统一折扣买的票同样算「全抵」；现金支付不下发预览（不干扰别的支付方式）")
    void unifiedDiscountAlsoCountsAndNonTicketHasNoPreview() {
        long station = createStation("预览统一站");
        long manager = createStaff("预览统一站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("预览统一客户", "tpp-unified-openid");
        long address = createAddress(customer, "预览统一小区1号");
        // 本站不为这款水开定制票；站里配了统一折扣 → 按该款水的价打折买票（v58 形态）
        long water = createProduct("预览统一水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, water, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        assertEquals(0, post("/api/ticket-discounts", mgr,
                "{\"qty\":10,\"discountPerMille\":950}").code(), "站长配统一折扣 10 张 9.5 折");
        long payId = post("/api/tickets/purchase", cus,
                "{\"productId\":" + water + ",\"quantity\":10,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"unifiedQty\":10,\"idempotencyKey\":\"tpp-unified-buy\"}")
                .data().path("paymentId").asLong();
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code(), "站长确认收款");

        JsonNode d = quote(cus, station, address, water, 2, 3);
        JsonNode tp = d.path("ticketPay");
        assertTrue(tp.path("fullyCovered").asBoolean(), "10 张票 ≥ 2 桶，应算全抵: " + tp);
        assertEquals(0, new BigDecimal("60.00").compareTo(tp.path("payableAmount").decimalValue()));

        // 现金（货到付款）支付：结算页照旧逐项列水费/押金/配送费，**不该**出现水票抵扣预览
        JsonNode cash = quote(cus, station, address, water, 2, 2);
        assertTrue(cash.path("ticketPay").isNull(), "非水票支付不得下发抵扣预览（否则计费区会按票显示）");
        assertEquals(0, new BigDecimal("100.00").compareTo(cash.path("totalAmount").decimalValue()),
                "现金支付的合计 = 水费 40 + 押金 60（票够与否都不影响它）");
    }

    @Test
    @DisplayName("商品根本不能用票时，「补票」解决不了 —— 文案必须指向改选支付方式")
    void unavailableIsNotTheSameAsShortfall() {
        long station = createStation("预览不可用站");
        long customer = createCustomer("预览不可用客户", "tpp-na-openid");
        long address = createAddress(customer, "预览不可用小区1号");
        // 瓶装水：本站没开定制水票，且统一票只抵桶装水 → 这一项根本不能用票
        long bottle = createProduct("预览不可用瓶装水", 2, "2.00", "0.00", 0, "0.00");
        createInventoryFull(station, bottle, 100, 0, "0.00");
        String cus = customerToken(customer);

        JsonNode tp = quote(cus, station, address, bottle, 2, 3).path("ticketPay");
        assertFalse(tp.path("fullyCovered").asBoolean());
        assertEquals(0, tp.path("coverQty").asInt());
        assertTrue(tp.path("title").asText("").contains("不能用票"),
                "标题要说清是「不能用票」而不是「票不够」，实际=" + tp.path("title").asText(""));
        String hint = tp.path("hint").asText("");
        assertTrue(hint.contains("补票也解决不了"),
                "必须告诉客户补票没用（否则他会去买一堆用不上的票），实际=" + hint);
        assertTrue(hint.contains("请改选支付方式"), "要给出正确的下一步动作，实际=" + hint);
    }

    private JsonNode quote(String cus, long station, long address, long product, int qty, int paymentMethod) {
        Api res = post("/api/payments/quote", cus, "{\"stationId\":" + station
                + ",\"paymentMethod\":" + paymentMethod + ",\"addressId\":" + address
                + ",\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}");
        assertEquals(0, res.code(), "报价应成功: " + res);
        return res.data();
    }
}
