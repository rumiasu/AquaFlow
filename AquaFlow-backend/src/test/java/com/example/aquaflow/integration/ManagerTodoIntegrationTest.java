package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长首页「待办聚合」（2026-09-18，规格见 {@code docs/design/20} §7）。
 *
 * <p>盯的是**四类待办各数各的、且不串站**：首页上的数字是站长决定"今天先干哪件事"的依据，
 * 数错了不会报错，只会让人去处理不存在的事、或者漏掉真在等他的事。</p>
 *
 * <p>口径一律复用各模块已有的读数入口（逾期走应收账款、未填成本走毛利、结算单走状态计数、
 * 桶异常走异常单列表的同一个过滤），所以本用例**同时是那四条口径的一致性断言** ——
 * 首页说 1 笔逾期，台账也必须说 1 笔。</p>
 */
@DisplayName("站长首页 · 待办聚合（只读 / 各口径一致 / 不串站）")
class ManagerTodoIntegrationTest extends AbstractIntegrationTest {

    private Api todo(String mgrToken) {
        return get("/api/manager/todo-summary", mgrToken);
    }

    private int countOf(Api res, String key) {
        for (int i = 0; i < res.data().path("items").size(); i++) {
            if (key.equals(res.data().path("items").get(i).path("key").asText())) {
                return res.data().path("items").get(i).path("count").asInt();
            }
        }
        throw new AssertionError("待办项里没有 " + key + "，实际=" + res.data().path("items"));
    }

    @Test
    @DisplayName("干净水站：四类待办全是 0，hasAny=false（不是把所有项都当成待办）")
    void cleanStationHasNothingToDo() {
        long station = createStation("待办空站");
        long mgr = createStaff("待办空站长", "STATION_MANAGER", station, 1);

        Api res = todo(staffToken(mgr, "STATION_MANAGER", station));
        assertTrue(res.isSuccess(), "实际=" + res);
        assertEquals(4, res.data().path("items").size(), "四类待办都要在（数量为 0 也要显示，否则站长不知道有这项）");
        for (String key : new String[]{"overdueReceivable", "costNotFilled", "draftPayroll", "pendingBarrelException"}) {
            assertEquals(0, countOf(res, key), key + " 应为 0");
        }
        assertFalse(res.data().path("hasAny").asBoolean(), "没有待办时 hasAny 必须是 false");
        assertTrue(res.data().path("items").get(0).path("label").asText().length() > 0,
                "文案由后端下发（前端禁止自带一份中文表）");
    }

    @Test
    @DisplayName("四类待办各记各的数，且与各自台账口径一致")
    void eachTodoIsCountedAndMatchesItsOwnLedger() {
        long[] s = seed();
        long station = s[0], mgr = s[1], customer = s[2], address = s[3], product = s[4];
        String mgrToken = staffToken(mgr, "STATION_MANAGER", station);
        String cusToken = customerToken(customer);

        // 1) 逾期应收：现金单 + 账期，再把到期日推到过去（账期是快照，只能这样造"已逾期"）
        assertEquals(0, put("/api/manager/customers/" + customer + "/credit-terms", mgrToken,
                "{\"dueDays\":30}").code(), "站长设账期");
        assertEquals(0, post("/api/orders/create", cusToken,
                "{\"addressId\":" + address + ",\"stationId\":" + station + ",\"paymentMethod\":2,"
                        + "\"idempotencyKey\":\"todo-ar\",\"items\":[{\"productId\":" + product
                        + ",\"quantity\":2}]}").code(), "现金单（下单即待收款）");
        long order = longOf("SELECT id FROM orders WHERE idempotency_key='todo-ar'");
        jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL 5 DAY) WHERE id=?", order);

        // 2) 未填成本：上架了但没填进货成本 —— 是"没填"，不是"填了 0"
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND enabled=1 AND cost_price IS NULL",
                station), "前置：本用例的商品确实没填成本");

        // 3) 待确认结算单：已生成、还没确认（status=1 草稿）
        insert("INSERT INTO staff_payroll(payroll_no, station_id, staff_id, period_start, period_end, "
                        + "total_amount, status) VALUES (?,?,?,?,?,100.00,1)",
                "PR-TODO-1", station, mgr, java.sql.Date.valueOf("2026-09-01"),
                java.sql.Date.valueOf("2026-09-07"));

        // 4) 待处理桶异常：配送员已录入、等站长处置
        long exceptionOrder = createOrderFull(customer, address, station, product, 4, 2, 2,
                "20.00", "0.00", "20.00", false, 2);
        insert("INSERT INTO order_barrel_exception(order_id, customer_id, station_id, delivery_qty, "
                        + "return_qty, discrepancy, category, staff_action, status, created_at) "
                        + "VALUES (?,?,?,2,0,2,'RETURN_SHORT','PARTIAL','STAFF_RECORDED',NOW())",
                exceptionOrder, customer, station);

        Api res = todo(mgrToken);
        assertTrue(res.isSuccess(), "实际=" + res);
        assertEquals(1, countOf(res, "overdueReceivable"), "1 位客户逾期");
        assertEquals(1, countOf(res, "costNotFilled"), "1 个上架商品没填成本");
        assertEquals(1, countOf(res, "draftPayroll"), "1 张待确认结算单");
        assertEquals(1, countOf(res, "pendingBarrelException"), "1 单待处理桶异常");
        assertTrue(res.data().path("hasAny").asBoolean(), "有待办时 hasAny=true");

        // 逾期那一项要带金额，且与应收账款台账**逐字一致**（同一口径，不能两算）
        var overdueItem = res.data().path("items").get(0);
        assertTrue(overdueItem.path("amount").isNumber(), "逾期应收要带金额，实际=" + overdueItem);
        assertEquals(0, get("/api/manager/receivables", mgrToken).data().path("overdueAmount")
                        .decimalValue().compareTo(overdueItem.path("amount").decimalValue()),
                "首页的逾期金额必须等于应收账款台账的逾期合计");

        // 处置掉一单之后，首页立刻少一项（避免"处理完了首页还挂着"）
        long exId = longOf("SELECT id FROM order_barrel_exception WHERE station_id=?", station);
        assertEquals(0, post("/api/manager/exceptions/" + exId + "/handle", mgrToken,
                "{\"action\":\"IGNORE\",\"managerNote\":\"客户已电话说明\"}").code(), "忽略该异常");
        assertEquals(0, countOf(todo(mgrToken), "pendingBarrelException"), "忽略后不再是待处理");
    }

    @Test
    @DisplayName("不串站：别的站有 4 类待办，也进不了本站的首页；顾客调不到")
    void stationIsolationAndRoleGuard() {
        long[] a = seed("待办A站");
        long[] b = seed("待办B站");
        String tokenA = staffToken(a[1], "STATION_MANAGER", a[0]);
        String tokenB = staffToken(b[1], "STATION_MANAGER", b[0]);

        // 只给 B 站造待办
        insert("INSERT INTO staff_payroll(payroll_no, station_id, staff_id, period_start, period_end, "
                        + "total_amount, status) VALUES (?,?,?,?,?,50.00,1)",
                "PR-TODO-B", b[0], b[1], java.sql.Date.valueOf("2026-09-01"),
                java.sql.Date.valueOf("2026-09-07"));
        long bOrder = createOrderFull(b[2], b[3], b[0], b[4], 4, 2, 2, "20.00", "0.00", "20.00", false, 2);
        insert("INSERT INTO order_barrel_exception(order_id, customer_id, station_id, delivery_qty, "
                        + "return_qty, discrepancy, category, staff_action, status, created_at) "
                        + "VALUES (?,?,?,2,0,2,'RETURN_SHORT','PARTIAL','STAFF_RECORDED',NOW())",
                bOrder, b[2], b[0]);

        assertEquals(1, countOf(todo(tokenB), "draftPayroll"), "B 站自己的结算单要算上");
        assertEquals(0, countOf(todo(tokenA), "draftPayroll"), "A 站不得看到 B 站的结算单");
        assertEquals(0, countOf(todo(tokenA), "pendingBarrelException"), "A 站不得看到 B 站的桶异常");

        assertFalse(todo(customerToken(a[2])).isSuccess(), "顾客不得查看站长待办（含本站欠款与人事数据）");
    }

    // ---------- helpers ----------

    /** @return {station, manager, customer, address, product} */
    private long[] seed() {
        return seed("待办站");
    }

    /**
     * 造一个"本站 + 已绑定客户 + 已上架但没填成本的商品"的最小自洽场景。
     *
     * <p>{@code tag} 让同一个用例里能造出两个互不相干的水站（不串站那一条要用），
     * 站名/商品名都带上它，避免撞上库里的唯一键。</p>
     */
    private long[] seed(String tag) {
        long station = createStation(tag);
        long manager = createStaff(tag + "长", "STATION_MANAGER", station, 1);
        long customer = createCustomer(tag + "客户", "todo-openid-" + tag);
        long address = createAddress(customer, tag + "小区 1 号");
        long product = createProduct(tag + "水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        createCustomerStationConfig(customer, station, 1);
        return new long[]{station, manager, customer, address, product};
    }
}
