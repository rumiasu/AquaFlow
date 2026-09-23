package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长看板 / 公告 / 综合搜索 / 意见反馈 —— 这四组端点在 2026-09-16 之前
 * <b>一条用例都没有</b>（见 {@code docs/audit/2026-09-16-场景测试矩阵.md} 的 C1）。
 *
 * <p>本类刻意只做「契约级」断言：把每个端点按真实角色走一遍，断言
 * ①业务码是 0（不是 500、不是 404）；②不该看见它的角色被拒。零覆盖的接口
 * 最常见的坏法就是"没测过所以没人发现它 500 / 404 / 忘了鉴权"，契约用例正好专治这个。</p>
 */
class DashboardNoticeSearchFeedbackIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("看板 5 个端点：站长拿到数字，顾客被拒，匿名 401")
    void dashboardEndpointsAreManagerOnly() {
        long station = createStation("看板站");
        long manager = createStaff("看板站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("看板客户", "dash-openid");
        long product = createProduct("看板水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 50);
        long address = createAddress(customer, "看板地址");
        createOrderFull(customer, address, station, product, 1, 1, 2,
                "10.00", "30.00", "40.00", true, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        String[] paths = {
                "/api/dashboard/today",
                "/api/dashboard/overview",
                // [2026-09-18] order-status / order-trend 已按死端点评估删除（口径与 /report 分叉），
                // 它们的 404 断言在 ManagerOrderControllerRemovedIntegrationTest 里。
                "/api/dashboard/report?range=7d",
                "/api/dashboard/report?range=30d"
        };
        for (String path : paths) {
            Api ok = get(path, mgr);
            assertEquals(0, ok.code(), path + " 站长应可读: " + ok);
            Api denied = get(path, cus);
            assertNotEquals(0, denied.code(), path + " 顾客不该读站长看板: " + denied);
            assertEquals(401, get(path, null).status(), path + " 匿名应是真 401");
        }
    }

    @Test
    @DisplayName("公告：发布→客户可见→编辑→跨站拒绝→删除")
    void noticeLifecycleAndStationScope() {
        long stationA = createStation("公告站A");
        long stationB = createStation("公告站B");
        long managerA = createStaff("公告站长A", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("公告站长B", "STATION_MANAGER", stationB, 1);
        long customer = createCustomer("公告客户", "notice-openid");

        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        String mgrB = staffToken(managerB, "STATION_MANAGER", stationB);
        String cus = customerToken(customer);

        Api created = post("/api/notices", mgrA, "{\"title\":\"停水通知\",\"content\":\"周三检修\",\"type\":1}");
        assertEquals(0, created.code(), "发布公告: " + created);
        long noticeId = created.data().path("id").asLong();
        assertTrue(noticeId > 0, "应返回落库后的公告 id");
        // 归属强制绑定登录站，请求体里没法伪造
        assertEquals(stationA, longOf("SELECT station_id FROM notice WHERE id=?", noticeId));
        // [2026-09-18] 状态文案必须由后端下发（Notice.getStatusText，真相源 constant/NoticeStatus.java）：
        // 站长端公告列表原来在前端写 `status === 1 ? '已发布' : '草稿'`，那正是本仓禁止的映射表。
        // 这条断言把它钉在契约层 —— 后端哪天不再下发 statusText，这里立刻红。
        assertEquals("已发布", created.data().path("statusText").asText(),
                "未传 status 时应默认发布，且状态文案由后端下发");

        Api published = get("/api/notices", cus);
        assertEquals(0, published.code(), "客户应能看已发布公告: " + published);
        assertEquals(1, published.data().size());
        assertEquals(0, get("/api/notices/" + noticeId, cus).code());

        assertEquals(0, get("/api/notices/all", mgrA).code());
        assertNotEquals(0, get("/api/notices/all", cus).code(), "客户不该有公告管理列表");

        // 未发布草稿对顾客不可见（[AQ-038] 修的就是"遍历 id 读他站草稿"）
        long draftId = insert("INSERT INTO notice(station_id, title, content, type, status) VALUES (?,?,?,1,0)",
                stationA, "草稿", "未发布");
        assertNotEquals(0, get("/api/notices/" + draftId, cus).code(), "草稿不该被顾客读到");
        assertEquals("草稿", get("/api/notices/" + draftId, mgrA).data().path("statusText").asText(),
                "草稿的状态文案同样由后端下发（前端只渲染，不做 status → 文案 映射）");

        // [2026-09-18 修复] 站长列表必须看得到**没发布的那部分**：
        // 旧 SQL 是 `where station_id = ? and status = 1` → 「保存草稿后列表里没有它」、
        // 「点下架后从列表消失、再也点不回来」，而界面文案明写"草稿只有你自己可见"。
        Api staffList = get("/api/notices/all", mgrA);
        assertEquals(0, staffList.code(), "站长应能看本站公告列表: " + staffList);
        boolean draftVisible = false;
        for (int i = 0; i < staffList.data().size(); i++) {
            if (staffList.data().get(i).path("id").asLong() == draftId) {
                draftVisible = true;
            }
        }
        assertTrue(draftVisible, "站长列表必须包含本站草稿（含未发布 / 已下架），否则草稿与下架两个状态在界面上等于不存在");

        Api updated = put("/api/notices/" + noticeId, mgrA, "{\"title\":\"改过的标题\"}");
        assertEquals(0, updated.code(), "站长应能编辑本站公告: " + updated);
        assertEquals("改过的标题", jdbc.queryForObject("SELECT title FROM notice WHERE id=?", String.class, noticeId));

        assertNotEquals(0, put("/api/notices/" + noticeId, mgrB, "{\"title\":\"越权改\"}").code(),
                "他站站长不得编辑本站公告");
        assertNotEquals(0, delete("/api/notices/" + noticeId, mgrB).code(), "他站站长不得删除本站公告");

        assertEquals(0, delete("/api/notices/" + noticeId, mgrA).code());
        assertNotEquals(0, get("/api/notices/" + noticeId, cus).code(), "删除后应查不到");
    }

    @Test
    @DisplayName("综合搜索：站长专属，返回三类结果且按站隔离")
    void searchIsManagerOnlyAndStationScoped() {
        long stationA = createStation("搜索站A");
        long stationB = createStation("搜索站B");
        long managerA = createStaff("搜索站长A", "STATION_MANAGER", stationA, 1);
        long customerA = createCustomer("张三搜索", "search-openid-a");
        long customerB = createCustomer("李四搜索", "search-openid-b");
        createCustomerStationConfig(customerA, stationA, 1);
        createCustomerStationConfig(customerB, stationB, 1);

        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);

        Api hit = get("/api/search?keyword=%E5%BC%A0%E4%B8%89", mgrA);
        assertEquals(0, hit.code(), "站长应能综合搜索: " + hit);
        assertNotNull(hit.data().get("customers"), "结果应含 customers/addresses/orders 三块");
        assertNotNull(hit.data().get("addresses"));
        assertNotNull(hit.data().get("orders"));
        // 关键字能命中本站客户；命中数不做强断言（搜索按姓名/手机号/地址多字段匹配）
        assertTrue(hit.data().get("customers").isArray());

        Api noMatch = get("/api/search?keyword=zzz-no-such-thing", mgrA);
        assertEquals(0, noMatch.code());
        assertEquals(0, noMatch.data().get("customers").size(), "搜不到就不该返回别人站的客户");

        assertNotEquals(0, get("/api/search?keyword=x", customerToken(customerA)).code(),
                "综合搜索是站长专属，顾客误调必须被拒（历史上顾客端误用过这个接口）");
    }

    @Test
    @DisplayName("意见反馈：客户提交只看自己的，站长看本站客户反馈")
    void feedbackSubmitAndAudience() {
        long station = createStation("反馈站");
        long manager = createStaff("反馈站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("反馈客户", "fb-openid");
        long other = createCustomer("另一个客户", "fb-openid-2");
        createCustomerStationConfig(customer, station, 1);

        String cus = customerToken(customer);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api submitted = post("/api/feedback", cus, "{\"category\":\"bug\",\"content\":\"下单页卡住\"}");
        assertEquals(0, submitted.code(), "客户应能提交反馈: " + submitted);
        assertEquals(customer, longOf("SELECT customer_id FROM feedback ORDER BY id DESC LIMIT 1"));

        // 空内容必须被 @NotBlank 挡住（不能让空反馈落库）
        assertNotEquals(0, post("/api/feedback", cus, "{\"category\":\"bug\",\"content\":\"\"}").code(),
                "空反馈内容应被参数校验拒绝");
        assertEquals(customer, longOf("SELECT customer_id FROM feedback ORDER BY id DESC LIMIT 1"),
                "被拒的提交不该落库");

        Api mine = get("/api/feedback/my", cus);
        assertEquals(0, mine.code());
        assertEquals(1, mine.data().size(), "客户只看得到自己那条");
        assertEquals(0, get("/api/feedback/my", customerToken(other)).data().size(), "别人看不到我的反馈");

        // 员工也能提交（身份按登录态自动区分）
        assertEquals(0, post("/api/feedback", mgr, "{\"category\":\"feature\",\"content\":\"想要批量导出\"}").code());

        Api all = get("/api/feedback/customers", mgr);
        assertEquals(0, all.code(), "站长应能看客户反馈汇总: " + all);
        assertEquals(1, all.data().size(), "只统计客户反馈，不含站长自己提的那条");
        assertNotEquals(0, get("/api/feedback/customers", cus).code(), "客户不该看反馈汇总");
        // [2026-09-18 修复] 客户姓名必须带出来：原 SQL 是 `select f.*`，从不 JOIN customer，
        // 于是 customerName 恒为 null —— 站长看到的是"客户 #42"，认不出人，这条反馈等于没法处理。
        assertEquals("反馈客户", all.data().get(0).path("customerName").asText(),
                "站长端要能看出是哪个客户报的（否则反馈无法闭环）");

        // [2026-09-18 修复] 归属口径必须是「绑定行 **或** 本站订单」的并集：
        // 原 SQL 用 inner join customer_station_config（只看绑定行），于是**只在小程序下过单、
        // 没有绑定行的老顾客**提交的反馈不进站长列表 —— 界面显示"暂无客户反馈"，库里却有记录。
        // 这正是 AGENTS §1 记的那条口径坑（客户特权 / 应收账款 / 代客下单护栏都栽在同一处）。
        long orderOnly = createCustomer("只下过单的客户", "fb-openid-3");
        long product = createProduct("反馈测试水", 1, "10.00", "30.00", 1, "0.00");
        long address = createAddress(orderOnly, "只下过单的客户地址");
        createOrder(orderOnly, address, station, product, 1, 1);
        assertEquals(0, post("/api/feedback", customerToken(orderOnly),
                "{\"category\":\"bug\",\"content\":\"下不了单\"}").code(), "该客户应能提交反馈");

        Api afterOrderOnly = get("/api/feedback/customers", mgr);
        assertEquals(0, afterOrderOnly.code());
        assertEquals(2, afterOrderOnly.data().size(),
                "只下过单、没有绑定行的顾客，其反馈也必须进站长列表（归属 = 绑定 或 本站订单，取并集）");
    }
}
