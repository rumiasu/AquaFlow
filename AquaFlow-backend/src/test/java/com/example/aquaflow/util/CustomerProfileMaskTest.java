package com.example.aquaflow.util;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.StaffMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CustomerProfileMask} 的**单元级**契约（台账 F-21）。
 *
 * <p><b>为什么要有这个类、而不是再加一条集成用例</b>：本类的 Map 形态端到端行为已经被
 * {@code CrossStationPoolRiskAndProfileIsolationIntegrationTest#fulfillmentSideKeepsMaskingAfterClaim}
 * 钉住了（读 {@code GET /api/staff/{id}/profile} 的 {@code currentOrders}，断言跨站行
 * {@code customerName} 为 null）。那条用例跑一遍要起整个 Spring 容器；而这里要钉的两件事
 * ——「同一行的同站/跨站判定」与「**读不到站别时静默不抹**」——都是纯数据映射，
 * 用 Map 直接喂进去就能证伪，跑一次不到 1 秒。两者分工：集成用例保端到端链路，
 * 本类保规则本身的每一条分支（含那条**故意 fail-open** 的）。</p>
 *
 * <p>⚠️ 本类**不重复**集成用例的端到端断言，也**不**替代它：本类全绿而集成用例红了，
 * 说明 SQL 列别名或控制器接线出了问题，不是规则本身。</p>
 */
class CustomerProfileMaskTest {

    /** 造一行「调用方 SQL 已按契约 as 出站别」的 Map（键名与 {@code StaffMapper.listCurrentOrders} 同形）。 */
    private static Map<String, Object> rowWithStations(Long ownerStationId, Long deliveryStationId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("totalAmount", new java.math.BigDecimal("40.00"));
        row.put("addressSnapshot", "某小区1号");
        row.put("receiverName", "收件人快照");
        row.put("stationId", ownerStationId);
        row.put("deliveryStationId", deliveryStationId);
        row.put("customerName", "归属站客户档案");
        row.put("customerPhone", "13800000000");
        return row;
    }

    /* ==================================================================
     *  ① 跨站（履约站 ≠ 归属站）→ 抹掉
     * ================================================================== */

    @Test
    @DisplayName("① 跨站行：画像两列置 null，订单快照照常（键必须保留，不是删列）")
    void crossStationRowLosesProfileButKeepsOrderSnapshot() {
        Map<String, Object> row = rowWithStations(1L, 2L);

        CustomerProfileMask.maskIfCrossStation(row, "customerName", "customerPhone");

        assertNull(row.get("customerName"), "跨站行的归属站客户姓名必须抹掉");
        assertNull(row.get("customerPhone"), "跨站行的归属站客户手机号必须抹掉");
        // 「保留键、显式置 null」是本类的明文口径：键没了前端就分不清"后端刻意不下发"与"客户端拿了旧后端"
        assertTrue(row.containsKey("customerName"), "键必须仍在，只是值为 null");
        assertTrue(row.containsKey("customerPhone"), "键必须仍在，只是值为 null");
        // 只抹画像，订单自身的快照必须原样 —— 跨站配送要靠它点名收件人、送对地址
        assertEquals("收件人快照", row.get("receiverName"), "收件人快照属'订单有关信息'，跨站配送必需");
        assertEquals("某小区1号", row.get("addressSnapshot"), "地址快照照常下发");
        assertEquals("40.00", String.valueOf(row.get("totalAmount")), "金额快照照常下发");
    }

    @Test
    @DisplayName("① 边界：只传一个 profileKey 时只抹那一个（调用方的键列表就是抹除范围）")
    void onlyRequestedProfileKeysAreCleared() {
        Map<String, Object> row = rowWithStations(1L, 2L);

        // 站长端员工画像的真实调用形态：只抹 customerName（见 StaffServiceImpl#decorateOrders）
        CustomerProfileMask.maskIfCrossStation(row, "customerName");

        assertNull(row.get("customerName"), "跨站行必须抹姓名");
        assertEquals("13800000000", row.get("customerPhone"), "没在 profileKeys 里的列不归本方法管，保持原样");
    }

    /* ==================================================================
     *  ② 同站 → 不抹
     * ================================================================== */

    @Test
    @DisplayName("② 同站行：画像照常显示（一刀切会把本站客户自己的姓名也抹掉）")
    void sameStationRowKeepsProfile() {
        Map<String, Object> row = rowWithStations(7L, 7L);

        CustomerProfileMask.maskIfCrossStation(row, "customerName", "customerPhone");

        assertEquals("归属站客户档案", row.get("customerName"), "同站单必须照常显示客户姓名");
        assertEquals("13800000000", row.get("customerPhone"), "同站单必须照常显示客户手机号");
    }

    /** 判定本身：只有「两列都非空且不相等」才算跨站，其余一律不是（`isCrossStation` 的兜底）。 */
    @Test
    @DisplayName("② 判定边界：任一为空 / 相等 → 不是跨站；不等 → 是跨站")
    void crossStationPredicateBoundaries() {
        assertFalse(CustomerProfileMask.isCrossStation(null, 2L), "归属站为空（池中单）不算跨站");
        assertFalse(CustomerProfileMask.isCrossStation(1L, null), "履约站为空（还没派出去）不算跨站");
        assertFalse(CustomerProfileMask.isCrossStation(null, null), "两列都空不算跨站");
        assertFalse(CustomerProfileMask.isCrossStation(3L, 3L), "同站不是跨站");
        assertTrue(CustomerProfileMask.isCrossStation(3L, 4L), "履约站 ≠ 归属站 = 跨站");
    }

    /* ==================================================================
     *  ③ 缺站别键的 Map → 不抹（**fail-open 契约，本类存在的理由**）
     * ================================================================== */

    /**
     * <b>为什么这里是 fail-open 而不是 fail-closed</b>（把这个决定钉成可证伪的契约，改语义前必须红）：
     *
     * <p>走进这个分支的 row 有两种来源，而<b>它们在数据上无法区分</b>：
     * ① 调用方 SQL 忘了 {@code as stationId} / {@code as deliveryStationId} —— 该抹没抹，是真漏；
     * ② 本站单的站别列<b>合法为空</b>（例如还没派单的池中单，{@code delivery_station_id} 本来就可空）
     * —— 不该抹，抹了就是误伤本站客户自己的姓名/电话（那是行为语义变更）。
     * 「读不到就抹」会把 ② 一起抹掉，即拿一个确定的误伤去换一个不确定的补救。</p>
     *
     * <p>所以本分支的正确做法是<b>可见化</b>（{@code log.warn} 打出 {@code rowKeys} 让人去核对 SQL 别名），
     * 而不是改成保守抹除。判据：这条用例红了 = 有人把 fail-open 改成了 fail-closed，
     * 必须同时回答"同站展示的姓名怎么保证不被误抹"。新增 Map 形态列表时的护栏是
     * {@link #realMapCallerSqlStillAliasesStationColumns()} 那样的别名断言 + 集成断言，不是改这里的语义。</p>
     */
    @Test
    @DisplayName("③ fail-open 契约：Map 没有站别键时**不抹**（读不到 ≠ 跨站）")
    void missingStationKeysAreNotMaskedFailOpen() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerName", "归属站客户档案");
        row.put("customerPhone", "13800000000");

        CustomerProfileMask.maskIfCrossStation(row, "customerName", "customerPhone");

        assertEquals("归属站客户档案", row.get("customerName"),
                "fail-open 契约：SQL 没 as 出站别时这里**不抹**（判定依据缺失 ≠ 跨站）");
        assertEquals("13800000000", row.get("customerPhone"),
                "同上：fail-open 是已知取舍，改它会误抹同站展示的姓名");
    }

    @Test
    @DisplayName("③ fail-open 契约：键在但值为 null（池中单）同样不抹")
    void nullStationValuesAreNotMaskedFailOpen() {
        Map<String, Object> row = rowWithStations(1L, null);

        CustomerProfileMask.maskIfCrossStation(row, "customerName", "customerPhone");

        assertEquals("归属站客户档案", row.get("customerName"), "履约站为空 = 还没派单，不能当跨站抹");
        assertEquals("13800000000", row.get("customerPhone"), "同上");
    }

    @Test
    @DisplayName("③ fail-open 契约：只缺一列就整体跳过（两列任一读不到都不抹）")
    void oneMissingStationColumnSkipsMaskingEntirely() {
        Map<String, Object> row = rowWithStations(1L, 2L);
        row.remove("deliveryStationId");

        CustomerProfileMask.maskIfCrossStation(row, "customerName", "customerPhone");

        assertEquals("归属站客户档案", row.get("customerName"),
                "少了 deliveryStationId 就无法判断是不是跨站，按 fail-open 跳过");
    }

    /**
     * 非 {@code Number} 形态的站别（例如字符串 {@code "1"}）同样落进 fail-open —— 这是
     * {@code asLong} 的实现边界（只认 {@code Number}）。MyBatis 对 BIGINT 列返回 {@code Long}，
     * 所以正常链路不会命中；写出来是为了让"读不到 = 不抹"这条边界的**全部形状**都被钉住。
     */
    @Test
    @DisplayName("③ fail-open 契约：站别不是 Number 时也不抹（asLong 只认 Number）")
    void nonNumericStationValuesAreNotMaskedFailOpen() {
        Map<String, Object> row = rowWithStations(null, null);
        row.put("stationId", "1");
        row.put("deliveryStationId", "2");

        CustomerProfileMask.maskIfCrossStation(row, "customerName", "customerPhone");

        assertEquals("归属站客户档案", row.get("customerName"), "字符串站别取不出 Long，落进 fail-open 分支");
    }

    /* ==================================================================
     *  ④ 已有的 Map 形态真实调用方：行为不被改变
     * ================================================================== */

    /**
     * 全仓 Map 形态**唯一**的真实调用方是站长端「员工画像 · 当前进行中」
     * （{@code StaffServiceImpl#decorateOrders} ← {@code StaffMapper.listCurrentOrders}）。
     * 它靠 SQL 的 {@code as stationId} / {@code as deliveryStationId} 两列喂给上面那条规则 ——
     * 这两列别名就是整条链的**唯一判定依据**，删掉它规则会静默失效（fail-open 的下一个落点）。
     *
     * <p>这里用反射直接读 mapper 的 SQL 文本做**别名断言**：不是替代集成用例（端到端行为仍由
     * {@code CrossStationPoolRiskAndProfileIsolationIntegrationTest#fulfillmentSideKeepsMaskingAfterClaim} 保），
     * 而是把失败**定位到 mapper 这一行**：集成用例红了要一路查是 SQL 别名丢了、还是控制器没调抹除；
     * 这里红了就只有一种可能。这就是 F-21 说的"新增 Map 形态列表时照这两行加断言"的机械护栏。</p>
     */
    @Test
    @DisplayName("④ 真实调用方 SQL 仍 as 出两列（别名丢了这条链会静默 fail-open）")
    void realMapCallerSqlStillAliasesStationColumns() throws Exception {
        Method m = StaffMapper.class.getMethod("listCurrentOrders", Long.class);
        Select select = m.getAnnotation(Select.class);
        assertNotNull(select, "listCurrentOrders 必须仍是注解 SQL —— 换成 XML mapper 时请把本断言搬到 XML 上");
        String sql = String.join(" ", select.value());
        assertTrue(sql.contains("as stationId"),
                "SQL 必须 as 出 stationId（MyBatis 的 map-underscore-to-camel-case 对 Map 返回值不生效），实际=" + sql);
        assertTrue(sql.contains("as deliveryStationId"),
                "SQL 必须 as 出 deliveryStationId，实际=" + sql);
    }

    /**
     * ④ 的行为面：拿真实调用方那张 SQL 会产出的**两种行**（同站 / 跨站）走一遍，
     * 断言"同站照常显示、跨站被抹"——即本站员工画像功能不因这条规则而被做坏。
     * 与 ①② 的区别只在**用的键集与真实调用方一致**（只抹 {@code customerName}，
     * 因为 SQL 根本没 select {@code customerPhone}）。
     */
    @Test
    @DisplayName("④ 真实调用方形态：同站行照常、跨站行抹掉，且订单快照（收件人/地址/金额）不受影响")
    void realCallerShapeKeepsSameStationAndMasksCrossStation() {
        Map<String, Object> cross = rowWithStations(1L, 2L);
        Map<String, Object> mine = rowWithStations(2L, 2L);

        // 与 StaffServiceImpl#decorateOrders 逐字同参：只抹 customerName
        CustomerProfileMask.maskIfCrossStation(cross, "customerName");
        CustomerProfileMask.maskIfCrossStation(mine, "customerName");

        assertNull(cross.get("customerName"), "跨站行在员工画像里不得出现归属站客户姓名");
        assertEquals("收件人快照", cross.get("receiverName"), "收件人快照照常（配送必需）");
        assertEquals("2", String.valueOf(cross.get("deliveryStationId")));
        assertEquals("归属站客户档案", mine.get("customerName"), "本站单必须照常显示姓名（防一刀切）");
    }

    /* ==================================================================
     *  顺带钉住 Orders 形态（同一规则的另一半；调用方见 DeliveryController）
     * ================================================================== */

    @Test
    @DisplayName("Orders 形态：跨站抹、同站留；无条件 mask 一律抹")
    void ordersOverloadsFollowTheSameRule() {
        Orders cross = orders(1L, 2L);
        CustomerProfileMask.maskIfCrossStation(cross);
        assertNull(cross.getCustomerName(), "Orders 形态跨站同样抹姓名");
        assertNull(cross.getCustomerPhone(), "Orders 形态跨站同样抹手机号");

        Orders mine = orders(2L, 2L);
        CustomerProfileMask.maskIfCrossStation(mine);
        assertEquals("归属站客户档案", mine.getCustomerName(), "Orders 形态同站不抹");

        // 池中单 delivery_station_id 为空、整表都是别站客户 → 必须用无条件抹除（见类 javadoc 的口径）
        CustomerProfileMask.mask(mine);
        assertNull(mine.getCustomerName(), "无条件 mask 必须一律抹（抢单池/他站外派面）");
        assertNull(mine.getCustomerPhone(), "无条件 mask 必须一律抹手机号");
    }

    private static Orders orders(Long ownerStationId, Long deliveryStationId) {
        Orders o = new Orders();
        o.setStationId(ownerStationId);
        o.setDeliveryStationId(deliveryStationId);
        o.setCustomerName("归属站客户档案");
        o.setCustomerPhone("13800000000");
        return o;
    }
}
