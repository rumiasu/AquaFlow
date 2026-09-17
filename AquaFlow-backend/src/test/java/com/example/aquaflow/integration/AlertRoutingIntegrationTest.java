package com.example.aquaflow.integration;

import com.example.aquaflow.exception.GlobalExceptionHandler;
import com.example.aquaflow.service.ReconciliationService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分级告警的**投递路由**：系统故障 → 系统管理员；运营故障 → 该站站长（各司其职）。
 *
 * <p>产品口径（2026-09-16）：「异常产生后通知站长做，但是分系统故障通知我、运营故障通知站长」。
 * 本类只验证**路由与可见性**，不重复验证业务链路（桶异常的产生与处置见
 * {@code BarrelExceptionFlowIntegrationTest}）：</p>
 *
 * <ul>
 *   <li>{@code alert_type='OPERATION'} 必须带 {@code station_id} 与收件站长，且**只对本站可见**；</li>
 *   <li>{@code alert_type='SYSTEM'} 必须 {@code station_id IS NULL}，且**站长端一条都看不到**
 *       （它带平台级细节，站长无权知情也修不了）；</li>
 *   <li>补偿执行失败要投系统告警 —— 而且**业务回滚不能把这条告警带走**
 *       （落库走独立事务，见 {@code AlertServiceImpl} 的注释）。</li>
 * </ul>
 */
@DisplayName("分级告警路由 · 系统故障→系统管理员 / 运营故障→站长")
class AlertRoutingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    private long stationA;
    private long stationB;
    private long product;
    private long customer;
    private long addr;
    private long mgrA;
    private long mgrB;

    private void seed() {
        stationA = createStation("A站");
        stationB = createStation("B站");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(stationA, product, 50, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgrA = createStaff("MA", "STATION_MANAGER", stationA, 1);
        mgrB = createStaff("MB", "STATION_MANAGER", stationB, 1);
    }

    private String tokenA() {
        return staffToken(mgrA, "STATION_MANAGER", stationA);
    }

    private String tokenB() {
        return staffToken(mgrB, "STATION_MANAGER", stationB);
    }

    /** 直接种一条「待站长处置」的桶异常单（链路本身由 B1 覆盖，这里只关心它触发的告警）。 */
    private long seedPendingException(long stationId) {
        long order = createOrderFull(customer, addr, stationId, product, 4, 2, 2,
                "20.00", "0.00", "20.00", false, 1);
        return insert("INSERT INTO order_barrel_exception(order_id, customer_id, station_id, delivery_qty, "
                        + "return_qty, discrepancy, category, staff_action, status, created_at) "
                        + "VALUES (?,?,?,2,0,2,'RETURN_SHORT','PARTIAL','STAFF_RECORDED',NOW())",
                order, customer, stationId);
    }

    private int opAlerts() {
        return intOf("SELECT COUNT(*) FROM alert_log WHERE alert_type='OPERATION'");
    }

    private int sysAlerts() {
        return intOf("SELECT COUNT(*) FROM alert_log WHERE alert_type='SYSTEM'");
    }

    @Test
    @DisplayName("运营告警投给本站站长：落库带收件人，且只对本站可见")
    void operationAlertGoesToStationManager() {
        seed();
        long exId = seedPendingException(stationA);

        Api res = post("/api/manager/exceptions/" + exId + "/handle", tokenA(),
                "{\"action\":\"IGNORE\",\"managerNote\":\"客户已电话说明\"}");
        assertTrue(res.isSuccess(), "忽略应成功，实际=" + res);

        assertEquals(1, opAlerts(), "应产生 1 条运营告警");
        assertEquals(stationA, longOf("SELECT station_id FROM alert_log WHERE alert_type='OPERATION'"),
                "运营告警必须落在该水站上");
        assertEquals(mgrA, longOf("SELECT staff_id FROM alert_log WHERE alert_type='OPERATION'"),
                "收件人应记为该站站长");
        assertEquals("LOGGED", jdbc.queryForObject(
                "SELECT notify_status FROM alert_log WHERE alert_type='OPERATION'", String.class),
                "外部渠道未接入时应是 LOGGED（已落库、站内可查），不能是'没发生'");

        // 站长端可查本站运营告警
        Api mine = get("/api/manager/alerts", tokenA());
        assertTrue(mine.isSuccess(), "站长查本站告警应成功，实际=" + mine);
        assertEquals(1, mine.data().size(), "本站应看到这 1 条");
        assertEquals("OPERATION", mine.data().get(0).path("alertType").asText());

        // 他站站长看不到
        Api other = get("/api/manager/alerts", tokenB());
        assertTrue(other.isSuccess());
        assertEquals(0, other.data().size(), "他站站长不得看到本站告警（跨租户）");

        // 顾客不得查看
        assertFalse(get("/api/manager/alerts", customerToken(customer)).isSuccess(), "顾客不得查看告警");
    }

    @Test
    @DisplayName("补偿执行成功 = 运营告警（钱/票动过了要留痕）")
    void successfulCompensationRaisesOperationAlert() {
        seed();
        long exId = seedPendingException(stationA);

        Api res = post("/api/manager/exceptions/" + exId + "/handle", tokenA(),
                "{\"action\":\"APPROVE\",\"refundCashAmount\":10.00}");
        assertTrue(res.isSuccess(), "批准补偿应成功，实际=" + res);

        assertEquals(1, opAlerts(), "应产生 1 条运营告警");
        assertTrue(jdbc.queryForObject("SELECT title FROM alert_log WHERE alert_type='OPERATION'", String.class)
                        .contains("补偿已执行"),
                "标题应说明补偿已执行");
        assertEquals(0, sysAlerts(), "成功路径不该产生系统告警");
    }

    @Test
    @DisplayName("系统故障投给系统管理员：对账不平落 SYSTEM 告警，且站长端看不到")
    void reconcileImbalanceRaisesSystemAlert() {
        seed();
        // 押金账户有余额却没有对应流水 → 对账等式1 必然不平
        createDepositBalance(customer, stationA, "50.00");

        reconciliationService.dailyReconcile();

        assertTrue(sysAlerts() > 0, "对账不平必须产生系统告警");
        assertEquals("NULL", jdbc.queryForObject(
                        "SELECT IFNULL(station_id,'NULL') FROM alert_log WHERE alert_type='SYSTEM' ORDER BY id DESC LIMIT 1",
                        String.class),
                "系统告警不属于任何水站（这也是它不给站长看的判据）");
        assertTrue(jdbc.queryForObject(
                        "SELECT title FROM alert_log WHERE alert_type='SYSTEM' ORDER BY id DESC LIMIT 1", String.class)
                        .contains("对账"), "标题应指向对账");

        // 各司其职：站长端看不到系统告警
        Api mine = get("/api/manager/alerts", tokenA());
        assertTrue(mine.isSuccess());
        assertEquals(0, mine.data().size(), "系统故障是发给系统管理员的，站长端不得出现");
    }

    @Test
    @DisplayName("未预期的 500 也算系统故障（全站异常汇集点接入告警）")
    void unexpected500RaisesSystemAlert() {
        seed();
        int before = sysAlerts();

        globalExceptionHandler.handleRuntimeException(new RuntimeException("boom-for-test"));

        assertEquals(before + 1, sysAlerts(), "未预期异常应产生 1 条系统告警");
        assertEquals("GlobalExceptionHandler.runtime", jdbc.queryForObject(
                "SELECT source FROM alert_log WHERE alert_type='SYSTEM' ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    @DisplayName("补偿失败：投系统告警，且业务回滚不得把这条告警带走")
    void compensationFailureAlertsAdminEvenThoughBusinessRollsBack() {
        seed();
        long exId = seedPendingException(stationA);

        // 退水票但没给商品 → 补偿做不到（见 BarrelExceptionFlow 的同类用例）
        Api res = post("/api/manager/exceptions/" + exId + "/handle", tokenA(),
                "{\"action\":\"APPROVE\",\"refundTicketQty\":2}");
        assertFalse(res.isSuccess(), "缺商品应被拒，实际=" + res);

        // 业务确实回滚了
        assertEquals("STAFF_RECORDED", jdbc.queryForObject(
                "SELECT status FROM order_barrel_exception WHERE id=?", String.class, exId), "异常单应退回未处置");

        // 但告警必须留下来（独立事务）——这正是"业务一炸、告警也跟着消失"那个坑的反例
        assertEquals(1, sysAlerts(), "补偿失败必须留下系统告警，且不被回滚带走");
        assertTrue(jdbc.queryForObject("SELECT title FROM alert_log WHERE alert_type='SYSTEM'", String.class)
                        .contains("补偿执行失败"),
                "标题应指向补偿失败");
        assertEquals(0, opAlerts(), "失败的补偿不能留下'已执行'的运营告警");
    }
}
