package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送完成（{@code POST /api/delivery/orders/{id}/complete}）的请求体契约。
 *
 * <p><b>为什么要单独一个类</b>：这个端点在 f3e702f（2026-09-12）把请求体从裸 {@code Map}
 * 收敛成强类型 {@code DeliveryOrderActionDTO.Complete} 时，<b>漏抄了 {@code collected}
 * 与 {@code note} 两个键</b>。service 一直在读它们、配送端一直在发它们，而 Jackson 对未知字段
 * 静默忽略 —— 于是：</p>
 * <ul>
 *   <li>货到付款点「已收款」完成配送，后端一律按「未收款」处理：订单停在 已送达(3)、
 *       {@code payment_status} 被改写成 未付(0)、{@code recordCashCollection} 不执行（钱不入账、
 *       没有 PAID 流水），跨站收款的站别护栏也成了死代码
 *       （[AQ-043] 原口径「跨站单仅原归属站可确认收款」，2026-09-18 已按结算站修订，
 *       见本类 {@code crossStationCollectionIsConfirmedBySettleStation}）；</li>
 *   <li>配送员手填的配送备注写不进 {@code orders.special_note}。</li>
 * </ul>
 *
 * <p>这类"前端发了、后端收不到"的缺陷不会报错、不会进日志，跑 service 层的用例也发现不了
 * （直接调 service 时 Map 是自己拼的，字段当然在）。所以本类<b>只走 HTTP</b>，
 * 并且把「显式传 collected=true」与「完全不传」作为一对对照用例钉死。</p>
 */
@DisplayName("配送完成请求体契约 · collected / note 不得被静默丢弃")
class DeliveryCompleteIntegrationTest extends AbstractIntegrationTest {

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
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /**
     * 造一张「配送中」的现金单：qty 桶、其中 shortage 个是本单新购（下单时会记一条 PENDING 配送中）。
     *
     * <p>必须带 in-transit 行，否则 {@code applyDelivery} 会把 delivered 全额算成 over，
     * 模型自相矛盾（占用 = 权益 + over 会多算一倍）。真实下单路径一定会写这条。</p>
     */
    private long deliveringCashOrder(int qty, int shortage) {
        String water = new BigDecimal("20.00").multiply(BigDecimal.valueOf(qty)).toPlainString();
        String deposit = new BigDecimal("30.00").multiply(BigDecimal.valueOf(shortage)).toPlainString();
        String total = new BigDecimal(water).add(new BigDecimal(deposit)).toPlainString();
        long order = createOrderFull(customer, addr, station, product,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */, water, deposit, total, true, qty);
        createOrderItem(order, product, "桶装水18.9L", qty, "20.00", "30.00", 1);
        if (shortage > 0) {
            createBarrelInTransit(customer, station, product, shortage, "30.00", order, "PENDING");
        }
        return order;
    }

    /** 应收 qty、实收 returned 的回桶明细（少收的部分 = 客户欠桶）。 */
    private String returnBody(long orderId, int qty, int returned, String extraJson) {
        return "{\"itemReturns\":[{\"orderItemId\":" + firstItemId(orderId)
                + ",\"expected\":" + qty + ",\"actual\":" + returned
                + ",\"reasons\":[{\"key\":\"customer_kept\",\"qty\":" + (qty - returned) + "}]}]"
                + (extraJson == null ? "" : "," + extraJson) + "}";
    }

    private long firstItemId(long orderId) {
        return longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", orderId);
    }

    private int paidRecords(long orderId) {
        return intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", orderId);
    }

    @Test
    @DisplayName("现金单 + collected=true：订单直接已完成+已付，并补写现场收款流水与押金")
    void cashCollectedAtDelivery_completesAndBooksCash() {
        seed();
        long order = deliveringCashOrder(1, 1);

        Api res = post("/api/delivery/orders/" + order + "/complete", mgrToken(),
                returnBody(order, 1, 0, "\"collected\":true"));
        assertTrue(res.isSuccess(), "现场收款后完成配送应成功，实际=" + res);

        assertEquals(4, intOf("SELECT status FROM orders WHERE id=?", order), "应直接置 已完成(4)");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "应置 已付(2)");
        assertEquals(1, paidRecords(order), "必须补写一条 PAID 收款流水（否则日结无凭证）");
        assertEquals(0, decimalOf("SELECT amount FROM payment_record WHERE order_id=? AND status=2", order)
                        .compareTo(new BigDecimal("50.00")),
                "收款金额应取订单应收合计 50.00（水费20 + 押金30）");
        assertEquals(0, decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                                + "WHERE customer_id=? AND station_id=?", customer, station)
                        .compareTo(new BigDecimal("30.00")),
                "已付款后应把预收桶押金入账（applyDepositOnPaid）");
    }

    @Test
    @DisplayName("现金单 + collected=false：只置已送达，不得写 PAID，也不得入账押金")
    void cashNotCollectedAtDelivery_staysDeliveredUnpaid() {
        seed();
        long order = deliveringCashOrder(1, 1);

        Api res = post("/api/delivery/orders/" + order + "/complete", mgrToken(),
                returnBody(order, 1, 0, "\"collected\":false"));
        assertTrue(res.isSuccess(), "未收款也应允许完成配送（只是不能标已付），实际=" + res);

        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", order), "应停在 已送达(3)");
        // [2026-09-16] 状态不得倒滚：送达未收款仍是 待收款(1)，不能写成 未支付(0)。
        // 0 会让这笔应收从 DashboardMapper 的待收款合计里消失（口径 = payment_status=1 且未取消），
        // 站长就看不到该催谁。取餐时该单建的是 payment_status=1，这里必须原样保留。
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "送达未收款应保持 待收款(1)，不得倒回 未支付(0)");
        assertEquals(0, paidRecords(order), "未收款绝不能有 PAID 流水");
        assertEquals(0, decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                                + "WHERE customer_id=? AND station_id=?", customer, station)
                        .compareTo(BigDecimal.ZERO),
                "未付款不得入账押金");
    }

    @Test
    @DisplayName("回归锁：不传 collected 与传 collected=false 等价（字段被静默丢弃时必须被这条抓住）")
    void omittedCollectedBehavesAsNotCollected() {
        seed();
        long order = deliveringCashOrder(1, 1);

        // 这一条就是 f3e702f 之后的线上真实行为。如果哪天 DTO/映射又把 collected 丢了，
        // cashCollectedAtDelivery_completesAndBooksCash 会红，本条则保持绿 —— 两条一起才说明"传了有用"。
        Api res = post("/api/delivery/orders/" + order + "/complete", mgrToken(), returnBody(order, 1, 0, null));
        assertTrue(res.isSuccess(), "缺省 collected 应仍能完成配送，实际=" + res);

        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", order),
                "不传 collected 必须按『未收款』处理，不能默认已收");
        assertEquals(0, paidRecords(order), "不传 collected 不得写 PAID 流水");
    }

    @Test
    @DisplayName("note：配送员手填备注必须落进 orders.special_note")
    void deliveryNoteIsPersisted() {
        seed();
        long order = deliveringCashOrder(1, 1);

        Api res = post("/api/delivery/orders/" + order + "/complete", mgrToken(),
                returnBody(order, 1, 0, "\"collected\":true,\"note\":\"客户要求下次带两桶\""));
        assertTrue(res.isSuccess(), "带备注完成应成功，实际=" + res);

        String note = jdbc.queryForObject("SELECT special_note FROM orders WHERE id=?", String.class, order);
        assertTrue(note != null && note.contains("[配送备注] 客户要求下次带两桶"),
                "配送备注应追加到 special_note，实际=" + note);
    }

    /**
     * 跨站外派单的现金收款判权（2026-09-18 <b>按新口径反过来</b>）。
     *
     * <p><b>旧口径</b>（[AQ-043]，本用例原来断言的就是它）：跨站单的钱与欠桶账都归归属站，
     * 所以「仅原归属站可确认收款」，履约站替归属站点收款必须被拒。</p>
     *
     * <p><b>为什么反过来</b>：三站语义落地后（v47 {@code orders.settle_station_id}），跨站单的
     * 营收（水费 + 配送费 + 楼层费）归<b>结算站</b> = 抢单/定向外派成功后的<b>履约站</b>
     * —— 谁送谁收钱谁确认。继续要求归属站确认，等于让一个不拿这笔钱的人点"已收款"，
     * 而且他根本点不进来（{@code completeDelivery} 开头的 {@code checkStationOwnership}
     * 只放行履约站）。所以本条改成：<b>履约站（= 结算站）确认收款 → 闭环</b>；
     * <b>归属站来确认 → 被拒</b>（不是"他也能收"，而是他连这单都不是他的履约单）。</p>
     *
     * <p>⚠️ 与押金/欠桶的站别**不是**同一件事：钱认结算站，而押金入账、欠桶录入仍记<b>归属站</b>
     * （客户资产是"买在哪个站"）。本用例同时把这条不对称口径钉住。</p>
     */
    @Test
    @DisplayName("跨站外派单：由履约站（结算站）确认收款并闭环；归属站来确认被拒（AQ-043 已按结算站修订）")
    void crossStationCollectionIsConfirmedBySettleStation() {
        seed();
        long station2 = createStation("S2");
        long mgr2 = createStaff("M2", "STATION_MANAGER", station2, 1);

        // 归属站 S1、履约站 S2（结算站 = S2）：谁送谁收钱谁确认
        long order = createOrderCrossStation(customer, addr, station, station2, product,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */, "20.00", "30.00", "50.00");
        createOrderItem(order, product, "桶装水18.9L", 1, "20.00", "30.00", 1);
        createBarrelInTransit(customer, station, product, 1, "30.00", order, "PENDING");

        // ① 归属站 S1 来确认 → 拒（他不是履约站；这笔钱也已经不归他）
        Api byOwner = post("/api/delivery/orders/" + order + "/complete", mgrToken(),
                returnBody(order, 1, 0, "\"collected\":true"));
        assertFalse(byOwner.isSuccess(), "归属站不得确认跨站单收款，实际=" + byOwner);
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "被拒后订单状态不得变");
        assertEquals(0, paidRecords(order), "被拒后不得留下 PAID 流水");

        // ② 履约站 S2（= 结算站）确认 → 闭环 + 补 PAID 流水
        Api res = post("/api/delivery/orders/" + order + "/complete",
                staffToken(mgr2, "STATION_MANAGER", station2),
                returnBody(order, 1, 0, "\"collected\":true"));
        assertTrue(res.isSuccess(), "履约站（结算站）确认收款应成功，实际=" + res);

        assertEquals(4, intOf("SELECT status FROM orders WHERE id=?", order), "收款后应闭环为 已完成(4)");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "应置为已付款(2)");
        assertEquals(1, paidRecords(order), "必须补写 PAID 流水（账证一致）");

        // ③ 但押金仍记**归属站** S1 —— 钱认结算站，客户资产认归属站，这条不对称是有意的
        assertEquals(0, decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                                + "WHERE customer_id=? AND station_id=?", customer, station)
                        .compareTo(new BigDecimal("30.00")),
                "预收押金仍应入【归属站】S1 的账户（客户资产认归属站）");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_deposit_account WHERE customer_id=? AND station_id=?",
                customer, station2), "履约站/结算站不得出现这个客户的押金账户");
    }
}
