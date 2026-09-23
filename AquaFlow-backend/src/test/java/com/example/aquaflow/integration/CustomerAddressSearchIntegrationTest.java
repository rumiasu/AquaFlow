package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长端「按地址搜客户」的端到端口径（2026-09-18）。
 *
 * <p><b>产品口径（站长原话转述）</b>：站长搜客户"更注重地址，最多记个姓氏，不关注客户名字 ——
 * 地址其实更能指代人"；习惯<b>缩写</b>（「阳光小区8栋1单元301」打成「阳光81301」）、
 * 不爱打空格，且中文数字与阿拉伯数字混用（「八栋」/「8栋」）。所以<b>不做强子串匹配</b>，
 * 而是归一化后按相关性打分（算法与阈值本体的用例在
 * {@code util/CustomerSearchMatcherTest}）。</p>
 *
 * <p>本类只盯"接口层"的七件事，每件都对应一条会真实发生的误用：</p>
 * <ol>
 *   <li>缩写能命中地址；</li>
 *   <li>中文数字与阿拉伯数字等价；</li>
 *   <li>只记得姓氏也能命中（多个时按相关性排，姓名命中在前）；</li>
 *   <li>电话片段仍能命中（新口径不许把老能力弄丢）；</li>
 *   <li>地址关键字能命中；</li>
 *   <li>他站客户不出现（归属判据 = 绑定 ∪ 本站订单，与 {@code countCustomerOfStation} 同源）；</li>
 *   <li>无匹配返回空数组（既不报错，也不"返回全部"）。</li>
 * </ol>
 *
 * <p>另有两条"实现是否真的复用"的契约：三个入口（客户列表 / 代客下单选择器 / 综合搜索）
 * 对同一关键字必须给同一个人；以及"不筛"时的首屏语义（返回最近建档的若干条）不许被改成空列表。</p>
 */
@DisplayName("站长端客户搜索 · 地址参与 / 缩写与数字混用 / 相关性排序 / 站隔离")
class CustomerAddressSearchIntegrationTest extends AbstractIntegrationTest {

    /** 代客下单的客户选择器（口径：绑定 ∪ 本站订单，含"刚建档没下过单"的新客户） */
    private static final String PICKER = "/api/manager/order-assist/customers";

    /** 站长端客户列表（口径：orders 驱动的客户画像） */
    private static final String CUSTOMER_LIST = "/api/customers";

    private static String q(String keyword) {
        return "?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    private static boolean containsId(JsonNode array, long id) {
        for (JsonNode node : array) {
            if (node.path("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfId(JsonNode array, long id) {
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).path("id").asLong() == id) {
                return i;
            }
        }
        return -1;
    }

    /** 建"有本站订单"的客户：客户列表（orders 驱动）与选择器都能看到他 */
    private long customerWithOrder(long stationId, String name, String openid, String addressDetail) {
        long customer = createCustomer(name, openid);
        long address = createAddress(customer, addressDetail);
        createOrder(customer, address, stationId, 0L, 1, 1);
        return customer;
    }

    /** 建"只绑定、没下单"的客户：选择器看得到、客户列表（画像口径）看不到 */
    private long boundOnlyCustomer(long stationId, String name, String openid, String addressDetail) {
        long customer = createCustomer(name, openid);
        createAddress(customer, addressDetail);
        createCustomerStationConfig(customer, stationId, 0);
        return customer;
    }

    // ==================== ①~⑤ 地址参与搜索 ====================

    @Test
    @DisplayName("① 缩写命中：搜『阳光81301』命中地址『阳光小区8栋1单元301』")
    void abbreviationMatchesAddress() {
        long station = createStation("搜索站A");
        long manager = createStaff("搜索站长A", "STATION_MANAGER", station, 1);
        long zhang = customerWithOrder(station, "张三", "addr-a1", "阳光小区8栋1单元301");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api hit = get(PICKER + q("阳光81301"), mgr);
        assertEquals(0, hit.code(), "搜索应成功: " + hit);
        assertEquals(1, hit.data().size(), "缩写应只命中那一个客户: " + hit);
        assertTrue(containsId(hit.data(), zhang), "『阳光81301』必须命中『阳光小区8栋1单元301』: " + hit);
        // 选择器要带出地址：站长靠地址确认"是不是这家人"
        assertEquals("阳光小区8栋1单元301", hit.data().get(0).path("addressText").asText(),
                "命中项必须带地址文本: " + hit);
    }

    @Test
    @DisplayName("② 数字混用：『阳光八栋』与『阳光8栋』命中同一个地址")
    void chineseAndArabicNumeralsMatchSameAddress() {
        long station = createStation("搜索站B");
        long manager = createStaff("搜索站长B", "STATION_MANAGER", station, 1);
        long zhang = customerWithOrder(station, "张三", "addr-b1", "阳光小区8栋1单元301");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        for (String keyword : List.of("阳光八栋", "阳光8栋", "阳光小区八栋一单元三零一")) {
            Api hit = get(PICKER + q(keyword), mgr);
            assertEquals(0, hit.code(), "搜「" + keyword + "」应成功: " + hit);
            assertTrue(containsId(hit.data(), zhang), "搜「" + keyword + "」应命中同一个客户: " + hit);
        }
        // 汉字与数字混着写也一样
        Api mixed = get(PICKER + q("阳光8栋一单元301"), mgr);
        assertTrue(containsId(mixed.data(), zhang), "汉字/阿拉伯混用应命中: " + mixed);
    }

    @Test
    @DisplayName("③ 只记得姓氏：搜『王』命中多个，姓名命中排在'仅地址命中'之前")
    void surnameQueryMatchesAndRanksNameHitFirst() {
        long station = createStation("搜索站C");
        long manager = createStaff("搜索站长C", "STATION_MANAGER", station, 1);
        long wang = customerWithOrder(station, "王小明", "addr-c1", "水岸新城1栋2单元");
        long li = customerWithOrder(station, "李四", "addr-c2", "王府井小区3栋");
        long unrelated = customerWithOrder(station, "赵六", "addr-c3", "和平里5号楼");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api hit = get(PICKER + q("王"), mgr);
        assertEquals(0, hit.code(), "搜姓氏应成功: " + hit);
        assertEquals(2, hit.data().size(), "姓氏允许命中多个（王小明 与 地址含'王府井'的李四）: " + hit);
        assertTrue(containsId(hit.data(), wang) && containsId(hit.data(), li));
        assertFalse(containsId(hit.data(), unrelated), "毫不相关的客户不该被拉进来: " + hit);
        assertEquals(0, indexOfId(hit.data(), wang),
                "姓名命中（王小明）必须排在'仅地址命中'（王府井）之前: " + hit);
    }

    @Test
    @DisplayName("④ 电话片段仍能命中（新口径不许弄丢老能力）")
    void phoneFragmentStillMatches() {
        long station = createStation("搜索站D");
        long manager = createStaff("搜索站长D", "STATION_MANAGER", station, 1);
        long zhao = createCustomer("赵六", "addr-d1");
        // 直接改 customer.phone：造数的 createCustomer 不带电话；这里用 jdbc.update（insert() 走的是
        // 取自增主键的通道，UPDATE 拿不到 key 会抛异常）
        jdbc.update("UPDATE customer SET phone=? WHERE id=?", "13800138000", zhao);
        createAddress(zhao, "水岸新城");   // 地址里刻意不带数字，避免"电话命中"与"地址命中"混在一起
        createCustomerStationConfig(zhao, station, 0);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 造数前提：电话与绑定都必须真的落库，否则下面"搜不到"会被误读成搜索坏了
        assertEquals("13800138000",
                jdbc.queryForObject("SELECT phone FROM customer WHERE id=?", String.class, zhao),
                "造数前提：电话未落库");
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_station_config WHERE customer_id=? AND station_id=?",
                zhao, station), "造数前提：绑定行未落库");
        // 先把"这个客户在候选集里"证出来（按地址搜），再验电话搜
        assertTrue(containsId(get(PICKER + q("水岸"), mgr).data(), zhao),
                "该客户必须先是本站候选人（否则下面的电话用例测的是空集）");

        Api hit = get(PICKER + q("13800"), mgr);
        assertEquals(0, hit.code());
        assertTrue(containsId(hit.data(), zhao), "电话片段必须仍能命中: " + hit);
        // 全号也要能搜到
        assertTrue(containsId(get(PICKER + q("13800138000"), mgr).data(), zhao));
    }

    @Test
    @DisplayName("⑤ 地址关键字（不带门牌号）也能命中")
    void addressKeywordMatches() {
        long station = createStation("搜索站E");
        long manager = createStaff("搜索站长E", "STATION_MANAGER", station, 1);
        long zhang = customerWithOrder(station, "张三", "addr-e1", "阳光小区8栋1单元301");
        long other = customerWithOrder(station, "李四", "addr-e2", "月光花园2栋");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api hit = get(PICKER + q("阳光"), mgr);
        assertEquals(0, hit.code());
        assertTrue(containsId(hit.data(), zhang), "搜小区名应命中: " + hit);
        assertFalse(containsId(hit.data(), other), "别的小区不该被命中: " + hit);
    }

    // ==================== ⑥⑦ 边界 ====================

    @Test
    @DisplayName("⑥ 他站客户不出现在结果里（归属 = 绑定 ∪ 本站订单）")
    void otherStationCustomerIsInvisible() {
        long stationA = createStation("搜索站F");
        long stationB = createStation("搜索站G");
        long managerA = createStaff("搜索站长F", "STATION_MANAGER", stationA, 1);
        long mine = customerWithOrder(stationA, "本站客户", "addr-f1", "阳光小区8栋1单元301");
        // 他站客户：一个只有本站订单、一个只有绑定行，两种形态都不许漏出来
        long foreignOrder = customerWithOrder(stationB, "他站客户有单", "addr-f2", "阳光小区9栋1单元301");
        long foreignBound = boundOnlyCustomer(stationB, "他站客户仅绑定", "addr-f3", "阳光小区7栋1单元301");
        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);

        Api hit = get(PICKER + q("阳光81301"), mgrA);
        assertEquals(0, hit.code());
        assertTrue(containsId(hit.data(), mine), "本站客户应命中");
        assertFalse(containsId(hit.data(), foreignOrder), "他站客户（有单）不得出现: " + hit);
        assertFalse(containsId(hit.data(), foreignBound), "他站客户（仅绑定）不得出现: " + hit);

        // 综合搜索同样按站隔离
        Api search = get("/api/search" + q("阳光81301"), mgrA);
        assertEquals(0, search.code());
        assertFalse(containsId(search.data().get("customers"), foreignOrder),
                "综合搜索也不得返回他站客户: " + search);
    }

    @Test
    @DisplayName("⑦ 无匹配返回空数组：不报错，也不'返回全部'")
    void noMatchReturnsEmptyArray() {
        long station = createStation("搜索站H");
        long manager = createStaff("搜索站长H", "STATION_MANAGER", station, 1);
        customerWithOrder(station, "张三", "addr-h1", "阳光小区8栋1单元301");
        customerWithOrder(station, "李四", "addr-h2", "月光花园2栋");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api miss = get(PICKER + q("不存在的关键词xyz"), mgr);
        assertEquals(0, miss.code(), "搜不到不是错误: " + miss);
        assertTrue(miss.data().isArray(), "无匹配必须返回数组: " + miss);
        assertEquals(0, miss.data().size(), "无匹配必须返回空数组: " + miss);

        // 反向确认：同一次会话里"不筛"能拿到全部，所以上面的空数组确实是"没命中"而不是"接口坏了"
        Api all = get(PICKER, mgr);
        assertEquals(2, all.data().size(), "不带关键字应返回最近建档的若干条: " + all);

        // 客户列表同理：搜不到就得是空，不能把全站客户倒出来
        Api listMiss = get(CUSTOMER_LIST + q("不存在的关键词xyz"), mgr);
        assertEquals(0, listMiss.code());
        assertEquals(0, listMiss.data().size(), "客户列表搜不到也必须是空数组: " + listMiss);
    }

    // ==================== 复用与首屏语义 ====================

    @Test
    @DisplayName("三个入口复用同一实现：客户列表 / 代客下单选择器 / 综合搜索给同一个人")
    void allEntryPointsShareTheSameImplementation() {
        long station = createStation("搜索站I");
        long manager = createStaff("搜索站长I", "STATION_MANAGER", station, 1);
        long zhang = customerWithOrder(station, "张三", "addr-i1", "阳光小区8栋1单元301");
        long boundOnly = boundOnlyCustomer(station, "刚建档客户", "addr-i2", "阳光小区6栋1单元101");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 选择器：并集口径 → 连"刚建档没下过单"的新客户也搜得到（代客下单第一步的必要条件）
        Api picker = get(PICKER + q("阳光81301"), mgr);
        assertTrue(containsId(picker.data(), zhang));
        assertTrue(containsId(get(PICKER + q("阳光6101"), mgr).data(), boundOnly),
                "只绑定、没下过单的新客户也必须能被地址搜到: " + picker);

        // 客户列表：画像口径（orders 驱动）→ 有订单的那个在，新客户不在；并且带出地址供展示
        Api list = get(CUSTOMER_LIST + q("阳光81301"), mgr);
        assertEquals(0, list.code(), "客户列表带关键字应成功: " + list);
        assertTrue(containsId(list.data(), zhang), "客户列表也要按地址命中: " + list);
        assertEquals("阳光小区8栋1单元301", list.data().get(0).path("addressText").asText(),
                "命中项要带地址，否则站长看不出为什么命中: " + list);
        assertFalse(containsId(list.data(), boundOnly),
                "客户列表是 orders 驱动，没下过单的新客户本就不该出现（这是既有口径，不是本次改动）");

        // 综合搜索：customers 一块与选择器同源
        Api search = get("/api/search" + q("阳光81301"), mgr);
        assertEquals(0, search.code());
        assertNotNull(search.data().get("customers"));
        assertTrue(containsId(search.data().get("customers"), zhang), "综合搜索的客户块也要按地址命中: " + search);

        // 不带关键字时客户列表仍是全量（没有把老行为改成"必须给关键字"）
        Api listAll = get(CUSTOMER_LIST, mgr);
        assertEquals(1, listAll.data().size(), "不带关键字应返回全量本站客户（画像口径）: " + listAll);
    }

    @Test
    @DisplayName("首屏语义：选择器不带关键字返回最近建档的若干条（不能被改成空列表）")
    void blankKeywordKeepsRecentListSemantics() {
        long station = createStation("搜索站J");
        long manager = createStaff("搜索站长J", "STATION_MANAGER", station, 1);
        long customer = createCustomer("刚建档客户", "addr-j1");
        createCustomerStationConfig(customer, station, 0);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api blank = get(PICKER + "?keyword=", mgr);
        assertEquals(0, blank.code());
        assertTrue(containsId(blank.data(), customer),
                "关键字为空 = 不筛（代客下单页首屏依赖它），不能返回空列表: " + blank);
    }
}
