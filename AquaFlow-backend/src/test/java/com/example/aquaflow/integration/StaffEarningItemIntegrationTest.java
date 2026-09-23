package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 站长自定义工资条目（v44）契约。
 *
 * <p>为什么值得立用例：这是"会真的从人家工资里扣掉一笔钱"的入口，而它的危险形状都不是崩溃，
 * 而是<b>静默地把钱改错</b> —— 方向记反、条目被删掉后历史流水变成"无条目的人工调整"、
 * 或者站长传一个别站配送员的 id 就往那个人的「未结工资」里塞钱（后者是本次一并修掉的越权）。</p>
 *
 * <p>五条口径：① 条目 CRUD + 同站同名拒绝 + 停用只挡新录入；
 * ② 传了 itemId 就<b>只收正数</b>，符号由条目方向决定；
 * ③ {@code item_name} 是写入时的快照，条目改名不改写历史；
 * ④ 被流水用过的条目只能停用、不能删；
 * ⑤ 人工调整必须验"这个人与本站有履约关系"（本站员工 <b>或</b> 在本站有过收益）。</p>
 */
class StaffEarningItemIntegrationTest extends AbstractIntegrationTest {

    private static final String ITEMS = "/api/manager/earning-items";
    private static final String ADJUST = "/api/manager/payroll/adjust";

    @Test
    @DisplayName("条目 CRUD：同站同名拒绝、空名/非法方向拒绝、停用后不再出现在可选集")
    void itemCrudValidatesNameAndDirection() {
        long station = createStation("工资条目站A");
        long mgr = createStaff("站长甲", "STATION_MANAGER", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);

        Api created = post(ITEMS, token, "{\"name\":\"高温补贴\",\"direction\":1}");
        assertEquals(0, created.code(), "建条目应成功：" + created.data());
        long itemId = created.data().asLong();

        Api list = get(ITEMS, token);
        assertEquals(0, list.code());
        assertEquals(1, list.data().size(), "应只有刚建的那一条");
        assertEquals("加项", list.data().get(0).path("directionText").asText(),
                "方向文案由后端下发，前端不许自己映射 1/2");

        assertNotEquals(0, post(ITEMS, token, "{\"name\":\"高温补贴\",\"direction\":2}").code(),
                "同站同名必须拒绝 —— 两个「高温补贴」会让月底汇总对不上账");
        assertNotEquals(0, post(ITEMS, token, "{\"name\":\"   \",\"direction\":1}").code(), "空名应拒绝");
        assertNotEquals(0, post(ITEMS, token, "{\"name\":\"乱方向\",\"direction\":9}").code(),
                "方向只能是 1/2，其它值应拒绝而不是当加项静默处理");

        assertEquals(0, put(ITEMS + "/" + itemId, token, "{\"name\":\"高温费\",\"direction\":1}").code());
        assertEquals("高温费", get(ITEMS, token).data().get(0).path("name").asText());

        assertEquals(0, post(ITEMS + "/" + itemId + "/status", token, "{\"status\":0}").code());
        assertEquals(0, get(ITEMS + "?enabledOnly=true", token).data().size(), "停用后不该出现在可选集");
        assertEquals(1, get(ITEMS, token).data().size(), "但列表仍要看得到（否则站长没法再启用它）");
    }

    @Test
    @DisplayName("方向由条目决定：传了 itemId 只收正数，扣项自动落负数，并记名称快照")
    void itemDrivesSignAndStoresSnapshot() {
        long station = createStation("工资条目站B");
        long mgr = createStaff("站长乙", "STATION_MANAGER", station, 1);
        long rider = createStaff("骑手乙", "DELIVERY", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);

        long addItem = post(ITEMS, token, "{\"name\":\"高温补贴\",\"direction\":1}").data().asLong();
        long deductItem = post(ITEMS, token, "{\"name\":\"迟到扣款\",\"direction\":2}").data().asLong();

        assertEquals(0, post(ADJUST, token, adjustBody(rider, addItem, "100")).code());
        assertEquals(0, decimalOf("select amount from staff_earning where staff_id = ?", rider)
                        .compareTo(new java.math.BigDecimal("100.00")),
                "加项应落正数");

        assertEquals(0, post(ADJUST, token, adjustBody(rider, deductItem, "30")).code());
        assertEquals(0, decimalOf("select sum(amount) from staff_earning where staff_id = ? and item_id = ?",
                        rider, deductItem).compareTo(new java.math.BigDecimal("-30.00")),
                "扣项必须由条目方向落成负数，而不是让录的人自己填负号");
        assertEquals("迟到扣款",
                itemNameOf(get("/api/manager/earnings?staffId=" + rider, token).data().path("earnings"), deductItem),
                "明细里应带条目名称快照（按 itemId 找行，别按下标 —— 明细是按 id 升序，下标会随插入顺序漂）");

        assertNotEquals(0, post(ADJUST, token, adjustBody(rider, addItem, "-5")).code(),
                "按条目录入时负数必须拒绝：符号已由条目决定，两处各判一次迟早写反");

        // 改名不改写历史：老流水保留当时的快照名
        assertEquals(0, put(ITEMS + "/" + addItem, token,
                "{\"name\":\"高温费\",\"direction\":1}").code());
        assertEquals(1, intOf("select count(*) from staff_earning where staff_id = ? and item_name = '高温补贴'",
                        rider),
                "条目改名不该改写已经发生过的工资记录（与 order_item.product_name 同口径）");
    }

    @Test
    @DisplayName("被流水用过的条目只能停用、不能删；没用过的可以删")
    void usedItemCannotBeDeleted() {
        long station = createStation("工资条目站C");
        long mgr = createStaff("站长丙", "STATION_MANAGER", station, 1);
        long rider = createStaff("骑手丙", "DELIVERY", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);

        long used = post(ITEMS, token, "{\"name\":\"破损赔偿\",\"direction\":2}").data().asLong();
        long unused = post(ITEMS, token, "{\"name\":\"临时帮忙费\",\"direction\":1}").data().asLong();

        assertEquals(0, post(ADJUST, token, adjustBody(rider, used, "20")).code());

        Api refused = delete(ITEMS + "/" + used, token);
        assertNotEquals(0, refused.code(),
                "删掉用过的条目，那些流水就变成「无条目的人工调整」，站长再也说不清那是什么钱");
        assertEquals(1, intOf("select count(*) from staff_earning_item where id = ?", used), "拒绝后条目必须还在");

        assertEquals(0, post(ITEMS + "/" + used + "/status", token, "{\"status\":0}").code(),
                "正确做法是停用");
        assertNotEquals(0, delete(ITEMS + "/" + used, token).code(), "停用不等于可以删");

        assertEquals(0, delete(ITEMS + "/" + unused, token).code(), "没被用过的条目应能直接删掉");
        assertEquals(0, intOf("select count(*) from staff_earning_item where id = ?", unused));
    }

    @Test
    @DisplayName("按条目汇总：同条目累加，自由文本调整不进汇总但仍进未结合计")
    void itemSummaryAggregates() {
        long station = createStation("工资条目站D");
        long mgr = createStaff("站长丁", "STATION_MANAGER", station, 1);
        long rider = createStaff("骑手丁", "DELIVERY", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);

        long addItem = post(ITEMS, token, "{\"name\":\"高温补贴\",\"direction\":1}").data().asLong();
        long deductItem = post(ITEMS, token, "{\"name\":\"迟到扣款\",\"direction\":2}").data().asLong();

        post(ADJUST, token, adjustBody(rider, addItem, "100"));
        post(ADJUST, token, adjustBody(rider, addItem, "50"));
        post(ADJUST, token, adjustBody(rider, deductItem, "30"));
        // 自由文本调整：没有条目，只应体现在未结合计里
        assertEquals(0, post(ADJUST, token, "{\"staffId\":" + rider + ",\"amount\":10,\"note\":\"口头约定\"}").code());

        Api res = get("/api/manager/earnings?staffId=" + rider, token);
        assertEquals(0, res.code());

        JsonNode summary = res.data().path("itemSummary");
        assertTrue(summary.isArray(), "itemSummary 应是数组：" + res.data());
        assertEquals(2, summary.size(), "只汇总带条目的流水，实际=" + summary);

        assertEquals(0, totalOf(summary, "高温补贴").compareTo(new java.math.BigDecimal("150.00")),
                "同条目两笔应累加");
        assertEquals(0, totalOf(summary, "迟到扣款").compareTo(new java.math.BigDecimal("-30.00")),
                "扣项汇总应带符号，别让前端自己再判方向");

        assertEquals(0, decimalOf("select coalesce(sum(amount),0) from staff_earning where staff_id = ?", rider)
                        .compareTo(new java.math.BigDecimal("130.00")),
                "明细合计 = 150 − 30 + 10（自由文本那 10 也要算进工资）");
        assertEquals(0, res.data().path("unsettledTotal").decimalValue()
                        .compareTo(new java.math.BigDecimal("130.00")),
                "未结合计与明细合计必须一致 —— 汇总不是合计，两者不是同一口径");
    }

    @Test
    @DisplayName("人工调整的归属：给没有履约关系的外站配送员记工资必须拒绝")
    void adjustRejectsStaffWithoutRelation() {
        long stationA = createStation("工资条目站E");
        long mgrA = createStaff("站长戊", "STATION_MANAGER", stationA, 1);
        String tokenA = staffToken(mgrA, "STATION_MANAGER", stationA);

        long stationB = createStation("工资条目站F");
        long riderB = createStaff("骑手己", "DELIVERY", stationB, 1);

        Api refused = post(ADJUST, tokenA, "{\"staffId\":" + riderB + ",\"amount\":100,\"note\":\"塞钱\"}");
        assertNotEquals(0, refused.code(),
                "「我的工资」自助查询刻意只按 staff_id 过滤（跨站外派的口径），"
                        + "所以任何站长传一个别站配送员 id 就能改那个人的未结工资 —— 必须在这里拦住");

        // 跨站外派是合法场景：这个人在本站留下过履约痕迹后，就该给他记账
        insert("insert into staff_earning(station_id, staff_id, order_id, kind, product_id, amount, note, create_time) "
                + "values(?,?,null,'ADJUST',0,10.00,'外派跑腿',NOW())", stationA, riderB);
        assertEquals(0, post(ADJUST, tokenA, "{\"staffId\":" + riderB + ",\"amount\":50,\"note\":\"外派补贴\"}").code(),
                "在本站有过收益的人属于「与本站有履约关系」，跨站外派的工钱得发得出去");
    }

    /* ==================== 夹具 ==================== */

    private static String adjustBody(long staffId, long itemId, String amount) {
        return "{\"staffId\":" + staffId + ",\"itemId\":" + itemId + ",\"amount\":" + amount + "}";
    }

    private static java.math.BigDecimal totalOf(JsonNode summary, String name) {
        for (JsonNode n : summary) {
            if (name.equals(n.path("name").asText())) {
                return n.path("total").decimalValue();
            }
        }
        fail("汇总里找不到条目「" + name + "」，实际=" + summary);
        return null;
    }

    /** 明细里某个条目那行的名称快照（按 itemId 查，不依赖列表顺序） */
    private static String itemNameOf(JsonNode earnings, long itemId) {
        assertTrue(earnings.isArray(), "earnings 应是数组：" + earnings);
        for (JsonNode n : earnings) {
            if (n.path("itemId").asLong() == itemId) {
                return n.path("itemName").asText();
            }
        }
        fail("明细里找不到 itemId=" + itemId + " 的收益行，实际=" + earnings);
        return null;
    }
}
