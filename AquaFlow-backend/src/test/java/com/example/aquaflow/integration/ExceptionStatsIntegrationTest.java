package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长端「桶异常统计看板」：{@code GET /api/manager/exceptions/stats}。
 *
 * <p>异常记录的<b>产生与处置</b>由 {@code BarrelExceptionFlowIntegrationTest} 覆盖；这里只测聚合口径，
 * 所以直接按表结构种几行（与 {@code StationAssetBackfillIntegrationTest} 的造数方式一致），
 * 让"统计算得对不对"这件事不被配送流程的细节掩盖。</p>
 *
 * <p>被锁死的口径：{@code totalCount}/{@code byCategory} 按 {@code created_at} 落在区间内统计；
 * 补偿金额<b>只统计 status=EXECUTED 的行</b>（没执行的补偿不能算进"已补偿"）；按登录站长的水站隔离。</p>
 */
@DisplayName("站长看板 · 桶异常统计（分类 / 补偿金额 / 时间区间 / 按站隔离）")
class ExceptionStatsIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 50, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
        // 异常单要挂在真实订单上（order_id 是外键语义），顺手造一张
        createOrderFull(customer, addr, station, product, 4, 2, 2, "20.00", "0.00", "20.00", false, 1);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /** 种一条异常单；status=EXECUTED 时带上已补偿金额。 */
    private long seedException(long stationId, String category, String status, String refundCash) {
        long orderId = longOf("SELECT id FROM orders WHERE station_id=? ORDER BY id LIMIT 1", stationId);
        return insert("INSERT INTO order_barrel_exception(order_id, customer_id, station_id, delivery_qty, "
                        + "return_qty, discrepancy, category, staff_action, manager_action, refund_cash_amount, "
                        + "refund_ticket_qty, status, created_at, executed_at) "
                        + "VALUES (?,?,?,2,0,2,?,'PARTIAL','APPROVE',?,0,?,NOW(),?)",
                orderId, customer, stationId, category, new BigDecimal(refundCash), status,
                "EXECUTED".equals(status) ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
    }

    private Api stats(String token) {
        return get("/api/manager/exceptions/stats", token);
    }

    @Test
    @DisplayName("看板要算上「今天」：刚产生的异常必须出现在默认区间里")
    void statsIncludesToday() {
        seed();
        seedException(station, "RETURN_SHORT", "STAFF_RECORDED", "0.00");

        Api res = stats(mgrToken());
        assertTrue(res.isSuccess(), "统计应可读，实际=" + res);
        // 默认区间是「最近 30 天 ~ 今天」。若结束边界按今天 00:00 比较，今天新增的异常会被整批漏掉
        // —— 站长看到的看板永远是"截至昨天"，刚发生的问题一条都不显示（排查时极易误判为"没异常"）。
        assertEquals(1, res.data().path("totalCount").asLong(),
                "今天新增的异常必须计入默认区间，实际=" + res.data());
        assertEquals(1, res.data().path("byCategory").path("RETURN_SHORT").asLong(),
                "分类分布也要算上今天");
    }

    @Test
    @DisplayName("补偿金额只认已执行(EXECUTED)，未执行的不得计入")
    void compensationCountsOnlyExecuted() {
        seed();
        seedException(station, "RETURN_SHORT", "EXECUTED", "30.00");
        seedException(station, "RETURN_SHORT", "IGNORED", "999.00");   // 没执行 → 不计
        seedException(station, "RETURN_OVER", "STAFF_RECORDED", "888.00");

        Api res = stats(mgrToken());
        assertTrue(res.isSuccess(), "统计应可读，实际=" + res);

        assertEquals(3, res.data().path("totalCount").asLong(), "三类异常都应计入总数");
        assertEquals(2, res.data().path("byCategory").path("RETURN_SHORT").asLong(), "少回 2 条");
        assertEquals(1, res.data().path("byCategory").path("RETURN_OVER").asLong(), "多回 1 条");
        assertEquals(0, new BigDecimal(res.data().path("totalRefundCash").asText())
                        .compareTo(new BigDecimal("30.00")),
                "只统计 EXECUTED 行的补偿金额，实际=" + res.data().path("totalRefundCash"));
    }

    @Test
    @DisplayName("按登录站长的水站隔离；顾客/配送员不得查看")
    void statsIsStationScopedAndGuarded() {
        seed();
        seedException(station, "RETURN_SHORT", "EXECUTED", "10.00");

        long station2 = createStation("S2");
        long mgr2 = createStaff("M2", "STATION_MANAGER", station2, 1);
        Api other = stats(staffToken(mgr2, "STATION_MANAGER", station2));
        assertTrue(other.isSuccess(), "他站站长自己那站也能查，实际=" + other);
        assertEquals(0, other.data().path("totalCount").asLong(),
                "不得看到本站的异常统计（跨租户）");

        assertFalse(stats(customerToken(customer)).isSuccess(), "顾客不得查看异常统计");
        assertEquals(1, stats(mgrToken()).data().path("totalCount").asLong(), "本站自己仍能查到");
    }
}
