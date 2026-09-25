package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractScenarioTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 站长接单页的**客户信用标记**：待分配列表每一行都带上"这个客户欠不欠钱"，
 * 前端据此上色（黄 = 有挂账；红 = 已逾期或超额度）。
 *
 * <p><b>为什么要随列表下发、而不是让前端逐单去查</b>：列表一页几十行，
 * 逐行算一次风险就是几十次 SQL（本仓"列表页 N+1"的老坑）。
 * 后端一次批量算好（{@code CustomerRiskService.summarizeStation}），前端只负责上色。</p>
 *
 * <p><b>六个标记字段</b>（都是 {@code Orders} 上的**关联/计算字段，不是数据库列**）：</p>
 * <ul>
 *   <li>{@code customerType}：1 个人 / 2 企业；</li>
 *   <li>{@code customerRiskLevel}：NORMAL / WATCH / ALERT / FREEZE；</li>
 *   <li>{@code customerRiskLevelText}：中文（正常 / 关注 / 预警 / 冻结）—— **文案只有一个来源**
 *       （{@code CustomerRiskService.textOf}），前端不许自带 NORMAL→「正常」映射表；</li>
 *   <li>{@code customerRiskNote}：给站长看的那句话（"有 ¥320.00 挂账，都在账期内"），同样后端拼好；</li>
 *   <li>{@code outstandingCredit}：未结赊账（**只算水费，不含押金**）；</li>
 *   <li>{@code overdueDays}：最长逾期天数（0 = 没逾期）。</li>
 * </ul>
 *
 * <p>⚠️ 等级必须**总是有值**（该客户没有赊账时也要明确下发 NORMAL），
 * 否则前端要自己区分"没有这个字段"和"值是空" —— 那是让前端猜。</p>
 */
@DisplayName("站长接单页 · 客户信用标记（企业 / 有挂账 / 逾期）随列表下发")
class StationOrderListRiskMarkIntegrationTest extends AbstractScenarioTest {

    private static final int CASH = 2;
    private static final int TICKET = 3;

    /** 在站长的待分配列表里按订单 id 找那一行；找不到返回 null。 */
    private JsonNode rowOf(World w, long orderId, String token) {
        Api res = get("/api/delivery/orders/station-pending", token);
        assertEquals(0, res.code(), "待分配列表应可读: " + res);
        JsonNode data = res.data();
        if (data == null || !data.isArray()) {
            return null;
        }
        for (JsonNode node : data) {
            if (node.path("id").asLong() == orderId) {
                return node;
            }
        }
        return null;
    }

    private long pendingCashOrder(World w, String key, int qty) {
        assertEquals(0, placeOrder(w, key, CASH, qty).code(), "下现金单应成功");
        long order = orderIdOf(key);
        assertNotNull(rowOf(w, order, w.managerToken()), "现金单下单即进站长待分配（货到付款是例外）");
        return order;
    }

    @Test
    @DisplayName("现金待分配单：该行带「有挂账」标记（WATCH）+ 未结金额，且不逾期")
    void pendingCashOrderIsMarkedAsWatch() {
        World w = openStation("标记站A");
        long order = pendingCashOrder(w, "mk-a1", 2);

        JsonNode row = rowOf(w, order, w.managerToken());
        assertNotNull(row, "订单应在待分配列表里");
        assertEquals("WATCH", row.path("customerRiskLevel").asText(),
                "有未结赊账、未逾期 → 关注（前端据此标黄），实际=" + row);
        assertEquals("关注", row.path("customerRiskLevelText").asText(),
                "徽标中文由后端下发，前端不许自带映射表，实际=" + row);
        assertEquals("有 ¥40.00 挂账，都在账期内", row.path("customerRiskNote").asText(),
                "那句话也由后端拼好（前端自己不拼「欠了多少」），实际=" + row);
        assertEquals(1, row.path("customerType").asInt(),
                "个人客户的 customerType 应为 1（企业为 2），实际=" + row);
        assertEquals(0, row.path("overdueDays").asInt(), "还没到期，逾期天数应为 0，实际=" + row);
        assertEquals(0, row.path("outstandingCredit").decimalValue()
                        .compareTo(new java.math.BigDecimal("40.00")),
                "未结金额应只算**水费**（2 桶 × 20 = 40，不含押金），实际=" + row.path("outstandingCredit"));
    }

    @Test
    @DisplayName("逾期之后每一行升级为红：3 天 → 预警，20 天 → 冻结（前端据此从黄转红）")
    void overdueUpgradesTheRow() {
        World w = openStation("标记站B");
        long order = pendingCashOrder(w, "mk-b1", 1);

        jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL 3 DAY) WHERE id=?", order);
        JsonNode alert = rowOf(w, order, w.managerToken());
        assertEquals("ALERT", alert.path("customerRiskLevel").asText(), "逾期 3 天应是预警，实际=" + alert);
        assertEquals(3, alert.path("overdueDays").asInt(), "逾期天数应如实下发，实际=" + alert);

        jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL 20 DAY) WHERE id=?", order);
        JsonNode freeze = rowOf(w, order, w.managerToken());
        assertEquals("FREEZE", freeze.path("customerRiskLevel").asText(),
                "逾期 20 天（超过阈值 15）应是冻结，实际=" + freeze);
        assertEquals(20, freeze.path("overdueDays").asInt(), "实际=" + freeze);
    }

    @Test
    @DisplayName("没有赊账的行也要**明确**下发 NORMAL 与 0，不能让前端去猜缺字段是什么意思")
    void rowsWithoutCreditStillCarryExplicitNormal() {
        World w = openStation("标记站C");
        // 水票单：下单 → 扣票支付 → 已付，于是它既进站长视野、又不属于赊账
        createTicketAccount(w.customerId(), w.stationId(), w.productId(), 10);
        assertEquals(0, placeOrder(w, "mk-c1", TICKET, 1).code(), "水票下单应成功");
        long order = orderIdOf("mk-c1");
        assertEquals(0, post("/api/payments", w.customerToken(),
                "{\"orderId\":" + order + ",\"paymentMethod\":3}").code(), "用票支付应成功");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "扣票即视同已付");

        JsonNode row = rowOf(w, order, w.managerToken());
        assertNotNull(row, "已付的水票单应进站长待分配");
        assertEquals("NORMAL", row.path("customerRiskLevel").asText(),
                "不属于赊账的单也应明确下发 NORMAL，实际=" + row);
        assertEquals("正常", row.path("customerRiskLevelText").asText(),
                "中文也要有值（前端靠它决定「要不要标」），实际=" + row);
        assertEquals(0, row.path("outstandingCredit").decimalValue()
                        .compareTo(java.math.BigDecimal.ZERO),
                "未结赊账应为 0（水票已付、且水票本来就不算赊账），实际=" + row.path("outstandingCredit"));
        assertEquals(0, row.path("overdueDays").asInt(), "逾期天数应为 0，实际=" + row);
    }

    /**
     * <b>列表上色与客户详情页的等级必须逐字相同</b> —— 这是本类最该守住的一条。
     *
     * <p>2026-09-22 之前它们是两个数：列表那条路不查额度，于是"超额度但没逾期"的客户
     * 在列表上是黄（关注）、点进详情却变红（预警）。两处各写一套判据，迟早分叉
     * （本仓"计价双轨"的同款形状）。现在两边共用 {@code CustomerRiskService.levelOf}
     * 与 {@code limitOf}，这条用例就是那道防分叉的闸门。</p>
     *
     * <p>造数用"老账不还"这个真实形状：额度公式是 {@code max(300, 近 90 天水费)}，
     * 而"欠款"没有 90 天窗口 —— 所以<b>超额度只会由"超过 90 天还没结的账"造成</b>
     * （正常买、正常结的客户，欠多少就有多少历史消费顶着，永远到不了超额度）。</p>
     */
    @Test
    @DisplayName("列表等级与客户详情页的风险等级必须一致（超额度也要在列表上变红）")
    void listLevelMatchesCustomerDetailLevel() {
        World w = openStation("标记站D");
        long order = pendingCashOrder(w, "mk-d1", 20);   // 400 元挂账

        // 把这笔挂账挪到 90 天窗口之外：额度公式的分子归零（额度退回下限 300），
        // 而未结赊账仍是 400 → 超额度。
        jdbc.update("UPDATE orders SET create_time = DATE_SUB(NOW(), INTERVAL 120 DAY) WHERE id=?", order);

        JsonNode row = rowOf(w, order, w.managerToken());
        assertNotNull(row, "订单应在待分配列表里（列表只按状态/付款方式筛，不看下单时间）");
        assertEquals("ALERT", row.path("customerRiskLevel").asText(),
                "未结 400 超过额度 300 → 预警（列表上要变红），实际=" + row);

        Api detail = get("/api/manager/customers/" + w.customerId() + "/risk", w.managerToken());
        assertEquals(0, detail.code(), "客户风险详情应可读: " + detail);
        assertEquals(detail.data().path("level").asText(), row.path("customerRiskLevel").asText(),
                "同一个客户在**列表**与**详情页**上的等级必须是同一个数（分叉过一次，别再分叉）: 列表="
                        + row.path("customerRiskLevel") + " 详情=" + detail.data());
        assertEquals(detail.data().path("reason").asText(), row.path("customerRiskNote").asText(),
                "列表上那句话也要与详情页的 reason 逐字相同: 详情=" + detail.data());
    }
}
