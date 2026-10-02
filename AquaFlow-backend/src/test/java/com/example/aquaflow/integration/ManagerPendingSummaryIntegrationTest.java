package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.service.BarrelBusinessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长「待办汇总（按紧急程度分级）」契约 —— {@code GET /api/manager/pending-summary}。
 *
 * <p>2026-09-19 新增（起因：别人递过来的申请没有任何提醒，站长只能自己一页页翻）。</p>
 *
 * <p>本类刻意只断言**契约与分级判据**，不去重算每一项的业务口径 —— 那些口径由各自模块的
 * 用例守着（本端点只是把它们的读数搬过来）。这里要防的是三件事：
 * ① 端点的分级字段（{@code level}）与 {@code p0Total} 的语义被改坏 —— 它是"红点亮不亮"的唯一依据；
 * ② 权限被放开（待办里含本站欠款与人事申请，跨站可见等于漏经营底细）；
 * ③ 空库时炸掉（新库、刚建站、真的没事做，这三种情况都会走到）。</p>
 */
@TestPropertySource(properties = {
        "aquaflow.barrel.independent-rights-enabled=true",
        "aquaflow.barrel.maintenance-enabled=false",
        "aquaflow.pending.level-overrides="
})
class ManagerPendingSummaryIntegrationTest extends AbstractIntegrationTest {

    // 2026-10-02：仅枚举长度相等挡不住“漏一项、重复另一项”或错级别；
    // 独立列出业务契约，按实际 HTTP 返回检查唯一键、完整目录与默认分级。
    private static final Map<String, List<String>> EXPECTED_KEYS_BY_LEVEL = Map.of(
            "P0", List.of("pendingAssign", "waitingStock", "pendingTransfer", "customerCancel",
                    "stationCancel", "directedIncoming", "barrelReturn"),
            "P1", List.of("pendingPayment", "overdueReceivable", "staffBinding", "enterpriseApply",
                    "draftPayroll", "interStationUnsettled", "returnRefund", "recoverySend",
                    "recoveryReceive", "barrelHandover", "barrelDispute"),
            "P2", List.of("poolClaimable", "costNotFilled", "barrelException", "operationAlert"));

    @Autowired
    private BarrelBusinessPolicy barrelBusinessPolicy;

    /** HTTP 契约包括未知读数；不能把 null 强转成 0 后当作已经核对。 */
    private static void assertSummaryContract(Api res) {
        assertEquals(0, res.code(), "汇总应成功返回: " + res);
        assertTrue(res.data().path("items").isArray(), "必须下发条目数组");
        assertTrue(res.data().path("complete").isBoolean(), "complete 必须明确表示是否全部核对");
        assertTrue(res.data().path("p0Total").isIntegralNumber(), "必须明确下发整数红点计数，缺值不能默认为 0");
        Set<String> actual = new HashSet<>();
        Set<String> expected = new HashSet<>();
        EXPECTED_KEYS_BY_LEVEL.values().forEach(expected::addAll);
        int nonzeroP0 = 0;
        boolean allAvailable = true;
        for (com.fasterxml.jackson.databind.JsonNode row : res.data().path("items")) {
            String key = row.path("key").asText();
            assertTrue(actual.add(key), "条目键不得重复: " + key);
            assertTrue(expected.contains(key), "不得静默加入未登记条目: " + key);
            assertTrue(row.path("label").isTextual() && !row.path("label").asText().isBlank(),
                    "条目必须有服务提供的可读标签: " + key);
            assertTrue(row.path("available").isBoolean(), "每项必须声明 available: " + key);
            if (row.path("available").asBoolean()) {
                assertTrue(row.path("count").isIntegralNumber() && row.path("count").asInt() >= 0,
                        "已核对项必须有非负整数计数: " + key);
                if ("P0".equals(row.path("level").asText()) && row.path("count").asInt() > 0) nonzeroP0++;
            } else {
                allAvailable = false;
                assertTrue(row.has("count") && row.path("count").isNull(),
                        "未核对项必须保留未知，不能显示 0: " + key);
            }
        }
        assertEquals(expected, actual, "目录必须完整且唯一，不能仅比较条目件数");
        EXPECTED_KEYS_BY_LEVEL.forEach((level, keys) -> keys.forEach(key ->
                assertEquals(level, itemOf(res, key).path("level").asText(), key + " 的默认业务级别漂移")));
        assertEquals(allAvailable, res.data().path("complete").asBoolean(), "complete 必须与实际核对范围一致");
        assertEquals(nonzeroP0, res.data().path("p0Total").asInt(), "红点只数已核对的非零 P0 项，不加总订单数");
    }

    /** 取 items 里某个 key 的那一项；不存在返回 null。 */
    private static com.fasterxml.jackson.databind.JsonNode itemOf(Api res, String key) {
        for (com.fasterxml.jackson.databind.JsonNode it : res.data().path("items")) {
            if (key.equals(it.path("key").asText())) {
                return it;
            }
        }
        return null;
    }

    @Test
    @DisplayName("待办汇总：空库不炸、每项都带 level、p0Total 只数 P0 的非零项")
    void summaryContractAndGrading() {
        long station = createStation("待办空站");
        long manager = createStaff("待办站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // —— 空库：不该炸，且 p0Total 必须是 0 ——
        Api empty = get("/api/manager/pending-summary", mgr);
        assertSummaryContract(empty);
        assertEquals(0, empty.code(), "空库时应正常返回: " + empty);
        assertNotNull(empty.data().path("items"), "必须下发 items");
        assertEquals(0, empty.data().path("p0Total").asInt(), "没有任何待办时 p0Total 应为 0");

        // —— 每一项都必须带 level，且 level 只能是 P0/P1/P2 ——
        // 这是"红点亮不亮"的依据：漏了 level 前端就只能靠猜，分级会静默失效。
        assertTrue(empty.data().path("items").size() > 0, "应当下发条目（含 count=0 的）");
        for (com.fasterxml.jackson.databind.JsonNode it : empty.data().path("items")) {
            String level = it.path("level").asText();
            assertTrue("P0".equals(level) || "P1".equals(level) || "P2".equals(level),
                    "level 只能是 P0/P1/P2，实际=" + level + "（key=" + it.path("key").asText() + "）");
            assertNotNull(it.path("label").asText(), "label 必须由后端下发，前端不自带映射表");
        }

        assertTrue(empty.data().path("complete").asBoolean(), "已安装结构的空站应全部核对完成");

        // 空库时每一项的 count 都是 0（否则 p0Total 的语义就不成立了）
        for (com.fasterxml.jackson.databind.JsonNode it : empty.data().path("items")) {
            assertTrue(it.path("available").asBoolean(), "空站已核对项仍应 available=true: " + it.path("key").asText());
            assertEquals(0, it.path("count").asInt(),
                    "空库时 count 应为 0: " + it.path("key").asText());
        }

        // —— 权限：顾客不能读（待办含本站欠款与人事申请） ——
        long customer = createCustomer("待办客户", "pending-openid");
        assertNotEquals(0, get("/api/manager/pending-summary", customerToken(customer)).code(),
                "顾客不该读站长待办");

        // —— 跨站隔离：这是本用例真正要防的 ——
        // ⚠️ 不能拿"没绑定水站的账号"来测：`AuthInterceptor` 对 staff token **以库内 stationId 覆盖
        // token 里那个**（AQ-024 回查，防调站后旧 token 继续操作原站），所以 token 里塞 null 是无效的。
        // 正确判据是：站长**只可能**看到自己站的数据，连"客户可编造的 stationId 参数"也不行。
        long otherStation = createStation("待办别站");
        long otherManager = createStaff("他站站长", "STATION_MANAGER", otherStation, 1);
        String otherMgr = staffToken(otherManager, "STATION_MANAGER", otherStation);
        for (com.fasterxml.jackson.databind.JsonNode it : get("/api/manager/pending-summary", otherMgr)
                .data().path("items")) {
            assertEquals(0, it.path("count").asInt(),
                    "别站不应看到本站的待办: " + it.path("key").asText());
        }
        // 伪造 stationId 参数：归属只认登录态，传了也必须被忽略（不构成越权读取的通道）
        Api forged = get("/api/manager/pending-summary?stationId=" + otherStation, mgr);
        assertEquals(0, forged.code(), "多了个未知参数不该报错（后端不读它）");
        assertNotEquals(otherStation, station, "前置：两个站确实是不同的站");
    }

    @Test
    @DisplayName("待办汇总：有货到付款的待分配单 → 待分配订单+P0，红点判据随之变化")
    void pendingAssignDrivesP0() {
        long station = createStation("待办有单站");
        long manager = createStaff("待办有单站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("待办有单客户", "pending-openid-2");
        long product = createProduct("待办测试水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "待办地址");
        createInventory(station, product, 50);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        int before = get("/api/manager/pending-summary", mgr).data().path("p0Total").asInt();

        long order = createOrderFull(customer, address, station, product, 1, 2, 2,
                "10.00", "30.00", "40.00", true, 1);
        // 夹具只插 (status, payment_status, payment_method) 三列，**不写 delivery_staff_id**
        // → 配送员为 NULL = 未分配；再确认一次归属字段，避免夹具将来改动让本用例静默失效
        assertEquals(station, longOf("SELECT station_id FROM orders WHERE id=?", order));
        assertEquals(station, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order));
        assertEquals(2, intOf("SELECT payment_method FROM orders WHERE id=?", order),
                "现金单（payment_method=2）是进站长视野的判据之一");
        assertTrue(jdbc.queryForObject("SELECT delivery_staff_id IS NULL FROM orders WHERE id=?",
                Boolean.class, order), "前置：该单必须处于未分配状态");

        Api after = get("/api/manager/pending-summary", mgr);
        assertSummaryContract(after);
        assertEquals(0, after.code(), "有单时也应正常返回: " + after);

        com.fasterxml.jackson.databind.JsonNode assign = itemOf(after, "pendingAssign");
        assertNotNull(assign, "必须下发 pendingAssign 这一项");
        assertEquals(1, assign.path("count").asInt(), "应数到 1 条待分配");
        assertEquals("P0", assign.path("level").asText(),
                "待分配必须归 P0 —— 客户付了钱在等水，不处理就卡住今天的配送");

        assertTrue(after.data().path("p0Total").asInt() > before,
                "多了一条 P0 待办，p0Total 必须变大（它是红点判据）");
    }

    @Test
    @DisplayName("待办汇总：新凭据读取不可用保留未知，恢复后重新核对，不伪装为零")
    void unavailableBusinessCountsStayUnknownUntilRetry() {
        long station = createStation("未核对责任站");
        long manager = createStaff("责任站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        Object installed = ReflectionTestUtils.getField(barrelBusinessPolicy, "schemaInstalled");
        assertEquals(Boolean.TRUE, installed, "前置：测试目标须安装新业务结构");
        try {
            // 只模拟读数不可用；不改真实表，不切换业务写模型，finally 恢复共享 Bean。
            ReflectionTestUtils.setField(barrelBusinessPolicy, "schemaInstalled", false);
            Api unavailable = get("/api/manager/pending-summary", mgr);
            assertSummaryContract(unavailable);
            assertTrue(!unavailable.data().path("complete").asBoolean(), "部分未知不能说全部完成核对");
            for (String key : List.of("returnRefund", "recoverySend", "recoveryReceive", "barrelHandover", "barrelDispute")) {
                assertTrue(!itemOf(unavailable, key).path("available").asBoolean(), key + " 应明确未核对");
                assertTrue(itemOf(unavailable, key).path("count").isNull(), key + " 不得转换成 0");
            }
            assertTrue(itemOf(unavailable, "waitingStock").path("available").asBoolean(), "可用的库存责任仍须正常核对");
        } finally {
            ReflectionTestUtils.setField(barrelBusinessPolicy, "schemaInstalled", installed);
        }
        Api retry = get("/api/manager/pending-summary", mgr);
        assertSummaryContract(retry);
        assertTrue(retry.data().path("complete").asBoolean(), "恢复后重新核对完成");
        for (String key : EXPECTED_KEYS_BY_LEVEL.get("P1")) {
            assertTrue(itemOf(retry, key).path("available").asBoolean(), "恢复后可用性须重新计算: " + key);
        }
    }
}
