package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 站长看板 / 公告 / 综合搜索 / 意见反馈 —— 这四组端点在 2026-09-16 之前
 * <b>一条用例都没有</b>（见 {@code docs/audit/2026-09-16-场景测试矩阵.md} 的 C1）。
 *
 * <p>本类刻意只做「契约级」断言：把每个端点按真实角色走一遍，断言
 * ①业务码是 0（不是 500、不是 404）；②不该看见它的角色被拒。零覆盖的接口
 * 最常见的坏法就是"没测过所以没人发现它 500 / 404 / 忘了鉴权"，契约用例正好专治这个。</p>
 */
class DashboardNoticeSearchFeedbackIntegrationTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @CsvSource({"A, update", "B, update", "A, delete", "B, delete"})
    void systemAnnouncementsRemainUnchangedAfterAnyStationManagerWrite(String actor, String operation) {
        long stationA = createStation("合成公告站A"), stationB = createStation("合成公告站B");
        long managerA = createStaff("合成站长A", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("合成站长B", "STATION_MANAGER", stationB, 1);
        String token = actor.equals("A") ? staffToken(managerA, "STATION_MANAGER", stationA)
                : staffToken(managerB, "STATION_MANAGER", stationB);
        long id = insert("insert into notice(station_id,title,content,type,status) values(null,?,?,1,1)",
                "合成系统公告", "不可被站长覆盖的系统正文");
        var before = jdbc.queryForList("select * from notice order by id");
        Api response = operation.equals("update")
                ? put("/api/notices/"+id, token, "{\"title\":\"越权改写\",\"content\":\"越权正文\",\"type\":2,\"status\":0}")
                : delete("/api/notices/"+id, token);
        var after = jdbc.queryForList("select * from notice order by id");
        System.out.println("SYSTEM_NOTICE_WRITE actor="+actor+" operation="+operation+" response="+response.body()
                +" unchanged="+before.equals(after));
        assertAll(() -> assertEquals(1, response.code(), response.toString()),
                () -> assertEquals(before, after, "rejected writes must preserve the complete system announcement"));
        if (operation.equals("update")) {
            Api forged = put("/api/notices/"+id, token, "{\"stationId\":"+stationA+",\"title\":\"伪造归属\"}");
            assertEquals(1, forged.code(), forged.toString());
            assertEquals(before, jdbc.queryForList("select * from notice order by id"));
        }
    }

    @Test
    @DisplayName("看板端点：站长拿到数字，顾客被拒，匿名 401")
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

        // [2026-09-27] 原 4 条路径里的 /api/dashboard/today 与 /overview **已删**（产品批准）：
        // 两端小程序零真调用（前端包装函数 2026-09-19 就删了），且它们的 pendingOrders 只有
        // "按状态数"、与待分配列表的付款闸门口径分叉。删除记录见 DashboardController 的墓碑注释。
        // ⚠️ 那两条路径的 404 断言**不在这里** —— 它们和 /order-status、/order-trend 一样，
        // 属"删掉即不存在"，无需断言（不像 ManagerOrderController 那条，是要防止被加回来）。
        String[] paths = {
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
    @DisplayName("已删的两个看板端点：路径不存在（防止被加回来）")
    void removedDashboardEndpointsAreGone() {
        long station = createStation("看板站2");
        long manager = createStaff("看板站长2", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // [2026-09-27 删除] GET /today 与 /overview。判据：路由不存在 ⇒ body.code=404。
        // ⚠️ 断言看 **body 的 code**，不看 HTTP 状态 —— 本仓"路由不存在"走的是
        // Result.error(404)，HTTP 仍是 200（唯一例外是未认证的真 401）。
        for (String path : new String[]{"/api/dashboard/today", "/api/dashboard/overview"}) {
            Api res = get(path, mgr);
            assertEquals(404, res.code(), path + " 应已删除（路由不存在），实际=" + res);
        }
    }

    @Test
    @DisplayName("看板报表必须下发口径说明（否则站长会以为外派单算错了）")
    void dashboardReportCarriesScopeNote() {
        long station = createStation("口径站");
        long manager = createStaff("口径站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api res = get("/api/dashboard/report?range=today", mgr);
        assertEquals(0, res.code(), "看板报表应可读: " + res);

        // [2026-09-27] 报表按**结算站**统计（营收归谁），订单列表按**归属站**；
        // 站里一旦发生外派，两个数就不一样。没有这行说明，站长只能猜哪个是错的。
        String note = res.data().path("scopeNote").asText(null);
        assertNotNull(note, "报表必须下发 scopeNote —— 口径说明是这一页的一部分，不是可选项");
        assertTrue(note.contains("营收归属"),
                "说明要讲清'按营收归属统计'这一层，实际=" + note);
        assertTrue(note.contains("外派"),
                "说明要讲清外派单为什么不计入本站（那正是两个数不一样的唯一原因），实际=" + note);
        // ⚠️ 这是**站长界面上的正文**：不许出现 markdown 的星号（会原样渲染），也不许出现开发词
        assertTrue(!note.contains("*"), "画面文案不许带星号（会原样显示），实际=" + note);
        for (String devWord : new String[]{"接口", "后端", "字段", "落库", "端点"}) {
            assertTrue(!note.contains(devWord), "画面文案不许出现开发词「" + devWord + "」，实际=" + note);
        }
    }

    @Test
    @DisplayName("看板「订单已付款」必须下发口径说明（它不等于本站收到的钱）")
    void dashboardReportCarriesPaidAmountNote() {
        long station = createStation("已付款口径站");
        long manager = createStaff("已付款口径站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api res = get("/api/dashboard/report?range=today", mgr);
        assertEquals(0, res.code(), "看板报表应可读: " + res);

        // [2026-09-27 产品裁定 1.b，正本 docs/design/32] 这一格原名叫「已收款」，
        // 站长读它 = "本站收到了多少钱"，而算法是"已付款订单的金额合计"（payment_status = 2）。
        // 两者在没有外派、也没有预售时数值相同 ⇒ 一直没被看出来；
        // 实测反例：接单站显示 ¥168 而它名下**一条流水都没有**。
        // 拍板选的是"保留算法、改名 + 在画面上写清口径"，所以名字的解释必须**真的下发**，
        // 否则改名只是把误导从"读错名字"挪到"看不见名字旁边那句话"。
        String note = res.data().path("paidAmountNote").asText(null);
        assertNotNull(note, "报表必须下发 paidAmountNote —— 它替代的是原来的「已收款」这个名字");
        assertTrue(note.contains("不等于"),
                "说明必须点破「不等于本站收到的钱」，那正是原名的误导点，实际=" + note);
        assertTrue(note.contains("外派") || note.contains("接单站"),
                "说明要讲清外派单为什么不算本站收的钱，实际=" + note);
        assertTrue(!note.contains("*"), "画面文案不许带星号（会原样显示），实际=" + note);
        for (String devWord : new String[]{"接口", "后端", "字段", "落库", "端点"}) {
            assertTrue(!note.contains(devWord), "画面文案不许出现开发词「" + devWord + "」，实际=" + note);
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
