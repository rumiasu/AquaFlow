package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractScenarioTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户信用风险（验资）：风险等级与可赊额度**算出来的**，不是存出来的。
 *
 * <p><b>等级四档</b>（判据见 {@code CustomerRiskService.assess}）：
 * 正常 / 关注（有挂账但在账期内 —— <b>正常经营</b>）/ 预警（有逾期或超额度）/ 冻结（逾期超过 15 天）。</p>
 *
 * <p><b>额度不给死值</b>：{@code max(300, 该客户近 90 天月均水费 × 3)}。
 * 用户口径是「5000 对于小站来说挺沉重的」—— 所以额度跟着**这个客户自己的**消费规模走，
 * 站长一个数都不用填。</p>
 *
 * <p><b>为什么"自动解冻"是设计的核心</b>：等级是现算的，欠款一结清自然回到正常。
 * 手写一个冻结/解冻开关看着直观，但它会变成站长**又要记得去点一下**的东西 ——
 * 忘了解冻客户莫名其妙下不了单，忘了冻结风险裸奔。</p>
 */
@DisplayName("客户信用风险：额度按消费规模算 + 逾期才拦退押金 + 结清自动解冻")
class CustomerCreditRiskIntegrationTest extends AbstractScenarioTest {

    private static final int CASH = 2;

    private JsonNode riskOf(World w) {
        Api res = get("/api/manager/customers/" + w.customerId() + "/risk", w.managerToken());
        assertEquals(0, res.code(), "读信用画像应成功: " + res);
        return res.data();
    }

    /** 退桶预检（顾客自助，只读）：返回 data，含 blocked / blockedReason。 */
    private JsonNode returnPreview(World w, int qty) {
        Api res = get("/api/barrels/return/preview?productId=" + w.productId()
                + "&quantity=" + qty + "&stationId=" + w.stationId(), w.customerToken());
        assertEquals(0, res.code(), "退桶预检应成功: " + res);
        return res.data();
    }

    private int status(long orderId) {
        return intOf("SELECT status FROM orders WHERE id=?", orderId);
    }

    private int paymentStatus(long orderId) {
        return intOf("SELECT payment_status FROM orders WHERE id=?", orderId);
    }

    /** 走真实链路造一张「已送达未收款」的现金单。 */
    private long deliveredUnpaidCashOrder(World w, String key, int qty) {
        assertEquals(0, placeOrder(w, key, CASH, qty).code(), "下单应成功");
        long order = orderIdOf(key);
        assertEquals(0, acceptOrder(w, order).code(), "接单应成功");
        assertEquals(0, completeDelivery(w, order, qty, 0, false, null).code(), "送达（不收款）应成功");
        assertEquals(3, status(order), "应停在 已送达(3)");
        return order;
    }

    @Test
    @DisplayName("新客户没有历史可验资 → 用平台下限额度（300），等级正常")
    void newCustomerGetsFloorLimit() {
        World w = openStation("验资站A");

        JsonNode risk = riskOf(w);
        assertEquals("NORMAL", risk.path("level").asText(), "还没消费过的客户应是正常，实际=" + risk);
        assertEquals(0, risk.path("creditLimit").decimalValue().compareTo(new java.math.BigDecimal("300")),
                "没有历史 → 用平台下限 300 元，实际=" + risk.path("creditLimit"));
        assertFalse(risk.path("returnBlocked").asBoolean(), "正常客户退桶不该被拦");
    }

    @Test
    @DisplayName("额度跟着客户自己的消费规模走：本季水费越高额度越大（不给死值）")
    void limitGrowsWithConsumption() {
        World w = openStation("验资站B");

        // 下 20 桶的单（水费 20×20 = 400 元）→ 90 天月均 ≈ 133 → ×3 ≈ 400 > 下限 300
        assertEquals(0, placeOrder(w, "risk-big", CASH, 20).code(), "大额下单应成功");

        JsonNode risk = riskOf(w);
        assertTrue(risk.path("creditLimit").decimalValue().compareTo(new java.math.BigDecimal("300")) > 0,
                "有消费历史后额度应超过下限，实际=" + risk.path("creditLimit"));
        assertEquals("WATCH", risk.path("level").asText(),
                "有未结赊账、且都在账期内 → 关注（这是正常经营，不该升级为预警），实际=" + risk);
    }

    @Test
    @DisplayName("账期内挂账 = 正常经营：只标记、**不拦退押金**（别误伤月结客户）")
    void outstandingWithinTermDoesNotBlockReturn() {
        World w = openStation("验资站C");
        // 给客户设月结 30 天，再走完下单→送达（不收款）
        assertEquals(0, put("/api/manager/customers/" + w.customerId() + "/credit-terms",
                w.managerToken(), "{\"dueDays\":30}").code(), "设账期应成功");
        deliveredUnpaidCashOrder(w, "risk-c1", 1);

        JsonNode risk = riskOf(w);
        assertEquals("WATCH", risk.path("level").asText(), "账期内应是关注，实际=" + risk);
        assertFalse(risk.path("returnBlocked").asBoolean(),
                "账期内退桶**不该**被拦 —— 月结客户天天如此，拦它就是误伤主业，实际=" + risk);

        JsonNode preview = returnPreview(w, 1);
        assertFalse(preview.path("blocked").asBoolean(),
                "账期内退桶预检应放行，实际=" + preview);
    }

    @Test
    @DisplayName("逾期之后：等级升为预警、退押金被拦，且拒绝原因说的是「欠款」而不是「余额不足」")
    void overdueBlocksReturnWithClearReason() {
        World w = openStation("验资站D");
        assertEquals(0, put("/api/manager/customers/" + w.customerId() + "/credit-terms",
                w.managerToken(), "{\"dueDays\":30}").code(), "设账期应成功");
        long order = deliveredUnpaidCashOrder(w, "risk-d1", 1);

        // 造数：把应付日期推到 3 天前 = 已逾期（但还没到冻结阈值 15 天）
        assertEquals(1, jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL 3 DAY) WHERE id=?",
                order), "造数：应付日期推到 3 天前");

        JsonNode risk = riskOf(w);
        assertEquals("ALERT", risk.path("level").asText(), "逾期应是预警（未超 15 天不冻结），实际=" + risk);
        assertEquals(3, risk.path("maxOverdueDays").asInt(), "最长逾期天数应如实反映，实际=" + risk);
        assertTrue(risk.path("returnBlocked").asBoolean(), "逾期客户退押金应被拦，实际=" + risk);

        JsonNode preview = returnPreview(w, 1);
        assertTrue(preview.path("blocked").asBoolean(), "逾期客户退桶预检应拦下，实际=" + preview);
        String reason = preview.path("blockedReason").asText();
        assertTrue(reason.contains("欠款"),
                "拒绝原因必须指向**欠款**（只报「押金余额不足」会让站长看不出真实原因），实际=" + reason);
    }

    @Test
    @DisplayName("逾期超过 15 天升为冻结；欠款一结清就**自动**回到正常、退桶也自动放行（不需要谁去点解冻）")
    void overdueBeyondThresholdFreezesAndSettlementAutoUnfreezes() {
        World w = openStation("验资站E");
        assertEquals(0, put("/api/manager/customers/" + w.customerId() + "/credit-terms",
                w.managerToken(), "{\"dueDays\":30}").code(), "设账期应成功");
        long order = deliveredUnpaidCashOrder(w, "risk-e1", 1);

        // 造数：逾期 20 天 > 阈值 15 天
        assertEquals(1, jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL 20 DAY) WHERE id=?",
                order), "造数：应付日期推到 20 天前");
        assertEquals("FREEZE", riskOf(w).path("level").asText(), "超过阈值应升为冻结，实际=" + riskOf(w));
        assertTrue(returnPreview(w, 1).path("blocked").asBoolean(), "冻结时退桶应被拦");

        // 收款结清 → 等级与拦截**自动**恢复，没有任何"解冻"动作
        assertEquals(0, confirmOfflinePay(w, order).code(), "确认收款应成功");
        assertEquals(2, paymentStatus(order), "应已付");

        JsonNode after = riskOf(w);
        assertEquals("NORMAL", after.path("level").asText(),
                "欠款结清后必须**自动**回到正常（靠现算，不靠人去点解冻），实际=" + after);
        assertFalse(after.path("returnBlocked").asBoolean(), "结清后退押金应自动放行");
        assertFalse(returnPreview(w, 1).path("blocked").asBoolean(),
                "结清后退桶预检应自动放行，实际=" + returnPreview(w, 1));
    }
}
