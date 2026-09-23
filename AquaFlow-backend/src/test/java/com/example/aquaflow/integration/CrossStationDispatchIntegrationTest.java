package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转单 / 外派 / 抢单池的跨站链路。
 *
 * <p>这是本仓库**最容易真丢钱**的一片区域：一单可以「归属站 ≠ 履约站」，于是必须时刻分清
 * <b>三列</b>（v47 起是三列不是两列，正本 {@code sql/migration_v47_order_settle_station.sql}）：
 * <b>归属站 {@code station_id}</b>（客户主动选定的站 = <b>定价方</b>）、
 * <b>履约站 {@code delivery_station_id}</b>（谁去送：库存、配送员、工钱）、
 * <b>结算站 {@code settle_station_id}</b>（<b>本单营收归谁</b>：水费 + 配送费 + 楼层费）。
 * 外派 / 抢单 / 放池改的是<b>后两列（一起改）</b>，归属站自始至终不变；
 * 而<b>押金 / 水票 / 桶权益一律按归属站</b> —— 「营收跟着送货的站走、客户资产留在买它的站」
 * 就是本类的每一条断言在守的线。</p>
 *
 * <p>链路（都走 HTTP，不直接调 service）：</p>
 * <ul>
 *   <li>放抢单池：{@code POST /api/delivery/orders/transfer/{id}/outsource}（{@code targetStationId} 留空）→
 *       履约站与配送员被清空、订单回到待配送、结算站回归属站；归属站不变。</li>
 *   <li>抢单：目标站 {@code GET /api/delivery/orders/pool} 看得到 → {@code POST .../{id}/claim-pool} →
 *       履约站与结算站都变成抢单站、状态推到配送中。</li>
 *   <li>召回：{@code POST .../{id}/cancel-dispatch} → 履约站与结算站都恢复成归属站、配送员清空。</li>
 *   <li>指定外派：{@code .../outsource} 带 {@code targetStationId}（不能是自己）。</li>
 *   <li>站内转单：{@code POST .../transfer/{id}} 直接改派并落 {@code order_transfer} 结构化记录。</li>
 * </ul>
 */
@DisplayName("跨站调度 · 外派 / 抢单池 / 召回 / 站内转单（营收随履约站，押金与桶权益留归属站）")
class CrossStationDispatchIntegrationTest extends AbstractIntegrationTest {

    private long stationA;
    private long stationB;
    private long product;
    private long customer;
    private long addr;
    private long mgrA;
    private long mgrB;
    private long driverB;

    private void seed() {
        stationA = createStation("A站");
        stationB = createStation("B站");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(stationA, product, 50, 1, "18.00");
        createInventoryFull(stationB, product, 50, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgrA = createStaff("MA", "STATION_MANAGER", stationA, 1);
        mgrB = createStaff("MB", "STATION_MANAGER", stationB, 1);
        driverB = createStaff("DB", "DELIVERY", stationB, 1);
    }

    private String tokenA() {
        return staffToken(mgrA, "STATION_MANAGER", stationA);
    }

    private String tokenB() {
        return staffToken(mgrB, "STATION_MANAGER", stationB);
    }

    /**
     * A 站的一张待配送订单（履约站也是 A）。
     *
     * <p>⚠️ [2026-09-18] 刻意造成<b>不含桶、不收押金</b>的单（{@code delivery_bucket_qty = 0}）：
     * 产品裁定「涉押金/桶权益的单禁止外派直接拒单；如确需外派只能指定水站并由双方确认」之后，
     * 要核对回桶的老客单（{@code delivery_bucket_qty > 0 && first_barrel_order = 0}）与收押金的单
     * 都进不了抢单池（判据 {@code OrderWorkflowServiceImpl.involvesDepositOrBarrelRights}）。
     * 本类验证的是<b>外派机制本身</b>（CAS、履约站、留痕、召回、转单），所以用能合法外派的普通单造数；
     * 被拒的那一侧由 {@code CrossStationPoolRiskAndProfileIsolationIntegrationTest} 覆盖。</p>
     */
    private long pendingOrderAtA() {
        return createOrderFull(customer, addr, stationA, product,
                1 /* 待配送 */, 1 /* 待收款 */, 2 /* 现金 */,
                "40.00", "0.00", "40.00", false, 0);
    }

    private String deliveryStationOf(long orderId) {
        return jdbc.queryForObject("SELECT IFNULL(delivery_station_id,'NULL') FROM orders WHERE id=?",
                String.class, orderId);
    }

    private long ownerStationOf(long orderId) {
        return longOf("SELECT station_id FROM orders WHERE id=?", orderId);
    }

    private String specialNote(long orderId) {
        String s = jdbc.queryForObject("SELECT IFNULL(special_note,'') FROM orders WHERE id=?", String.class, orderId);
        return s == null ? "" : s;
    }

    private boolean poolContains(String token, long orderId) {
        Api res = get("/api/delivery/orders/pool", token);
        assertTrue(res.isSuccess(), "抢单池列表应可读，实际=" + res);
        for (JsonNode n : res.data()) {
            if (n.path("id").asLong() == orderId) return true;
        }
        return false;
    }

    @Test
    @DisplayName("放池 → 目标站可见并抢单：履约站换人、归属站不变、留痕齐全")
    void outsourceToPoolThenOtherStationClaims() {
        seed();
        long order = pendingOrderAtA();

        Api out = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(),
                "{\"reason\":\"本站运力不足\"}");
        assertTrue(out.isSuccess(), "放入抢单池应成功，实际=" + out);

        assertEquals("NULL", deliveryStationOf(order), "放池后履约站必须清空（这是「在池中」的唯一标志）");
        assertEquals(stationA, ownerStationOf(order), "归属站永远不变：钱和票还记 A 站");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "放池不推进状态，仍是待配送");
        assertTrue(specialNote(order).contains("[外派]") && specialNote(order).contains("抢单池"),
                "必须留痕，实际=" + specialNote(order));

        assertFalse(poolContains(tokenA(), order), "归属站自己的池子里不该看到自己的单");
        assertTrue(poolContains(tokenB(), order), "目标站应能在抢单池里看到这一单");

        Api claim = post("/api/delivery/orders/" + order + "/claim-pool", tokenB(),
                "{\"deliveryStaffId\":" + driverB + "}");
        assertTrue(claim.isSuccess(), "抢单应成功，实际=" + claim);

        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "履约站应变为抢单站 B");
        assertEquals(driverB, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order), "配送员应为指定的 B 站配送员");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "抢单即接单，状态推到配送中(2)");
        assertEquals(stationA, ownerStationOf(order), "抢单不得改动归属站");
        assertTrue(specialNote(order).contains("[抢单]"), "必须留痕，实际=" + specialNote(order));
    }

    @Test
    @DisplayName("抢单只能成一次：已出池的单再抢必须被拒")
    void claimPoolIsOnceOnly() {
        seed();
        long stationC = createStation("C站");
        long mgrC = createStaff("MC", "STATION_MANAGER", stationC, 1);

        long order = pendingOrderAtA();
        assertTrue(post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}").isSuccess());
        assertTrue(post("/api/delivery/orders/" + order + "/claim-pool", tokenB(),
                "{\"deliveryStaffId\":" + driverB + "}").isSuccess(), "B 先抢到");

        Api again = post("/api/delivery/orders/" + order + "/claim-pool",
                staffToken(mgrC, "STATION_MANAGER", stationC), "{\"deliveryStaffId\":" + mgrC + "}");
        assertFalse(again.isSuccess(), "被抢走的单不能再抢，实际=" + again);
        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "履约站不得被二次抢单覆盖");
    }

    @Test
    @DisplayName("取消外派：召回为归属站待配送，配送员清空")
    void cancelDispatchRecallsToOwnerStation() {
        seed();
        long order = pendingOrderAtA();
        assertTrue(post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}").isSuccess());

        Api back = post("/api/delivery/orders/" + order + "/cancel-dispatch", tokenA(), null);
        assertTrue(back.isSuccess(), "归属站应能取消外派，实际=" + back);

        assertEquals(stationA, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "召回后履约站恢复为归属站");
        assertEquals("NULL", jdbc.queryForObject("SELECT IFNULL(delivery_staff_id,'NULL') FROM orders WHERE id=?",
                String.class, order), "召回后不该还挂着别人家的配送员");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "召回后回到待配送");
        assertTrue(specialNote(order).contains("[取消外派]"), "必须留痕，实际=" + specialNote(order));
    }

    /**
     * **「还没被接单之前，这单仍算归属站的」**（2026-09-22 产品裁定）。
     *
     * <p>定向外派会把 {@code delivery_station_id} 直接改成目标站，而对方<b>接单</b>才把状态推到
     * {@code 配送中(2)}。中间那段"已指定、没人接"的窗口里，判权若只看"是不是当前履约站"，
     * 归属站就<b>连改派都做不到</b> —— 而小程序上「重新外派」正是照 {@code status === 1} 显示的，
     * 点了必报"仅能操作本站订单"（本用例第 ① 步修的就是它）。</p>
     *
     * <p>被接单之后自动失效：与「被接单后归接单站管」是同一条线（{@code cancelDispatch} 同理）。</p>
     */
    @Test
    @DisplayName("未被接单前归属站可重新外派 / 退回池；被接单后两个动作都关掉")
    void ownerKeepsDispatchRightsUntilTheOtherStationAccepts() {
        seed();
        long order = pendingOrderAtA();

        // A 定向外派给 B —— 状态仍是 待配送(1) = 对方还没接单
        assertTrue(post("/api/delivery/orders/" + order + "/dispatch", tokenA(),
                "{\"targetStationId\":" + stationB + "}").isSuccess(), "定向外派应成功");
        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order));
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "定向外派不改状态");

        // ① 归属站可以**改派**（修复前这里必被拒）
        Api reDispatch = post("/api/delivery/orders/" + order + "/dispatch", tokenA(),
                "{\"targetStationId\":" + stationB + "}");
        assertTrue(reDispatch.isSuccess(), "还没被接单时归属站应能重新外派，实际=" + reDispatch);

        // ② 归属站也可以把它**退回抢单池**
        Api backToPool = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}");
        assertTrue(backToPool.isSuccess(), "还没被接单时归属站应能退回池里，实际=" + backToPool);
        assertEquals("NULL", deliveryStationOf(order), "退回池后没有履约站");
        assertEquals(stationA, longOf("SELECT settle_station_id FROM orders WHERE id=?", order),
                "退回池 = 营收回归属站");

        // ③ B 接单之后：这单归 B 管，归属站两个动作都做不了了
        assertTrue(post("/api/delivery/orders/" + order + "/dispatch", tokenA(),
                "{\"targetStationId\":" + stationB + "}").isSuccess(), "再次定向外派应成功");
        assertTrue(post("/api/delivery/orders/" + order + "/accept",
                staffToken(driverB, "DELIVERY", stationB), "{}").isSuccess(), "B 接单应成功");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "接单后应是配送中");

        Api lateRedispatch = post("/api/delivery/orders/" + order + "/dispatch", tokenA(),
                "{\"targetStationId\":" + stationB + "}");
        assertFalse(lateRedispatch.isSuccess(), "被接单后归属站不得再改派，实际=" + lateRedispatch);
        Api latePool = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}");
        assertFalse(latePool.isSuccess(), "被接单后归属站不得再退回池，实际=" + latePool);
        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "两次被拒之后履约站必须仍是 B");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "状态也不得被动");
    }

    /**
     * 召回的时间窗：**只在"还没被接单"时**（2026-09-22 产品裁定）。
     *
     * <p>原话：「外派出去的本单就不归本站管了，只能接单站管，联系等都是接单站执行」。
     * 落到代码上就是：一旦接单站把单接走（状态 → 配送中(2)），归属站的「取消外派」必须关掉。</p>
     *
     * <p>⚠️ 修之前这里放行 配送中(2)，并且 CAS 会把状态**改回** 待配送(1) ——
     * 那是归属站把别站正在送的单抢回来（可能出现两个配送员送同一张单），
     * 而货已经在接单站车上，"取消外派"根本拦不住这件事。</p>
     */
    @Test
    @DisplayName("已被接单的单：归属站不能再取消外派（被接单后归接单站管）")
    void recallIsRefusedAfterTheOtherStationAccepts() {
        seed();
        long order = pendingOrderAtA();
        // 放池 → B 抢单（= B 接单），状态推进到配送中
        assertTrue(post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}").isSuccess());
        assertTrue(post("/api/delivery/orders/" + order + "/claim-pool",
                        staffToken(mgrB, "STATION_MANAGER", stationB),
                        "{\"deliveryStaffId\":" + driverB + "}").isSuccess(),
                "B 抢单应成功");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "抢单后应是配送中");

        Api denied = post("/api/delivery/orders/" + order + "/cancel-dispatch", tokenA(), null);
        assertFalse(denied.isSuccess(), "B 已接单后归属站不得再召回，实际=" + denied);
        assertTrue(denied.message() != null && denied.message().contains("接单站"),
                "拒绝文案要说清「归接单站管」，实际=" + denied.message());

        // 被拒之后三列一个都不许动
        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "召回被拒后履约站必须仍是接单站");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order),
                "状态不得被改回待配送（那正是修之前的行为）");
    }

    @Test
    @DisplayName("指定水站外派：履约站换人、状态不变、外派追踪列表可见；不能外派给自己")
    void outsourceToNamedStation() {
        seed();
        long order = pendingOrderAtA();

        Api self = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(),
                "{\"targetStationId\":" + stationA + "}");
        assertFalse(self.isSuccess(), "不能外派给自己，实际=" + self);

        Api res = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(),
                "{\"targetStationId\":" + stationB + ",\"reason\":\"指定 B 站送\"}");
        assertTrue(res.isSuccess(), "指定外派应成功，实际=" + res);

        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order));
        assertEquals(stationA, ownerStationOf(order), "归属站不变");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "指定外派也不推进状态");
        assertFalse(poolContains(tokenB(), order),
                "指定外派的单**不进抢单池**（履约站已经定了，池子只放 delivery_station_id 为空的单）");

        Api tracking = get("/api/delivery/orders/dispatch-tracking", tokenA());
        assertTrue(tracking.isSuccess(), "外派追踪应可读，实际=" + tracking);
        boolean seen = false;
        for (JsonNode n : tracking.data()) {
            if (n.path("id").asLong() == order) seen = true;
        }
        assertTrue(seen, "归属站的外派追踪列表里应能看到这一单");
    }

    @Test
    @DisplayName("跨站现金单：履约站完成配送并确认收款，但钱/押金/桶账全部记在归属站")
    void crossStationCashFlowsToOwnerStation() {
        seed();
        // 归属站 A、履约站 B（A 指派给 B 去送）
        long order = createOrderCrossStation(customer, addr, stationA, stationB, product,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */, "40.00", "60.00", "100.00");
        long itemId = createOrderItem(order, product, "桶装水18.9L", 2, "20.00", "30.00", 1);
        createBarrelInTransit(customer, stationA, product, 2, "30.00", order, "PENDING");

        // 谁操作：完成配送与确认收款都只认【履约站】(checkStationOwnership)，归属站连完成都做不了
        Api byOwner = post("/api/delivery/orders/" + order + "/complete", tokenA(), "{}");
        assertFalse(byOwner.isSuccess(), "归属站不是履约站，不得完成配送，实际=" + byOwner);
        assertTrue(byOwner.message().contains("无权操作他站订单"),
                "拒绝原因应指向站别，实际=" + byOwner.message());

        // 履约站 B 完成配送，且现场**未**收款 → 只到已送达，钱还没到手
        Api done = post("/api/delivery/orders/" + order + "/complete", tokenB(),
                "{\"itemReturns\":[{\"orderItemId\":" + itemId + ",\"expected\":2,\"actual\":0,"
                        + "\"reasons\":[{\"key\":\"customer_kept\",\"qty\":2}]}]}");
        assertTrue(done.isSuccess(), "履约站完成配送应成功，实际=" + done);
        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", order), "未收款 → 停在已送达(3)");
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order), "仍是待收款(1)");

        // B 现场收到现金后再确认收款 → 订单闭环。
        // ⚠️ [2026-09-18 产品裁定 / v47] 从这里往下，**营收与客户资产分成两个站**：
        //   · **收款流水（= 营收凭证）记【结算站】B** —— 「配送费要改，还有水费也一起给实际配送站」，
        //     结算站就是"这单的钱归谁"（`orders.settle_station_id`，本单 = 履约站 B）；
        //   · **押金账户 / 押金流水 / 桶权益仍记【归属站】A** —— 那是"客户买在哪个站的资产"，
        //     与营收归谁是两件事（AGENTS §1.1）。改这条线之前先读 v47 迁移文件头。
        // 本条断言原写作"收款流水必须记在【归属站】A"，是 v47 之前的旧口径，已随产品裁定订正。
        Api pay = post("/api/delivery/orders/" + order + "/confirm-offline-pay", tokenB(), null);
        assertTrue(pay.isSuccess(), "履约站确认线下收款应成功，实际=" + pay);

        assertEquals(4, intOf("SELECT status FROM orders WHERE id=?", order), "收款后订单闭环为已完成");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "应已付款");
        assertEquals(stationB, longOf("SELECT station_id FROM payment_record WHERE order_id=? AND status=2", order),
                "收款流水（营收凭证）必须记【结算站】B —— 水费+配送费+楼层费归实际配送站（v47）");
        assertEquals(0, decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                        + "WHERE customer_id=? AND station_id=?", customer, stationA)
                        .compareTo(new BigDecimal("60.00")), "预收押金应入 A 站账户");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_deposit_account WHERE customer_id=? AND station_id=?",
                customer, stationB), "履约站 B 不得出现这个客户的押金账户");

        assertEquals(2, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND status=1", customer, stationA), "桶权益记在归属站 A");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_barrel_lot WHERE customer_id=? AND station_id=?",
                customer, stationB), "履约站 B 不得出现桶权益");
    }

    @Test
    @DisplayName("站内转单：改派配送员并落结构化 order_transfer；配送员只能转自己名下的单")
    void transferToStaffIsRecordedAndScoped() {
        seed();
        long driverA1 = createStaff("DA1", "DELIVERY", stationA, 1);
        long driverA2 = createStaff("DA2", "DELIVERY", stationA, 1);

        long order = pendingOrderAtA();
        String t1 = staffToken(driverA1, "DELIVERY", stationA);
        String t2 = staffToken(driverA2, "DELIVERY", stationA);

        // 先把单派给 DA1
        assertTrue(post("/api/delivery/orders/assign/" + order, tokenA(),
                "{\"deliveryStaffId\":" + driverA1 + "}").isSuccess(), "站长派单应成功");
        assertEquals(driverA1, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order));

        // DA2 想转一张不在自己名下的单 → 拒
        Api notMine = post("/api/delivery/orders/transfer/" + order, t2,
                "{\"deliveryStaffId\":" + driverA2 + ",\"reason\":\"不是我的单\"}");
        assertFalse(notMine.isSuccess(), "配送员不得转让别人名下的单，实际=" + notMine);
        assertEquals(driverA1, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order), "被拒后配送员不变");

        // DA1 转给 DA2 → 成功、结构化记录落库
        Api ok = post("/api/delivery/orders/transfer/" + order, t1,
                "{\"deliveryStaffId\":" + driverA2 + ",\"reason\":\"我请假\"}");
        assertTrue(ok.isSuccess(), "转让自己的单应成功，实际=" + ok);
        assertEquals(driverA2, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order), "配送员应换成 DA2");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? AND kind='STAFF' "
                + "AND sub_kind='TRANSFER'", order), "应落一条结构化转单记录（事后可追溯，而不是只写备注）");
        assertTrue(specialNote(order).contains("[转让]"), "必须留痕，实际=" + specialNote(order));

        // ===== 撤回转让：[2026-09-18 修] 旧实现只有站级校验，缺两条判据 =====
        // ① 没有校验"调用者是不是发起人"：转单后 delivery_staff_id 立即变成 DA2，
        //    于是**同站任意第三个配送员**都能把这条转单撤掉 —— DA2 的"待接收"里静默少一条，
        //    而订单还挂在 DA2 名下、状态不变。
        // ② 丢弃 resolvePendingByKind 的受影响行数（它自己的 javadoc 写着"0 表示无待决策转单"）：
        //    对没有待决策转单的订单也返回成功，只往 special_note 塞一行「[取消转让]」（会显示在转单页的"备注"里）。
        long driverA3 = createStaff("DA3", "DELIVERY", stationA, 1);
        String t3 = staffToken(driverA3, "DELIVERY", stationA);
        String noteBefore = specialNote(order);

        Api byOther = post("/api/delivery/orders/transfer/" + order + "/cancel", t3, null);
        assertFalse(byOther.isSuccess(), "同站非发起人不得撤回别人的转单，实际=" + byOther);
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM order_transfer WHERE order_id=? AND sub_kind='TRANSFER'", String.class, order),
                "被拒之后转单必须还是待决策");
        assertEquals(noteBefore, specialNote(order), "被拒不得往 special_note 里塞「[取消转让]」");

        // 发起人 DA1 撤回 → 成功，待决策的转单记录置为已取消
        Api cancel = post("/api/delivery/orders/transfer/" + order + "/cancel", t1, null);
        assertTrue(cancel.isSuccess(), "发起人撤回自己的转单应成功，实际=" + cancel);
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM order_transfer WHERE order_id=? AND sub_kind='TRANSFER'", String.class, order),
                "待决策的转单应被置为已取消");

        // 已经没有待决策转单了 → 必须**报错**，不能静默成功（§8.17「用户以为做成了、账上没动」）
        Api again = post("/api/delivery/orders/transfer/" + order + "/cancel", t1, null);
        assertFalse(again.isSuccess(), "没有待决策转单时必须拒绝，实际=" + again);

        // 站长代撤：站务常态，保留（既有断言用的就是站长令牌）
        Api back = post("/api/delivery/orders/transfer/" + order, t2,
                "{\"deliveryStaffId\":" + driverA1 + ",\"reason\":\"转回去\"}");
        assertTrue(back.isSuccess(), "DA2 转回 DA1 应成功，实际=" + back);
        assertTrue(post("/api/delivery/orders/transfer/" + order + "/cancel", tokenA(), null).isSuccess(),
                "站长应能代撤本站的待决策转单");
    }

    @Test
    @DisplayName("站长拒单：直接取消走退款；选外派则回到抢单池且状态回到待配送")
    void stationRejectCancelsOrDispatches() {
        seed();
        // 拒单取消：订单终止、库存回补（用待配送现金单，未付款不涉及退款）
        long order1 = pendingOrderAtA();
        Api cancel = post("/api/delivery/orders/" + order1 + "/station-reject", tokenA(),
                "{\"reason\":\"客户地址无法配送\",\"tryDispatch\":false}");
        assertTrue(cancel.isSuccess(), "站长拒单应成功，实际=" + cancel);
        assertEquals(5, intOf("SELECT status FROM orders WHERE id=?", order1), "拒单取消应置已取消(5)");

        // 拒单外派：进池、状态仍是待配送、履约站清空
        long order2 = pendingOrderAtA();
        Api dispatch = post("/api/delivery/orders/" + order2 + "/station-reject", tokenA(),
                "{\"reason\":\"本站送不了，外派\",\"tryDispatch\":true}");
        assertTrue(dispatch.isSuccess(), "拒单外派应成功，实际=" + dispatch);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order2), "外派支线不取消订单");
        assertEquals("NULL", deliveryStationOf(order2), "外派支线应把订单放回抢单池");
        assertTrue(poolContains(tokenB(), order2), "B 站应能在池里看到它");
    }
}
