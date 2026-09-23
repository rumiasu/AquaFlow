package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站级账期（v60）：把「这笔钱最晚什么时候该到」从客户级搬到 **客户 × 水站级**。
 *
 * <p><b>要修的租户边界漏洞</b>：账期原先是 {@code company_info.due_days}（客户级，
 * {@code uk_company_customer} 唯一），而设置端点只校验"该客户归属本站" ——
 * 于是 A 站设的账期会在 B 站生效，B 站还能把它改掉。仓库里同一件事早有定论
 * （{@code customer_privilege} 表注释：「A 站给的不在 B 站生效（否则等于跨站送钱）」）。</p>
 *
 * <p><b>v60 之后的账期口径</b>（两个字段合起来才有账期，缺一不可）：</p>
 * <ul>
 *   <li>{@code customer_station_config.settlement_cycle} = {@code MONTHLY}（月结）；</li>
 *   <li>{@code customer_station_config.due_days} &gt; 0。</li>
 * </ul>
 * <p>满足两者 → {@code orders.due_date = 下单当月的最后一天 + due_days}（下单时快照、之后只读）；
 * 否则 {@code due_date} 为空 = 即时结清。</p>
 *
 * <p>⚠️ 本类显式打开企业身份开关（{@code @TestPropertySource}），因为"一键套用平台默认账期"
 * 挂在企业审核通过那一步。关掉开关时的行为由 {@code EnterpriseIdentityDisabledIntegrationTest} 钉住。</p>
 */
@TestPropertySource(properties = {
        "app.enterprise.enabled=true"
})
@DisplayName("站级账期（v60）：A 站设的不在 B 站生效 + 企业一键套用 + 未结账单重算")
class StationCreditTermsIntegrationTest extends AbstractIntegrationTest {

    /** 平台默认账期天数（与 constant/SettlementCycle.PLATFORM_DEFAULT_DUE_DAYS 同值）。 */
    private static final int PLATFORM_DEFAULT_DUE_DAYS = 30;

    private long stationA;
    private long stationB;
    private long customer;
    private long address;
    private long water;
    private String tokenA;
    private String tokenB;
    private String cus;

    private void seed() {
        stationA = createStation("A站");
        stationB = createStation("B站");
        long mgrA = createStaff("A站长", "STATION_MANAGER", stationA, 1);
        long mgrB = createStaff("B站长", "STATION_MANAGER", stationB, 1);
        customer = createCustomer("客户", "credit-openid");
        address = createAddress(customer, "信用小区1号");
        water = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(stationA, water, 100, 1, "18.00");
        createInventoryFull(stationB, water, 100, 1, "18.00");
        // 两站都开通货到付款 —— 否则连现金单都下不出来（未开通的客户下单即被拒）
        createCustomerStationConfig(customer, stationA, 1);
        createCustomerStationConfig(customer, stationB, 1);
        tokenA = staffToken(mgrA, "STATION_MANAGER", stationA);
        tokenB = staffToken(mgrB, "STATION_MANAGER", stationB);
        cus = customerToken(customer);
    }

    /** 下现金单（贷到付款）：账期只对现金单产生应付日期。 */
    private Api placeCashOrder(long stationId, String key) {
        return post("/api/orders/create", cus, "{\"addressId\":" + address
                + ",\"stationId\":" + stationId + ",\"paymentMethod\":2,\"idempotencyKey\":\"" + key + "\","
                + "\"items\":[{\"productId\":" + water + ",\"quantity\":1}]}");
    }

    /** 该单快照下来的应付日期（{@code null} = 即时结清）。 */
    private String dueDateOf(String key) {
        return jdbc.queryForObject("SELECT due_date FROM orders WHERE idempotency_key=?", String.class, key);
    }

    private JsonNode termsOf(String token) {
        Api res = get("/api/manager/customers/" + customer + "/credit-terms", token);
        assertEquals(0, res.code(), "读账期应可读: " + res);
        return res.data();
    }

    private Api setTerms(String token, String body) {
        return put("/api/manager/customers/" + customer + "/credit-terms", token, body);
    }

    @Test
    @DisplayName("账期是**站级**的：A 站设了月结 30 天，B 站读到的仍是现结、B 站的单不产生应付日期")
    void creditTermsAreStationScoped() {
        seed();

        assertEquals(0, setTerms(tokenA, "{\"dueDays\":30}").code(), "A 站给客户设月结应成功");

        // A 站读到月结 30 天；B 站读到现结（这就是 v60 要修的租户边界）
        JsonNode a = termsOf(tokenA);
        assertEquals(PLATFORM_DEFAULT_DUE_DAYS, a.path("dueDays").asInt(), "A 站应读到 30 天，实际=" + a);
        assertEquals("MONTHLY", a.path("settlementCycle").asText(), "A 站应是月结");
        JsonNode b = termsOf(tokenB);
        assertTrue(b.path("dueDays").isNull() || b.path("dueDays").asInt(0) == 0,
                "B 站必须仍是现结 —— A 站设的账期不得在 B 站生效，实际=" + b);
        assertEquals("IMMEDIATE", b.path("settlementCycle").asText(), "B 站的结算周期应是现结");
        assertTrue(b.path("termsText").asText().contains("未设账期"),
                "B 站的文案也要说清是未设账期，实际=" + b.path("termsText").asText());

        // 两边各下一张现金单：只有 A 站的单会快照出应付日期
        assertEquals(0, placeCashOrder(stationA, "ct-a").code(), "A 站下单应成功");
        assertEquals(0, placeCashOrder(stationB, "ct-b").code(), "B 站下单应成功");
        assertNotNull(dueDateOf("ct-a"), "A 站（月结）的单必须快照出应付日期");
        assertNull(dueDateOf("ct-b"), "B 站（现结）的单不得有应付日期");
    }

    @Test
    @DisplayName("一键套用：站长通过企业申请 → 该客户在**该站**自动获得平台默认账期（月结 30 天），别的站不受影响")
    void approvingEnterpriseAppliesPlatformDefaultCreditTerms() {
        seed();

        assertEquals(0, post("/api/enterprise/applications", cus, "{\"stationId\":" + stationA
                        + ",\"companyName\":\"某某公司\",\"contactPerson\":\"张三\","
                        + "\"contactPhone\":\"13800000000\"}").code(), "客户提交企业申请应成功");
        long applyId = longOf("SELECT id FROM customer_enterprise_apply WHERE customer_id=?", customer);
        assertTrue(applyId > 0, "申请应落库");
        assertEquals(0, put("/api/enterprise/manager/applications/" + applyId, tokenA,
                "{\"approve\":true,\"note\":\"已核对营业执照\"}").code(), "站长通过申请应成功");

        // 站长的动作只是"点一下通过"，账期自动套上 —— 他不需要理解什么是结算周期
        JsonNode a = termsOf(tokenA);
        assertEquals(PLATFORM_DEFAULT_DUE_DAYS, a.path("dueDays").asInt(),
                "通过后应自动获得平台默认账期（" + PLATFORM_DEFAULT_DUE_DAYS + " 天），实际=" + a);
        assertEquals("MONTHLY", a.path("settlementCycle").asText(), "平台默认是月结");

        // 下单立刻生效（due_date 在下单那一刻快照）
        assertEquals(0, placeCashOrder(stationA, "ct-ent").code(), "套用账期后下单应成功");
        assertNotNull(dueDateOf("ct-ent"), "套用账期后下单必须快照出应付日期");

        // ⚠️ 而且**只在该站生效**：审批只影响审批的那个站，别的站仍是现结
        JsonNode b = termsOf(tokenB);
        assertTrue(b.path("dueDays").isNull() || b.path("dueDays").asInt(0) == 0,
                "一键套用只影响审批的那个站，B 站不该跟着有账期，实际=" + b);
    }

    @Test
    @DisplayName("改账期只对**新单**生效（老单是下单时快照）；按「重算」才把未结账单改过来，且留痕")
    void changingTermsOnlyAffectsNewOrdersUntilRecalculated() {
        seed();
        assertEquals(0, setTerms(tokenA, "{\"dueDays\":30}").code(), "先设 30 天");
        assertEquals(0, placeCashOrder(stationA, "ct-old").code(), "下老单");
        String before = dueDateOf("ct-old");
        assertNotNull(before, "老单应有应付日期");

        // 改成 15 天
        assertEquals(0, setTerms(tokenA, "{\"dueDays\":15}").code(), "改成 15 天");

        // ① 老单**不变** —— 这是设计如此（与金额/地址快照同源），不是 bug
        assertEquals(before, dueDateOf("ct-old"),
                "老单的应付日期是下单时快照，改账期不得动它（界面必须把这句话写给站长看）");

        // ② 新单按新账期，比老单早
        assertEquals(0, placeCashOrder(stationA, "ct-new").code(), "下新单");
        String fresh = dueDateOf("ct-new");
        assertNotNull(fresh, "新单应有应付日期");
        assertTrue(fresh.compareTo(before) < 0, "新单（15 天）应比老单（30 天）早到期：" + fresh + " vs " + before);

        // ③ 重算：把未结的老单按新账期改过来
        Api rec = post("/api/manager/customers/" + customer + "/credit-terms/recalculate", tokenA, "{}");
        assertEquals(0, rec.code(), "重算应成功: " + rec);
        assertEquals(1, rec.data().path("changedCount").asInt(),
                "只应重算 1 张（老单；新单本来就是新账期，不动它）实际=" + rec.data());
        assertEquals(fresh, dueDateOf("ct-old"), "重算后老单应与新单到期日一致");

        // ④ 留痕：每张被改动的单都要在 special_note 里留下旧→新
        String note = jdbc.queryForObject("SELECT special_note FROM orders WHERE idempotency_key='ct-old'",
                String.class);
        assertTrue(note != null && note.contains("[账期重算]") && note.contains(before),
                "重算必须留痕（含旧日期），实际=" + note);
    }

    @Test
    @DisplayName("现结客户没有可重算的挂账单：给业务错误，不静默返回 0（否则站长以为「点了没用」）")
    void recalculateOnImmediateSettlementIsRejected() {
        seed();

        Api rec = post("/api/manager/customers/" + customer + "/credit-terms/recalculate", tokenA, "{}");
        assertNotEquals(0, rec.code(), "现结客户重算应报错，实际=" + rec);
        assertTrue(rec.message() != null && rec.message().contains("现结"),
                "文案要指出原因是现结，实际=" + rec.message());
    }

    @Test
    @DisplayName("只放开「现结 / 月结」两种周期：传季结等未支持的形态要被**拒**，不静默按现结处理")
    void unsupportedSettlementCycleIsRejected() {
        seed();

        Api res = setTerms(tokenA, "{\"dueDays\":30,\"settlementCycle\":\"QUARTERLY\"}");
        assertNotEquals(0, res.code(), "未支持的结算周期应被拒，实际=" + res);
        assertTrue(termsOf(tokenA).path("dueDays").isNull() || termsOf(tokenA).path("dueDays").asInt(0) == 0,
                "被拒后不得留下任何账期配置（否则就是「界面说没设、实际设上了」）");
    }
}
