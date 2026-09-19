package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业身份（v50/v51，**开关打开**时的完整闭环）：大额提示 → 客户申请 → 站长审核 → 转企业身份 + 写企业资料；
 * 以及 v51 的触发口径。
 *
 * <p>产品口径（2026-09-19 第五批）：「企业的只看水，押金不算，水超过 30 桶就可以吧。也可以由水站设置」
 * 「桶数或金额，站长也可以自行设置范围，可以任选其一也可都选」——
 * 故本类把平台默认桶数压到 10 桶（省造数），并按站配置验证"只按金额 / 两项都设 / 两项都留空"三种形态。
 * 平台默认值 30 桶本身由 {@code EnterpriseIdentityDefaultThresholdIntegrationTest} 钉住。</p>
 *
 * <p>⚠️ 本类显式把开关打开（{@code @TestPropertySource}）；**关掉时**的行为（含配置端点也必须真拒绝）由
 * {@code EnterpriseIdentityDisabledIntegrationTest} 钉住 —— 两半合起来才是"随时可开关"。</p>
 */
@TestPropertySource(properties = {
        "app.enterprise.enabled=true",
        "app.enterprise.large-order-barrels=10",
        "app.enterprise.large-order-water-amount=0"
})
@DisplayName("企业身份（开关开）：只算水的触发口径 + 按站阈值 + 申请审核闭环")
class EnterpriseIdentityIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long otherStation;
    private long mgrA;
    private long mgrB;
    private long customer;
    private long address;
    private long water;
    private long device;
    private String cus;
    private String mgrToken;

    private void seed() {
        station = createStation("企业站");
        otherStation = createStation("别站");
        mgrA = createStaff("企业站长", "STATION_MANAGER", station, 1);
        mgrB = createStaff("别站站长", "STATION_MANAGER", otherStation, 1);
        customer = createCustomer("企业客户", "ent-openid");
        address = createAddress(customer, "企业小区1号");
        // category=1 是桶装水（唯一计入企业身份口径的品类）；押金故意给高，用来证明"押金不算"
        water = createProduct("桶装水18.9L", 1, "20.00", "50.00", 0, "0.00");
        // category 正本见 product 表注释：1 桶装水 / 2 瓶装水 / 3 饮水器。这里取 3=饮水器，
        // 用它证明"非桶装商品的金额也不算"（单价很高，若口径含非桶装商品，这一单早就该提示了）。
        device = createProduct("饮水机", 3, "300.00", "0.00", 0, "0.00");
        createInventoryFull(station, water, 500, 0, "0.00");
        createInventoryFull(station, device, 100, 0, "0.00");
        createInventoryFull(otherStation, water, 500, 0, "0.00");
        createCustomerStationConfig(customer, station, 0);
        createCustomerStationConfig(customer, otherStation, 0);
        cus = customerToken(customer);
        mgrToken = staffToken(mgrA, "STATION_MANAGER", station);
    }

    private JsonNode quote(long quoteStation, long productId, int qty) {
        Api res = post("/api/payments/quote", cus, "{\"stationId\":" + quoteStation + ",\"paymentMethod\":2,"
                + "\"addressId\":" + address + ",\"items\":[{\"productId\":" + productId
                + ",\"quantity\":" + qty + "}]}");
        assertEquals(0, res.code(), "报价应成功: " + res);
        return res.data();
    }

    private Api setConfig(long quoteStation, String barrels, String amount) {
        String token = quoteStation == station ? mgrToken : staffToken(mgrB, "STATION_MANAGER", otherStation);
        return put("/api/enterprise/manager/config", token,
                "{\"barrelThreshold\":" + barrels + ",\"waterAmountThreshold\":" + amount + "}");
    }

    private JsonNode getConfig(String token) {
        Api res = get("/api/enterprise/manager/config", token);
        assertEquals(0, res.code(), "读配置应成功: " + res);
        return res.data();
    }

    private Api apply(String company, String contact) {
        return post("/api/enterprise/applications", cus, "{\"stationId\":" + station
                + ",\"companyName\":\"" + company + "\",\"contactPerson\":\"" + contact + "\","
                + "\"contactPhone\":\"13800001234\",\"taxNo\":\"91310000MA1K3XYZ8Q\"}");
    }

    /* ==================== 触发口径（只算水） ==================== */

    @Test
    @DisplayName("只算桶装水：押金与饮水机金额都不进口径；达到平台默认桶数（本类压到 10）才提示")
    void onlyWaterBarrelsCount() {
        seed();
        // 9 桶水 = 水费 180、押金 450；再加 1 台 300 元饮水机 → 订单总额 930 元。
        // 若按"订单总额"或"含押金"判，这一单早就该提示了 —— 它不提示，正是"只看水"的证据。
        Api mixed = post("/api/payments/quote", cus, "{\"stationId\":" + station + ",\"paymentMethod\":2,"
                + "\"addressId\":" + address + ",\"items\":[{\"productId\":" + water + ",\"quantity\":9},"
                + "{\"productId\":" + device + ",\"quantity\":1}]}");
        assertEquals(0, mixed.code(), "报价应成功: " + mixed);
        // 把"总额确实很大"钉住：水费 180 + 押金 450 + 饮水机 300 = 930 元，
        // 若口径是订单总额（v50 的算法）或含押金，这一单必然提示 —— 它不提示才是"只看水"。
        assertTrue(new java.math.BigDecimal(mixed.data().path("totalAmount").asText("0"))
                        .compareTo(new java.math.BigDecimal("900")) > 0,
                "本单总额应远超 900（证明不提示不是因为单子小），实际="
                        + mixed.data().path("totalAmount").asText(""));
        assertTrue(mixed.data().path("enterpriseHint").isNull(),
                "9 桶水（含 450 押金 + 300 元饮水机）不该提示：口径只数桶装水且不含押金");

        // 10 桶 = 达到阈值（阈值语义是"达到即提示"）
        JsonNode ten = quote(station, water, 10);
        assertFalse(ten.path("enterpriseHint").isNull(), "10 桶应达到阈值并提示");
        assertTrue(ten.path("enterpriseHint").asText("").contains("10 桶"),
                "文案要报本单自己的桶数（不透露阈值），实际=" + ten.path("enterpriseHint").asText(""));
    }

    @Test
    @DisplayName("按站配金额口径：只按金额时桶数再多也不算，且押金仍不计入金额")
    void stationCanSwitchToWaterAmountOnly() {
        seed();
        assertEquals(0, setConfig(station, "null", "200.00").code(), "站长设本站只按水费 200 元");

        // 5 桶 = 水费 100（< 200）、押金 250 → 订单总额 350（≥ 200）。
        // 不提示 ⟹ 金额口径用的是**水费**而不是订单总额（押金确实不算）。
        JsonNode five = quote(station, water, 5);
        assertTrue(five.path("enterpriseHint").isNull(),
                "水费 100 未到 200，即便含押金总额 350 也不该提示");

        JsonNode fifteen = quote(station, water, 15);
        assertEquals(0, new java.math.BigDecimal("300.00")
                .compareTo(new java.math.BigDecimal(fifteen.path("waterAmount").asText("0"))));
        assertFalse(fifteen.path("enterpriseHint").isNull(), "水费 300 ≥ 200 应提示");
        assertTrue(fifteen.path("enterpriseHint").asText("").contains("水费"),
                "按金额命中时文案要说水费，实际=" + fifteen.path("enterpriseHint").asText(""));
    }

    @Test
    @DisplayName("两项都配 = 任一满足即提示（桶数或水费各自独立）")
    void bothCriteriaFireOnEitherOne() {
        seed();
        assertEquals(0, setConfig(station, "20", "1000.00").code(), "站长设 20 桶 或 水费 1000 元");

        assertTrue(quote(station, water, 15).path("enterpriseHint").isNull(),
                "15 桶 / 水费 300：两条都没到，不该提示");

        JsonNode byBarrels = quote(station, water, 25);
        assertFalse(byBarrels.path("enterpriseHint").isNull(), "25 桶 ≥ 20 应提示（桶数口径）");
        assertTrue(byBarrels.path("enterpriseHint").asText("").contains("25 桶"));

        JsonNode byAmount = quote(station, water, 60);
        assertFalse(byAmount.path("enterpriseHint").isNull(), "60 桶 → 水费 1200 ≥ 1000 应提示");
        assertTrue(byAmount.path("enterpriseHint").asText("").contains("水费"),
                "两条都命中时文案要同时报桶数与水费，实际=" + byAmount.path("enterpriseHint").asText(""));
    }

    @Test
    @DisplayName("两项都留空 = 本站不提示；这与「没配过 → 用平台默认」是两回事")
    void blankConfigSilencesOnlyThatStation() {
        seed();
        assertEquals(0, setConfig(station, "null", "null").code(), "两项留空应被接受");

        JsonNode huge = quote(station, water, 100);
        assertTrue(huge.path("enterpriseHint").isNull(),
                "本站两项都留空 = 站长明确表示不提示，100 桶也不该弹");

        JsonNode other = quote(otherStation, water, 10);
        assertFalse(other.path("enterpriseHint").isNull(),
                "别站没配过 → 用平台默认（本类 10 桶），同样 10 桶应当提示："
                        + "「没配过」与「配了空」语义不同，不能混");
    }

    @Test
    @DisplayName("阈值校验：0 或负数一律拒绝（0 桶意味着每单都弹，几乎必然是填错）")
    void rejectsNonPositiveThresholds() {
        seed();
        assertNotEquals(0, setConfig(station, "0", "null").code(), "桶数阈值 0 应被拒");
        assertNotEquals(0, setConfig(station, "null", "0").code(), "金额阈值 0 应被拒");
        assertNotEquals(0, setConfig(station, "-5", "null").code(), "负数应被拒");
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_enterprise_config WHERE station_id=?", station),
                "被拒的配置不得留痕");
    }

    @Test
    @DisplayName("配置回填：没配过时下发平台默认并标记 usingDefault；配过之后按配置回填")
    void configIsEchoedBackForTheStation() {
        seed();
        JsonNode initial = getConfig(mgrToken);
        assertTrue(initial.path("enabled").asBoolean(), "开关开着要下发 enabled=true（前端据此决定是否显示这一块）");
        assertTrue(initial.path("usingDefault").asBoolean(), "没配过时应标记 usingDefault");
        assertEquals(10, initial.path("barrelThreshold").asInt(), "没配过时回填平台默认桶数");
        // ⚠️ 平台默认的金额口径是"关闭"（配置值 0），对外必须表现为 **null**：
        // 下发 0 会让站长端把它回填进输入框（显示"水费达到 0 元"），一保存又被"必须为正"拒掉。
        assertTrue(initial.path("waterAmountThreshold").isNull(),
                "金额口径未启用时下发 null，不是 0");
        assertTrue(initial.path("defaultWaterAmount").isNull(),
                "平台默认的金额阈值未启用时同样下发 null，实际=" + initial.path("defaultWaterAmount"));

        assertEquals(0, setConfig(station, "8", "500.00").code());
        JsonNode after = getConfig(mgrToken);
        assertFalse(after.path("usingDefault").asBoolean(), "配过之后不该再说自己在用默认");
        assertEquals(8, after.path("barrelThreshold").asInt());
        assertEquals(0, new java.math.BigDecimal("500.00")
                .compareTo(new java.math.BigDecimal(after.path("waterAmountThreshold").asText())));
    }

    /* ==================== 申请与审核闭环 ==================== */

    @Test
    @DisplayName("客户申请 → 站长待审可见 → 通过后转企业身份并写企业资料；重复申请幂等")
    void applyThenApproveTurnsCustomerIntoEnterprise() {
        seed();
        assertEquals(1, intOf("SELECT customer_type FROM customer WHERE id=?", customer), "申请前是个人");

        Api first = apply("某某科技有限公司", "张经理");
        assertEquals(0, first.code(), "提交申请应成功: " + first);
        long applyId = longOf("SELECT id FROM customer_enterprise_apply WHERE customer_id=?", customer);
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM customer_enterprise_apply WHERE id=?",
                String.class, applyId));

        // 幂等：同站再提一次不产生第二条待审
        assertEquals(0, apply("某某科技有限公司", "张经理").code(), "重复提交应幂等返回");
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_enterprise_apply WHERE customer_id=?", customer),
                "同一站不应出现第二条申请");

        // 站长待审列表看得到
        JsonNode pending = get("/api/enterprise/manager/applications", mgrToken).data();
        assertEquals(1, pending.size(), "本站应有 1 条待审");
        assertEquals("待审核", pending.get(0).path("statusText").asText(), "状态文案由后端下发");
        assertEquals("某某科技有限公司", pending.get(0).path("companyName").asText());

        // 审核通过 → 客户转企业 + 企业资料落库
        assertEquals(0, put("/api/enterprise/manager/applications/" + applyId, mgrToken,
                "{\"approve\":true,\"note\":\"已核对营业执照\"}").code(), "通过应成功");
        assertEquals(2, intOf("SELECT customer_type FROM customer WHERE id=?", customer), "通过后应是企业身份");
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM customer_enterprise_apply WHERE id=?",
                String.class, applyId));
        assertEquals("某某科技有限公司", jdbc.queryForObject(
                "SELECT company_name FROM company_info WHERE customer_id=?", String.class, customer),
                "企业资料要写进 company_info");
        assertEquals("张经理", jdbc.queryForObject(
                "SELECT contact_person FROM company_info WHERE customer_id=?", String.class, customer));

        // 已经是企业身份 → 再报大额也不再提示（提示只针对"还是个人"的客户）
        assertTrue(quote(station, water, 50).path("enterpriseHint").isNull(), "企业身份客户不再提示申请");

        // 同一个申请不能被审第二次（CAS）
        assertNotEquals(0, put("/api/enterprise/manager/applications/" + applyId, mgrToken,
                "{\"approve\":true}").code(), "已审核的申请不得再被处理");
    }

    @Test
    @DisplayName("我的申请：客户能查到自己在本站的申请，别站查不到（下单页靠它判断「已申请过、别再弹窗」）")
    void customerCanQueryOwnApplication() {
        seed();
        assertEquals(0, get("/api/enterprise/applications/my?stationId=" + station, cus).data().size(),
                "还没申请时是空列表");

        assertEquals(0, apply("查询测试公司", "赵经理").code(), "提交申请应成功");
        JsonNode mine = get("/api/enterprise/applications/my?stationId=" + station, cus).data();
        assertEquals(1, mine.size(), "应能查到刚提交的那一条");
        assertEquals("PENDING", mine.get(0).path("status").asText(),
                "状态原值必须是 PENDING —— 顾客端下单页正是按它判断「已经申请过」的");
        assertEquals("待审核", mine.get(0).path("statusText").asText(), "状态文案由后端下发");
        assertEquals("查询测试公司", mine.get(0).path("companyName").asText());
        assertFalse(mine.get(0).path("applyTime").isNull(),
                "applyTime 不能为空：下单页要拿它做展示，站长端列表也要显示申请时间");

        assertEquals(0, get("/api/enterprise/applications/my?stationId=" + otherStation, cus).data().size(),
                "申请按站隔离：别站不该有我的申请");
    }

    @Test
    @DisplayName("越权：别站站长看不到、也不能审本站的申请（不区分「不存在」与「不是本站的」）")
    void otherStationCannotSeeOrReview() {
        seed();
        assertEquals(0, apply("越权测试公司", "李经理").code(), "提交申请应成功");
        long applyId = longOf("SELECT id FROM customer_enterprise_apply WHERE customer_id=?", customer);
        String otherToken = staffToken(mgrB, "STATION_MANAGER", otherStation);

        assertEquals(0, get("/api/enterprise/manager/applications", otherToken).data().size(),
                "别站的待审列表里不该有本站的申请");
        assertNotEquals(0, put("/api/enterprise/manager/applications/" + applyId, otherToken,
                "{\"approve\":true}").code(), "别站站长不得审核本站的申请");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM customer_enterprise_apply WHERE id=?",
                String.class, applyId), "被拒后申请状态必须原样");
        assertEquals(1, intOf("SELECT customer_type FROM customer WHERE id=?", customer), "客户身份不得被改动");
    }

    @Test
    @DisplayName("驳回：申请置已驳回，客户身份不动（可以再次提交）")
    void rejectKeepsCustomerAsIndividual() {
        seed();
        assertEquals(0, apply("会被驳回的公司", "王经理").code(), "提交申请应成功");
        long applyId = longOf("SELECT id FROM customer_enterprise_apply WHERE customer_id=?", customer);

        assertEquals(0, put("/api/enterprise/manager/applications/" + applyId, mgrToken,
                "{\"approve\":false,\"note\":\"资料不全\"}").code(), "驳回应成功");
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM customer_enterprise_apply WHERE id=?",
                String.class, applyId));
        assertEquals(1, intOf("SELECT customer_type FROM customer WHERE id=?", customer), "驳回不得改客户身份");
        assertEquals(0, intOf("SELECT COUNT(*) FROM company_info WHERE customer_id=?", customer),
                "驳回不得写企业资料");

        // 驳回后可以再申请
        assertEquals(0, apply("会被驳回的公司", "王经理").code(), "驳回后应可再次申请");
        assertNotNull(jdbc.queryForObject("SELECT status FROM customer_enterprise_apply WHERE id="
                + "(SELECT MAX(id) FROM customer_enterprise_apply WHERE customer_id=?)", String.class, customer));
    }
}
