package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-14（台账 {@code docs/audit/项目健康度台账.md} §4 P2）零覆盖端点契约 · <b>站长侧两条</b>。
 *
 * <p>被钉住的两条端点：{@code GET /api/manager/payroll}（结算单列表）与
 * {@code GET /api/manager/earning-items/directions}（加项/扣项可选项）。
 * 补本用例之前 {@code src/test/**} 对前者<b>只有 POST 生成结算单</b>、对后者<b>一次都没有引用</b>
 * （零覆盖证据：按端点路径常量 grep 命中数 = 0，见交付报告）。</p>
 *
 * <p><b>为什么值得为"只读列表 / 字典"立用例</b>（AGENTS §8.22）：这两种端点的真实故障形态都是
 * <b>界面空白</b> ——</p>
 * <ul>
 *   <li>列表的跨站过滤写错 → 不抛异常，只返回<b>别站的</b>工资台账（越权知情，钱的事最容易出事）；
 *       反过来，站长身份被判空时也可能"静默返回空列表"，看起来像"这个月还没结算"；</li>
 *   <li>字典被顾客/配送员拿到，等于把权限闸门交给界面：前端拿它去建工资条目，而条目方向决定
 *       <b>从谁工资里扣钱</b>（{@code EarningItemDirection}，正本在常量里，前端禁止自带映射表）。</li>
 * </ul>
 *
 * <p>所以每条端点都刻意把四种形态分开断言：正例落库、失败可区分、合法空态、越权隔离。
 * 断言语义一律看 body {@code code}（业务错误 HTTP 仍是 200），只有"未认证"才是真 401。</p>
 */
@DisplayName("F-14 · 站长侧零覆盖只读端点（/api/manager/payroll、/earning-items/directions）")
class ManagerPayrollListAndDirectionsIntegrationTest extends AbstractIntegrationTest {

    private static final String PAYROLL = "/api/manager/payroll";

    private static final String DIRECTIONS = "/api/manager/earning-items/directions";

    private static final String ITEMS = "/api/manager/earning-items";

    /* ==================================================================
     * 端点 3：GET /api/manager/payroll（ManagerPayrollController#listPayrolls）
     * 本站结算单列表；站点取自登录态，limit 被夹到 [1,500]。
     * ================================================================== */

    @Test
    @DisplayName("payroll 列表正例：只回本站、逐条与库对账（含 limit 上下夹取）")
    void payrollListReturnsOwnStationRowsMatchingDb() {
        long stationA = createStation("F14 工资站A");
        long stationB = createStation("F14 工资站B");
        long mgrA = createStaff("F14 工资站长A", "STATION_MANAGER", stationA, 1);
        long riderA1 = createStaff("F14 工资骑手A1", "DELIVERY", stationA, 1);
        long riderA2 = createStaff("F14 工资骑手A2", "DELIVERY", stationA, 1);
        long riderB = createStaff("F14 工资骑手B", "DELIVERY", stationB, 1);

        long payrollA1 = insertPayroll("F14-PR-A1", stationA, riderA1, "2026-01-01", "2026-01-31", "85.50", 1);
        long payrollA2 = insertPayroll("F14-PR-A2", stationA, riderA2, "2026-02-01", "2026-02-28", "120.00", 2);
        long payrollB1 = insertPayroll("F14-PR-B1", stationB, riderB, "2026-01-01", "2026-01-31", "9.99", 1);

        // 落库锚点：假设下面断言都成立，也要能证明这些行真的在库里、站别真的写对了
        assertEquals(2, intOf("SELECT COUNT(*) FROM staff_payroll WHERE station_id=?", stationA),
                "落库结果：A 站应有 2 张结算单");
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff_payroll WHERE station_id=?", stationB),
                "落库结果：B 站应有 1 张结算单");

        String tokenA = staffToken(mgrA, "STATION_MANAGER", stationA);
        Api res = get(PAYROLL, tokenA);

        assertEquals(0, res.code(), "站长查本站结算单应成功：" + res);
        assertTrue(res.data().isArray(), "data 应是数组：" + res.data());
        assertEquals(2, res.data().size(), "只应看到本站的 2 张（B 站那张不属于本站）：" + res.data());

        Set<Long> returned = new LinkedHashSet<>();
        for (JsonNode row : res.data()) {
            long id = row.path("id").asLong();
            returned.add(id);
            assertNotNull(row.path("payrollNo").asText(null), "单据号必须下发：" + row);
            // 逐条回库对账：金额/单据号/站别都必须与库里那一行一致（不是只看条数）
            assertEquals(0, decimalOf("SELECT total_amount FROM staff_payroll WHERE id=?", id)
                            .compareTo(row.path("totalAmount").decimalValue()),
                    "结算单合计必须与库里那一行一致（按 id 查，不依赖列表顺序）：" + row);
            assertEquals(jdbc.queryForObject("SELECT payroll_no FROM staff_payroll WHERE id=?", String.class, id),
                    row.path("payrollNo").asText(), "单据号必须与库里一致：" + row);
            assertEquals(stationA, longOf("SELECT station_id FROM staff_payroll WHERE id=?", id),
                    "列表里的每一行都必须属于本站：" + row);
            assertEquals(stationA, row.path("stationId").asLong(),
                    "下发的 stationId 也必须是本站（前端据此判权，不能是别站）：" + row);
        }
        assertEquals(Set.of(payrollA1, payrollA2), returned, "两张本站结算单一张都不能少");
        assertFalse(returned.contains(payrollB1), "B 站的结算单绝不能被 A 站站长看到");

        // limit 的上下夹取（limit=M 是 Math.min(Math.max(limit,1),500)）：
        // 客户端传 0 得到 1 条而不是"全部"、也不是报错；传超大值被夹到 500，本站只有 2 张
        assertEquals(1, get(PAYROLL + "?limit=0", tokenA).data().size(),
                "limit 下限夹到 1：传 0 既不该返回全部、也不该报错");
        assertEquals(2, get(PAYROLL + "?limit=1000", tokenA).data().size(),
                "limit 上限夹到 500：本站只有 2 张，不该因为超上限而报错或截断成 0");
    }

    @Test
    @DisplayName("payroll 列表失败可区分：顾客/配送员被拒、未绑站给明确文案，而不是空列表")
    void payrollListRejectsWithDistinguishableErrors() {
        long station = createStation("F14 工资站C");
        long manager = createStaff("F14 工资站长C", "STATION_MANAGER", station, 1);
        long rider = createStaff("F14 工资骑手C", "DELIVERY", station, 1);
        long customer = createCustomer("F14 工资客户C", "f14-payroll-cust");
        insertPayroll("F14-PR-C1", station, rider, "2026-01-01", "2026-01-31", "10.00", 1);

        // ① 顾客调员工端点 → 明拒。若回 code=0 空数组，界面就是"本站没有结算单"（§8.22）
        Api asCustomer = get(PAYROLL, customerToken(customer));
        assertEquals(1, asCustomer.code(), "顾客必须被拒，而不是拿到空列表：" + asCustomer);
        assertTrue(asCustomer.message().contains("权限不足"), "拒绝要给出权限类可读文案：" + asCustomer);
        assertTrue(asCustomer.data().isNull(), "被拒时不得下发 data 数组（否则与合法空列表混淆）：" + asCustomer);

        // ② 配送员：工资是站长台账（docs/design/18），配送员只能查自己的「我的工资」
        Api asRider = get(PAYROLL, staffToken(rider, "DELIVERY", station));
        assertEquals(1, asRider.code(), "配送员不该能看全站工资台账：" + asRider);
        assertTrue(asRider.message().contains("权限不足"), "拒绝文案要具体：" + asRider);
        assertTrue(asRider.data().isNull(), "被拒时不得下发 data：" + asRider);

        // ③ 没绑水站的站长（staff.station_id 为 NULL）→ 必须给「未绑定水站」，
        //    不能退化成"本站 0 张结算单"：前者要去补绑站，后者会让人以为台账是空的
        long orphanManager = createStaff("F14 没绑站的站长", "STATION_MANAGER", null, 1);
        Api orphan = get(PAYROLL, staffToken(orphanManager, "STATION_MANAGER", null));
        assertEquals(1, orphan.code(), "未绑站必须明确失败：" + orphan);
        assertTrue(orphan.message().contains("未绑定水站"), "文案要指得出是缺站别：" + orphan);
        assertTrue(orphan.data().isNull(), "被拒时不得下发 data：" + orphan);

        // ④ 未认证是真 401
        assertEquals(401, get(PAYROLL, null).status(), "无令牌应被 AuthInterceptor 拒成真 401");
    }

    @Test
    @DisplayName("payroll 列表越权隔离：站别只认登录态，参数里的 stationId 改不了过滤条件")
    void payrollListIsStationScopedByToken() {
        long stationA = createStation("F14 隔离工资站A");
        long stationB = createStation("F14 隔离工资站B");
        long mgrA = createStaff("F14 隔离站长A", "STATION_MANAGER", stationA, 1);
        long mgrB = createStaff("F14 隔离站长B", "STATION_MANAGER", stationB, 1);
        long riderA = createStaff("F14 隔离骑手A", "DELIVERY", stationA, 1);
        long riderB = createStaff("F14 隔离骑手B", "DELIVERY", stationB, 1);

        long payrollA1 = insertPayroll("F14-ISO-PR-A1", stationA, riderA, "2026-03-01", "2026-03-31", "33.00", 1);
        long payrollA2 = insertPayroll("F14-ISO-PR-A2", stationA, riderA, "2026-04-01", "2026-04-30", "44.00", 2);
        long payrollB1 = insertPayroll("F14-ISO-PR-B1", stationB, riderB, "2026-03-01", "2026-03-31", "55.00", 1);

        Set<Long> idsA = idsOf(get(PAYROLL, staffToken(mgrA, "STATION_MANAGER", stationA)));
        Set<Long> idsB = idsOf(get(PAYROLL, staffToken(mgrB, "STATION_MANAGER", stationB)));

        assertEquals(Set.of(payrollA1, payrollA2), idsA, "A 站站长只应看到 A 站那两张");
        assertEquals(Set.of(payrollB1), idsB, "B 站站长只应看到 B 站那一张");
        assertFalse(idsA.contains(payrollB1), "跨站泄露：A 站站长看到了 B 站的工资台账");
        assertFalse(idsB.contains(payrollA1) || idsB.contains(payrollA2),
                "跨站泄露：B 站站长看到了 A 站的工资台账");

        // 参数里塞别站 id 不生效：站点一律取自 AuthContext.requireStationId()
        Set<Long> spoofed = idsOf(get(PAYROLL + "?stationId=" + stationB, staffToken(mgrA, "STATION_MANAGER", stationA)));
        assertEquals(Set.of(payrollA1, payrollA2), spoofed,
                "请求参数里的 stationId 不得改变过滤条件（改了就等于任人翻阅他站工资）");
    }

    /* ==================================================================
     * 端点 4：GET /api/manager/earning-items/directions
     * （ManagerPayrollController#earningItemDirections）
     * 加项/扣项的可选项；文案由后端下发，前端禁止自带 1/2 映射表。
     * ================================================================== */

    @Test
    @DisplayName("directions 正例：下发 加项/扣项 两项，且每个 value 落库时就是那一列")
    void directionsRoundTripToPersistedDirection() {
        long station = createStation("F14 方向站");
        long manager = createStaff("F14 方向站长", "STATION_MANAGER", station, 1);
        String token = staffToken(manager, "STATION_MANAGER", station);

        Api dirs = get(DIRECTIONS, token);
        assertEquals(0, dirs.code(), "站长取方向可选项应成功：" + dirs);
        assertTrue(dirs.data().isArray(), "data 应是数组：" + dirs.data());
        assertEquals(2, dirs.data().size(), "只该下发 加项/扣项 两项：" + dirs.data());

        Map<Integer, String> textOf = new LinkedHashMap<>();
        for (JsonNode n : dirs.data()) {
            textOf.put(n.path("value").asInt(), n.path("text").asText());
        }
        assertEquals("加项", textOf.get(1), "方向 1 的文案由后端唯一确定：" + dirs.data());
        assertEquals("扣项", textOf.get(2), "方向 2 的文案由后端唯一确定：" + dirs.data());
        assertFalse(textOf.containsKey(0), "不能混进未定义的方向值：" + textOf.keySet());

        // 落库往返：用端点下发的 value 建条目 → 库里那一列必须就是它；
        // 列表下发的 directionText 也必须与字典逐字一致（否则前端两处口径就会漂）
        for (Map.Entry<Integer, String> e : textOf.entrySet()) {
            int value = e.getKey();
            Api created = post(ITEMS, token, "{\"name\":\"F14方向" + value + "\",\"direction\":" + value + "}");
            assertEquals(0, created.code(), "用字典里的值建条目必须成功：" + created);
            long itemId = created.data().asLong();

            assertEquals(value, intOf("SELECT direction FROM staff_earning_item WHERE id=?", itemId),
                    "端点下发的 value 必须就是落库的那一列（两端各写一套映射正是本仓事故形状）");
            assertEquals(e.getValue(), directionTextOf(get(ITEMS, token).data(), itemId),
                    "条目列表下发的 directionText 必须与 directions 字典逐字一致：" + e.getValue());
        }
    }

    @Test
    @DisplayName("directions 失败可区分：字典外的方向当场拒且一行不落，顾客被拒")
    void directionsRejectsValuesOutsideDictionary() {
        long station = createStation("F14 方向站E");
        long manager = createStaff("F14 方向站长E", "STATION_MANAGER", station, 1);
        long customer = createCustomer("F14 方向客户E", "f14-direction-cust");
        String token = staffToken(manager, "STATION_MANAGER", station);

        // ① 字典外的方向必须当场拒：静默当"加项"处理就是"往人家工资里记了一笔说不清的钱"
        Api bad = post(ITEMS, token, "{\"name\":\"F14乱方向\",\"direction\":9}");
        assertEquals(1, bad.code(), "方向 9 必须给业务错误（code=1），不能当加项静默通过：" + bad);
        assertTrue(bad.message().contains("方向只能是"), "文案要指得出是方向非法：" + bad);
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_earning_item WHERE station_id=? AND name='F14乱方向'", station),
                "被拒后库里必须一行都没有（关键状态未变，不能留下半条脏数据）");

        // ② 缺方向（null）同样拒 —— 兜底不许把未知值说成某个已知值
        Api noDirection = post(ITEMS, token, "{\"name\":\"F14无方向\",\"direction\":null}");
        assertEquals(1, noDirection.code(), "direction=null 必须被拒：" + noDirection);
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_earning_item WHERE station_id=?", station),
                "两次被拒之后本站仍应是 0 条条目");

        // ③ 顾客调它 → 明拒（不是"店铺还没配置可选项"）
        Api asCustomer = get(DIRECTIONS, customerToken(customer));
        assertEquals(1, asCustomer.code(), "顾客不该拿到工资条目的可选项：" + asCustomer);
        assertTrue(asCustomer.message().contains("权限不足"), "拒绝文案要具体：" + asCustomer);
        assertTrue(asCustomer.data().isNull(), "被拒时不得下发 data 数组（否则与「确实没有可选项」混淆）：" + asCustomer);

        // ④ 未认证是真 401
        assertEquals(401, get(DIRECTIONS, null).status(), "无令牌应是真 401");
    }

    @Test
    @DisplayName("directions 越权隔离：字典不含任何站别数据，配送员被拒，站点无关性可复现")
    void directionsCarryNoStationDataAndAreRoleGated() {
        long stationA = createStation("F14 字典站A");
        long stationB = createStation("F14 字典站B");
        long mgrA = createStaff("F14 字典站长A", "STATION_MANAGER", stationA, 1);
        long mgrB = createStaff("F14 字典站长B", "STATION_MANAGER", stationB, 1);
        long riderA = createStaff("F14 字典骑手A", "DELIVERY", stationA, 1);

        Api a = get(DIRECTIONS, staffToken(mgrA, "STATION_MANAGER", stationA));
        Api b = get(DIRECTIONS, staffToken(mgrB, "STATION_MANAGER", stationB));
        assertEquals(0, a.code(), "站长应能取到字典：" + a);
        assertEquals(a.data(), b.data(), "字典与水站无关，两站必须拿到完全相同的载荷");

        for (JsonNode n : a.data()) {
            assertEquals(2, n.size(), "字典项只含 value/text，任何多余字段都可能夹带站别信息：" + n);
            assertFalse(n.has("stationId"), "字典不得夹带站别：" + n);
            assertFalse(n.has("name"), "字典不得夹带任何条目名（那是别站数据）：" + n);
        }

        // 未绑水站的站长也能拿到：它是纯字典、不含站别数据，角色是唯一闸门
        // （与 requireStationId 型端点相反，这里连不上站也不该把可选项藏起来）
        long orphanManager = createStaff("F14 字典没绑站站长", "STATION_MANAGER", null, 1);
        Api orphan = get(DIRECTIONS, staffToken(orphanManager, "STATION_MANAGER", null));
        assertEquals(0, orphan.code(), "纯字典不依赖水站，没绑站也该拿得到可选项：" + orphan);

        // 配送员 → 明拒：这个字典会被前端用来建工资条目，而条目方向决定从谁工资里扣钱
        Api asRider = get(DIRECTIONS, staffToken(riderA, "DELIVERY", stationA));
        assertEquals(1, asRider.code(), "配送员不该拿到工资条目的可选项：" + asRider);
        assertTrue(asRider.message().contains("权限不足"), "拒绝文案要具体：" + asRider);
        assertTrue(asRider.data().isNull(), "被拒时不得下发 data：" + asRider);
    }

    /* ==================== 夹具 ==================== */

    /** 造一张结算单（只走 INSERT，不动生产代码）。 */
    private long insertPayroll(String payrollNo, long stationId, long staffId,
                               String periodStart, String periodEnd, String totalAmount, int status) {
        return insert("INSERT INTO staff_payroll(payroll_no, station_id, staff_id, period_start, period_end, "
                        + "total_amount, status) VALUES (?,?,?,?,?,?,?)",
                payrollNo, stationId, staffId,
                Date.valueOf(periodStart), Date.valueOf(periodEnd),
                new java.math.BigDecimal(totalAmount), status);
    }

    /** 列表响应里的 id 集合（用 Set 比较，不依赖列表顺序）。 */
    private static Set<Long> idsOf(Api res) {
        assertEquals(0, res.code(), "列表查询应成功：" + res);
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode row : res.data()) {
            ids.add(row.path("id").asLong());
        }
        return ids;
    }

    /** 条目列表里某个 id 的 directionText（按 id 找行，不按下标 —— 下标会随插入顺序漂）。 */
    private static String directionTextOf(JsonNode items, long itemId) {
        assertTrue(items.isArray(), "条目列表应是数组：" + items);
        for (JsonNode n : items) {
            if (n.path("id").asLong() == itemId) {
                return n.path("directionText").asText();
            }
        }
        throw new AssertionError("条目列表里找不到 id=" + itemId + " 的行，实际=" + items);
    }
}
