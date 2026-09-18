package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户反馈「匿名提交」（v46，2026-09-18 产品裁定）—— 真匿名，不是前端隐藏。
 *
 * <p><b>被测的判据只有一条</b>：匿名 = <b>站长不知道是谁</b>。所以断言全部落在
 * 「站长端 {@code GET /api/feedback/customers} 的响应里<b>根本拿不到</b> customerId / customerName」，
 * 而不是"页面上没显示"。前端隐藏是假的 —— 改个 setData、翻个接口就看见了。</p>
 *
 * <p>本类刻意同时覆盖<b>反向</b>：同站一条匿名 + 一条实名，实名那条的姓名照常下发。
 * 只测匿名会放过"一刀切全匿名"的实现（把所有记录的姓名都抹掉，
 * 站长从此谁都认不出，反馈也就没法闭环 —— 与"匿名"是两回事）。</p>
 *
 * <p>另外两条边界也在本类里钉住，因为它们最容易被"顺手改坏"：</p>
 * <ul>
 *   <li>顾客自己的 {@code GET /api/feedback/my} <b>不脱敏</b>（那是他自己的记录，
 *       一起脱敏会让「我的反馈」退化成一堆看不出是谁的记录）；</li>
 *   <li>{@code /api/feedback/customers} 仍然只对站长开放（配送员/客户都被拒）。</li>
 * </ul>
 *
 * <p>⚠️ 取记录一律<b>按 content 找</b>、不按位置：{@code feedback.create_time} 是
 * <b>秒级</b> datetime，同一用例里两条插入常常落进同一秒，{@code order by create_time desc}
 * 对并列行不保证稳定顺序 —— 按下标断言会偶发红（本仓对"偶发红"的代价已有记载）。</p>
 */
class AnonymousFeedbackIntegrationTest extends AbstractIntegrationTest {

    /** 从站长端列表里按 content 找一条，找不到直接失败（避免 null 传播成 NPE 掩盖真因）。 */
    private JsonNode findByContent(JsonNode list, String content) {
        for (JsonNode item : list) {
            if (content.equals(item.path("content").asText())) {
                return item;
            }
        }
        throw new AssertionError("站长端列表里找不到 content=" + content + " 的记录，实际返回: " + list);
    }

    @Test
    @DisplayName("匿名提交：落库 anonymous=1；站长端该条拿不到 customerId / customerName，但内容照常")
    void anonymousFeedbackHidesIdentityFromStation() {
        long station = createStation("匿名反馈站");
        long manager = createStaff("匿名反馈站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("匿名反馈客户", "anon-openid-1");
        createCustomerStationConfig(customer, station, 1);

        String cus = customerToken(customer);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 匿名提交：带上联系方式（客户自己愿意留的，见下方"照常下发"的断言）
        Api submitted = post("/api/feedback", cus,
                "{\"category\":\"投诉\",\"content\":\"匿名内容-配送员态度差\",\"contact\":\"13900000001\",\"anonymous\":true}");
        assertEquals(0, submitted.code(), "匿名提交应成功: " + submitted);

        // ① 落库真的表达了匿名
        assertEquals(1, intOf("SELECT anonymous FROM feedback WHERE content = ?", "匿名内容-配送员态度差"),
                "anonymous 必须落 1（v46 新增列）");
        // 身份仍然照旧写库：脱敏发生在**查询**层，不是在写入时抹掉 customer_id
        // （抹掉的话，站长端"不知道是谁"就变成了"系统也不知道是谁"，退款/回访都做不了）
        assertEquals(customer, longOf("SELECT customer_id FROM feedback WHERE content = ?", "匿名内容-配送员态度差"),
                "库里必须留着真实 customer_id —— 脱敏是查询层的事");

        Api all = get("/api/feedback/customers", mgr);
        assertEquals(0, all.code(), "站长应能读本站反馈: " + all);
        assertEquals(1, all.data().size(), "匿名记录不能因为脱敏而从列表里消失（本仓 §8.22 的形态）");

        JsonNode anon = findByContent(all.data(), "匿名内容-配送员态度差");
        assertTrue(anon.path("customerId").isNull(),
                "匿名记录的 customerId 必须是 null（后端 SQL 层置空，前端无从显示）: " + anon);
        assertTrue(anon.path("customerName").isNull(),
                "匿名记录的 customerName 必须是 null: " + anon);

        // ② 内容照常：匿名保护的是**身份**，不是**内容** —— 内容恰恰是站长要处理的东西
        assertEquals("投诉", anon.path("category").asText(), "分类照常下发");
        assertEquals("13900000001", anon.path("contact").asText(),
                "contact 照常下发：客户自己填了联系方式就是主动同意被联系，不由后端替他二次裁剪");
        assertFalse(anon.path("createTime").isNull(), "创建时间照常下发");
    }

    @Test
    @DisplayName("同站一条匿名 + 一条实名：只抹匿名那条，实名姓名照常（防一刀切全匿名）")
    void realNameFeedbackStillCarriesName() {
        long station = createStation("混合反馈站");
        long manager = createStaff("混合反馈站长", "STATION_MANAGER", station, 1);
        long anonCustomer = createCustomer("要匿名的客户", "anon-openid-2");
        long realCustomer = createCustomer("实名客户", "anon-openid-3");
        createCustomerStationConfig(anonCustomer, station, 1);
        createCustomerStationConfig(realCustomer, station, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);

        assertEquals(0, post("/api/feedback", customerToken(anonCustomer),
                "{\"category\":\"投诉\",\"content\":\"混合-匿名那条\",\"anonymous\":true}").code());
        assertEquals(0, post("/api/feedback", customerToken(realCustomer),
                "{\"category\":\"建议\",\"content\":\"混合-实名那条\",\"anonymous\":false}").code());

        Api all = get("/api/feedback/customers", mgr);
        assertEquals(0, all.code(), "站长应能读本站反馈: " + all);
        assertEquals(2, all.data().size(), "两条都要在列表里（匿名 ≠ 隐藏，只是不显示身份）");

        JsonNode anon = findByContent(all.data(), "混合-匿名那条");
        assertTrue(anon.path("customerId").isNull(), "匿名那条不返回客户号: " + anon);
        assertTrue(anon.path("customerName").isNull(), "匿名那条不返回姓名: " + anon);

        JsonNode real = findByContent(all.data(), "混合-实名那条");
        assertEquals(realCustomer, real.path("customerId").asLong(), "实名那条照常返回客户号: " + real);
        assertEquals("实名客户", real.path("customerName").asText(),
                "实名那条照常返回姓名 —— 一刀切全匿名会让所有反馈都无法闭环");
    }

    @Test
    @DisplayName("顾客自己的 /api/feedback/my 不脱敏：仍看得到自己的匿名记录（含 customerId）")
    void myEndpointStillReturnsOwnAnonymousFeedback() {
        long station = createStation("我的反馈站");
        long customer = createCustomer("自查客户", "anon-openid-4");
        long other = createCustomer("无关客户", "anon-openid-5");
        createCustomerStationConfig(customer, station, 1);

        String cus = customerToken(customer);
        assertEquals(0, post("/api/feedback", cus,
                "{\"category\":\"投诉\",\"content\":\"我的-匿名提交\",\"anonymous\":true}").code());

        Api mine = get("/api/feedback/my", cus);
        assertEquals(0, mine.code(), "客户应能读自己的反馈: " + mine);
        assertEquals(1, mine.data().size(), "匿名不能把自己的记录也藏起来（脱敏只对站长端）");
        JsonNode own = mine.data().get(0);
        assertEquals(customer, own.path("customerId").asLong(),
                "自己的记录照常带 customerId —— 脱了「我的反馈」会退化成一堆看不出归属的记录: " + own);
        assertTrue(own.path("anonymous").asBoolean(),
                "自己的记录照常带 anonymous 标记（他知道自己选了匿名）: " + own);

        assertEquals(0, get("/api/feedback/my", customerToken(other)).data().size(), "别人看不到我的反馈");
    }

    @Test
    @DisplayName("不传 anonymous（老客户端请求体）与显式传 null 都算实名")
    void missingAnonymousFieldMeansRealName() {
        long station = createStation("兼容反馈站");
        long manager = createStaff("兼容反馈站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("老客户端客户", "anon-openid-6");
        createCustomerStationConfig(customer, station, 1);

        String cus = customerToken(customer);
        // 老版本顾客端根本不发 anonymous —— 必须落 0（实名），否则升级后存量入口全变匿名
        assertEquals(0, post("/api/feedback", cus,
                "{\"category\":\"bug\",\"content\":\"兼容-没传字段\"}").code());
        // 显式传 null 同理（DTO 是 Boolean 包装类型，Controller 归一成 false 再落库）
        assertEquals(0, post("/api/feedback", cus,
                "{\"category\":\"bug\",\"content\":\"兼容-传了null\",\"anonymous\":null}").code());

        assertEquals(0, intOf("SELECT anonymous FROM feedback WHERE content = ?", "兼容-没传字段"),
                "没传 anonymous 必须落 0（实名）");
        assertEquals(0, intOf("SELECT anonymous FROM feedback WHERE content = ?", "兼容-传了null"),
                "anonymous=null 必须落 0（列是 NOT NULL DEFAULT 0）");

        Api all = get("/api/feedback/customers", staffToken(manager, "STATION_MANAGER", station));
        assertEquals(2, all.data().size());
        assertEquals("老客户端客户", findByContent(all.data(), "兼容-没传字段").path("customerName").asText(),
                "默认实名：姓名照常下发");
    }

    @Test
    @DisplayName("客户反馈汇总仍只对站长开放：配送员与顾客都被拒")
    void customersEndpointStillManagerOnly() {
        long station = createStation("权限反馈站");
        long manager = createStaff("权限反馈站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("权限反馈配送员", "DELIVERY", station, 1);
        long customer = createCustomer("权限反馈客户", "anon-openid-7");
        createCustomerStationConfig(customer, station, 1);

        assertEquals(0, post("/api/feedback", customerToken(customer),
                "{\"category\":\"投诉\",\"content\":\"权限-匿名\",\"anonymous\":true}").code());

        // 沿用既有口径：业务错误 HTTP 仍是 200，判据看 body 的 code（AGENTS §5）
        assertNotEquals(0, get("/api/feedback/customers", customerToken(customer)).code(),
                "客户不该看客户反馈汇总");
        assertNotEquals(0, get("/api/feedback/customers", staffToken(delivery, "DELIVERY", station)).code(),
                "配送员不该看客户反馈汇总（后端 @RequireRole 只有 STATION_MANAGER）");

        Api all = get("/api/feedback/customers", staffToken(manager, "STATION_MANAGER", station));
        assertEquals(0, all.code());
        assertEquals(1, all.data().size(), "站长仍能读到这条");
        assertEquals("权限-匿名", all.data().get(0).path("content").asText(), "内容照常下发");
    }
}
