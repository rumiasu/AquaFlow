package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单「结算站」显式化（v47，2026-09-18 产品裁定）。
 *
 * <p>盯五件事，前四件是产品裁定的直接判据、第五件防"顺手改错"：</p>
 * <ol>
 *   <li><b>跨站单（归属 A / 履约 B）的营收只在 B 出现</b> —— 看板 / 毛利（含成本 join）/ 应收台账
 *       三处同口径；A 一分不进。这四张报表此前各用一套站别口径（看板两级 coalesce、
 *       毛利与应收直接用 {@code station_id}），同一笔钱归两个站。</li>
 *   <li><b>同站单行为不变</b>（回归）：settle 与 station/delivery 同值，数字与升级前一致。</li>
 *   <li><b>v47 回填不改任何一个数字</b>：用同一份"改前形态"的数据（settle 全 NULL）先取一遍数字，
 *       跑迁移第 3 步的 UPDATE 后再取一遍 → 必须逐字一致（读侧三级 coalesce 的后两级就是旧口径）。</li>
 *   <li><b>履约站怎么变、结算站就怎么变</b>：放池/退回池 → 归属站；抢单/定向外派 → 履约站；召回 → 归属站。</li>
 *   <li><b>押金 / 水票 / 桶权益仍按归属站</b>：它们不是"营收"，是"客户买在哪个站的资产"，
 *       一律不得跟着结算站走（改错这条 = 客户退桶退不出钱、票凭空少）。</li>
 *   <li><b>待收款流水（{@code payment_record.station_id}）也跟着结算站走</b>：发起收款那一刻写死的站，
 *       在订单被抢单 / 定向外派 / 退回池 / 召回 / 指定退回-同意时必须一起搬；
 *       已收 / 已退的历史凭据不搬（那是已经发生过的钱）。</li>
 * </ol>
 *
 * <p>⚠️ 本用例的商品刻意选<b>非桶装水（category=2）且押金 0</b>：涉押金/桶权益的单在外派链路上
 * 另有一道"双方确认风险/禁止入池"的闸门（产品：押金不好划定，建议直接拒单），
 * 那条规则与本用例要盯的 settle 口径无关。用非桶装水把两者解耦，
 * 否则本用例会因为"另一条规则改了"而红/绿，测不出 settle 到底对不对。</p>
 */
@DisplayName("订单结算站 · 营收归谁（v47：水费+配送费+楼层费）")
class OrderSettleStationIntegrationTest extends AbstractIntegrationTest {

    private long stationA;
    private long stationB;
    private long mgrA;
    private long mgrB;
    private long driverB;
    private long customer;
    private long address;
    /** 非桶装水商品（category=2、押金 0）：不触发"涉押金/桶权益"的外派闸门，见类注释。 */
    private long goods;
    private String tokenA;
    private String tokenB;
    private String cus;

    private void seed() {
        stationA = createStation("结算A站");
        stationB = createStation("结算B站");
        mgrA = createStaff("结算A站长", "STATION_MANAGER", stationA, 1);
        mgrB = createStaff("结算B站长", "STATION_MANAGER", stationB, 1);
        driverB = createStaff("结算B配送员", "DELIVERY", stationB, 1);
        customer = createCustomer("结算客户", "settle-openid");
        address = createAddress(customer, "结算小区1号");
        goods = createProduct("结算饮水机", 2, "20.00", "0.00", 0, "0.00");
        createInventoryFull(stationA, goods, 100, 0, "0.00");
        createInventoryFull(stationB, goods, 100, 0, "0.00");
        createCustomerStationConfig(customer, stationA, 1);
        tokenA = staffToken(mgrA, "STATION_MANAGER", stationA);
        tokenB = staffToken(mgrB, "STATION_MANAGER", stationB);
        cus = customerToken(customer);
    }

    /** 设本站进货成本（毛利报表的成本 join 用它；站级，见 v39）。 */
    private void setCost(String token, String cost) {
        Api r = put("/api/manager/gross-profit/cost", token,
                "{\"productId\":" + goods + ",\"costPrice\":" + cost + "}");
        assertEquals(0, r.code(), "设置本站成本价应成功: " + r);
    }

    /** 走真实下单接口（客户在 A 站下单，现金支付）。 */
    private long placeOrder(int qty, String key) {
        Api r = post("/api/orders/create", cus, "{\"addressId\":" + address + ",\"stationId\":" + stationA
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"" + key + "\","
                + "\"items\":[{\"productId\":" + goods + ",\"quantity\":" + qty + "}]}");
        assertEquals(0, r.code(), "下单应成功: " + r);
        return longOf("SELECT id FROM orders WHERE idempotency_key=?", key);
    }

    private Long settleOf(long orderId) {
        return jdbc.queryForObject("SELECT settle_station_id FROM orders WHERE id=?", Long.class, orderId);
    }

    private String deliveryStationOf(long orderId) {
        return jdbc.queryForObject("SELECT IFNULL(delivery_station_id,'NULL') FROM orders WHERE id=?",
                String.class, orderId);
    }

    private BigDecimal dashboardGross(String token) {
        Api r = get("/api/dashboard/report?range=today", token);
        assertEquals(0, r.code(), "站长看板应可读: " + r);
        return r.data().path("summary").path("grossAmount").decimalValue();
    }

    private BigDecimal profitRevenue(String token) {
        return grossProfit(token).path("totalRevenue").decimalValue();
    }

    private BigDecimal profitCost(String token) {
        return grossProfit(token).path("totalCost").decimalValue();
    }

    private com.fasterxml.jackson.databind.JsonNode grossProfit(String token) {
        String today = LocalDate.now().toString();
        Api r = get("/api/manager/gross-profit?from=" + today + "&to=" + today, token);
        assertEquals(0, r.code(), "毛利报表应可读: " + r);
        return r.data();
    }

    private int receivableOrderCount(String token) {
        Api r = get("/api/manager/receivables/orders", token);
        assertEquals(0, r.code(), "应收台账应可读: " + r);
        return r.data().size();
    }

    /** 配送端「待收款订单数」（OrderMapper.countUncollected，与应收台账同一站别口径）。 */
    private int unpaidCount(String token) {
        Api r = get("/api/delivery/stats/today", token);
        assertEquals(0, r.code(), "配送端统计应可读: " + r);
        return r.data().path("unpaidOrders").asInt();
    }

    // ==================== ① 跨站单的营收归履约站 ====================

    @Test
    @DisplayName("跨站单（归 A / 履约 B）：营收只进 B 的看板/毛利/应收，A 一分不进")
    void crossStationRevenueCountsAtFulfillingStationOnly() {
        seed();
        // 两站进货价刻意不同：只改统计条件不改成本 join 的话，会用 A 站的进货价算 B 站的毛利
        setCost(tokenA, "12.00");
        setCost(tokenB, "5.00");

        long order = placeOrder(2, "settle-cross-1");
        assertEquals(stationA, settleOf(order), "下单时结算站 = 归属站（定价方）");
        BigDecimal amount = decimalOf("SELECT total_amount FROM orders WHERE id=?", order);
        assertEquals(0, new BigDecimal("40.00").compareTo(amount),
                "本用例商品 20×2、押金 0、运费 0，总额应为 40.00，实际=" + amount);

        // 定向外派给 B（真实链路：不是直接改库）
        Api out = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA,
                "{\"targetStationId\":" + stationB + "}");
        assertTrue(out.isSuccess(), "定向外派应成功: " + out);
        assertEquals(stationB, settleOf(order), "外派成功后结算站 = 履约站 B");
        assertEquals(stationA, longOf("SELECT station_id FROM orders WHERE id=?", order),
                "归属站（定价方）不变 —— 费用是下单时按 A 站算好的快照，外派不重算");

        // 看板
        assertEquals(0, dashboardGross(tokenB).compareTo(amount), "B 的看板应含这一单: " + amount);
        assertEquals(0, dashboardGross(tokenA).compareTo(BigDecimal.ZERO), "A 的看板不该含这一单");

        // 毛利（统计条件与成本 join 都要按结算站）
        assertEquals(0, profitRevenue(tokenB).compareTo(amount), "B 的毛利收入应含这一单");
        assertEquals(0, profitCost(tokenB).compareTo(new BigDecimal("10.00")),
                "成本必须取 B 站（结算站）的进货价 2×5=10；用 A 站的 12 元算 B 站的毛利 = 假毛利");
        assertEquals(0, profitRevenue(tokenA).compareTo(BigDecimal.ZERO), "A 的毛利报表不该含这一单");
        assertEquals(0, profitCost(tokenA).compareTo(BigDecimal.ZERO), "A 的成本也不该被这一单拉动");

        // 应收台账 + 待收款计数
        assertEquals(1, receivableOrderCount(tokenB), "B 的应收台账应有这一单");
        assertEquals(0, receivableOrderCount(tokenA), "A 的应收台账不该有这一单");
        assertEquals(1, unpaidCount(tokenB), "B 的「待收款订单数」应有这一单");
        assertEquals(0, unpaidCount(tokenA), "A 的「待收款订单数」不该有这一单");

        // 核销：列表看得到就必须销得掉（跨站单最坏的一种组合是"看得见、销不掉"）
        String body = "{\"customerId\":" + customer + ",\"orderIds\":[" + order + "]}";
        Api byA = post("/api/manager/receivables/settle", tokenA, body);
        assertNotEquals(0, byA.code(), "A 不是结算站，不得核销这一单");
        assertTrue(byA.message().contains("不属于本水站"),
                "拒绝原因应指向站别（而不是别的什么前置）：" + byA.message());
        assertEquals(1, intOf("SELECT settlement_status FROM orders WHERE id=?", order),
                "被拒的核销不得改动订单");

        Api byB = post("/api/manager/receivables/settle", tokenB, body);
        assertEquals(0, byB.code(), "B 是结算站，必须能核销: " + byB);
        assertEquals(1, byB.data().path("settledCount").asInt());
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "核销 ⟹ 已收款");
        assertEquals(stationB, longOf("SELECT station_id FROM payment_record WHERE order_id=? AND status=2", order),
                "核销补写的收款流水也记结算站（v47：钱跟着送货的站走）");
    }

    // ==================== ② 同站单行为不变（回归） ====================

    @Test
    @DisplayName("同站单（归属=履约=A）：行为与升级前一致，三列同值")
    void sameStationOrderIsUnchanged() {
        seed();
        setCost(tokenA, "12.00");
        long order = placeOrder(2, "settle-same-1");

        assertEquals(stationA, settleOf(order));
        assertEquals(stationA, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order));
        assertEquals(stationA, longOf("SELECT station_id FROM orders WHERE id=?", order));

        BigDecimal amount = decimalOf("SELECT total_amount FROM orders WHERE id=?", order);
        assertEquals(0, dashboardGross(tokenA).compareTo(amount), "A 的看板应有这一单");
        assertEquals(0, dashboardGross(tokenB).compareTo(BigDecimal.ZERO), "B 的看板不该有这一单");
        assertEquals(0, profitRevenue(tokenA).compareTo(amount), "A 的毛利应有这一单");
        assertEquals(0, profitCost(tokenA).compareTo(new BigDecimal("24.00")), "成本仍按 A 站进货价 2×12");
        assertEquals(1, receivableOrderCount(tokenA));
        assertEquals(0, receivableOrderCount(tokenB));
        assertEquals(1, unpaidCount(tokenA));
        assertEquals(0, unpaidCount(tokenB));

        // 同站单的核销照旧一条链路走通
        Api settle = post("/api/manager/receivables/settle", tokenA,
                "{\"customerId\":" + customer + ",\"orderIds\":[" + order + "]}");
        assertEquals(0, settle.code(), "同站单核销应成功: " + settle);
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order));
    }

    // ==================== ③ v47 回填不改数字 ====================

    /**
     * 同一份数据、回填前 vs 回填后，逐字比对。
     *
     * <p>造数刻意用<b>改前形态</b>（直接插库、{@code settle_station_id} 留 NULL）：
     * 那正是迁移要面对的存量行。回填跑的就是 {@code migration_v47} 第 3 步那一句 SQL。</p>
     */
    @Test
    @DisplayName("v47 回填前后统计逐字一致（同一份数据对比）+ 回填值等于旧口径")
    void backfillKeepsStatisticsIdentical() {
        seed();
        setCost(tokenA, "12.00");

        // 改前形态：同站单 + 跨站单，settle_station_id 全 NULL
        long same = createOrderFull(customer, address, stationA, goods,
                4 /* 已完成 */, 2 /* 已付 */, 2, "40.00", "0.00", "40.00", false, 0);
        createOrderItem(same, goods, "结算饮水机", 2, "20.00", "0.00", 2);
        long cross = createOrderCrossStation(customer, address, stationA, stationB, goods,
                4 /* 已完成 */, 2 /* 已付 */, 2, "60.00", "0.00", "60.00");
        createOrderItem(cross, goods, "结算饮水机", 3, "20.00", "0.00", 2);
        // 再来一张待收款（应收/待收款口径也要一起比）
        long unpaid = createOrderCrossStation(customer, address, stationA, stationB, goods,
                1 /* 待配送 */, 1 /* 待收款 */, 2, "20.00", "0.00", "20.00");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE settle_station_id IS NOT NULL"),
                "本用例必须先处于「改前形态」：settle_station_id 全为 NULL（三级 coalesce 靠后两级兜）");

        List<String> before = snapshot();
        // 回填前：三级 coalesce 必须与"旧口径（两级）"给出同一批订单
        assertEquals(before, snapshotFromLegacySql(), "settle 为 NULL 时，三级回退必须等价于升级前的两级口径");

        // —— 跑迁移第 3 步（与 migration_v47_order_settle_station.sql 逐字相同）——
        int backfilled = jdbc.update("UPDATE orders SET settle_station_id = coalesce(delivery_station_id, station_id) "
                + "WHERE settle_station_id IS NULL");
        assertEquals(3, backfilled, "三张单都应被回填");
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders "
                        + "WHERE NOT (settle_station_id <=> coalesce(delivery_station_id, station_id))"),
                "回填值必须逐行等于两级 coalesce（校验 C 的同款判据）");
        assertEquals(stationB, settleOf(cross), "跨站单回填到履约站");
        assertEquals(stationB, settleOf(unpaid), "跨站单回填到履约站");

        List<String> after = snapshot();
        assertEquals(before, after, "回填不得改动任何一个数字（看板/毛利/应收/待收款 四组，两站各一份）");

        // 二次回填影响 0 行（幂等），且数字仍然一致
        assertEquals(0, jdbc.update("UPDATE orders SET settle_station_id = coalesce(delivery_station_id, station_id) "
                + "WHERE settle_station_id IS NULL"), "二次回填必须影响 0 行");
        assertEquals(before, snapshot(), "二次回填后数字仍必须一致");
    }

    /** 一屏 8 个数字：两站 × （看板营业额 / 毛利收入 / 应收单数 / 待收款单数）。 */
    private List<String> snapshot() {
        List<String> out = new ArrayList<>();
        for (String token : List.of(tokenA, tokenB)) {
            out.add(norm(dashboardGross(token)));
            out.add(norm(profitRevenue(token)));
            out.add(String.valueOf(receivableOrderCount(token)));
            out.add(String.valueOf(unpaidCount(token)));
        }
        return out;
    }

    /**
     * 同一批数字的"升级前口径"算法：站别一律 {@code coalesce(delivery_station_id, station_id)}。
     * 用它把"回填后 API 给的数"与"旧 SQL 直接算的数"钉在一起 —— 只对比两次 API 调用，
     * 万一 SQL 改错了方向（例如漏了某张报表），两次都会错得一样、用例反而全绿。
     */
    private List<String> snapshotFromLegacySql() {
        List<String> out = new ArrayList<>();
        for (long stationId : List.of(stationA, stationB)) {
            out.add(norm(decimalOf("SELECT coalesce(sum(total_amount),0) FROM orders "
                    + "WHERE coalesce(delivery_station_id, station_id)=? AND status<>5", stationId)));
            out.add(norm(decimalOf("SELECT coalesce(sum(oi.subtotal),0) FROM order_item oi join orders o on o.id=oi.order_id "
                    + "WHERE coalesce(o.delivery_station_id, o.station_id)=? AND o.status<>5", stationId)));
            out.add(String.valueOf(intOf("SELECT count(*) FROM orders "
                    + "WHERE coalesce(delivery_station_id, station_id)=? AND payment_status=1 AND status<>5", stationId)));
            out.add(String.valueOf(intOf("SELECT count(*) FROM orders WHERE coalesce(delivery_station_id, station_id)=? "
                    + "AND status in (1,2,3) AND payment_method=2 AND payment_status<>2", stationId)));
        }
        return out;
    }

    /** 去掉尾零再比，避免 0 与 0.00 这种 scale 噪声把"逐字一致"误判成不一致。 */
    private static String norm(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    // ==================== ④ 履约站怎么变，结算站就怎么变 ====================

    @Test
    @DisplayName("放池/退回池→归属站；抢单/定向外派→履约站；召回→归属站（**只在对方接单之前**）")
    void settleFollowsDispatchLifecycle() {
        seed();
        long order = placeOrder(1, "settle-life-1");
        assertEquals(stationA, settleOf(order), "下单 = 归属站");

        // 放入抢单池（不指定目标站）
        Api pool = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA, "{}");
        assertTrue(pool.isSuccess(), "放入抢单池应成功: " + pool);
        assertEquals("NULL", deliveryStationOf(order), "在池中 = 无履约站");
        assertEquals(stationA, settleOf(order), "退货回池里，营收回归属站（池中没人履约）");

        // 池中没人接单 → 归属站可以召回，营收随之回归属站
        assertTrue(post("/api/delivery/orders/" + order + "/cancel-dispatch", tokenA, null).isSuccess(),
                "在池中（没人接单）归属站可以召回");
        assertEquals(stationA, settleOf(order), "召回 = 营收回归属站");

        // 定向外派 → 目标站**还没接单**（状态仍是待配送）→ 归属站仍可反悔召回
        Api direct = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA,
                "{\"targetStationId\":" + stationB + "}");
        assertTrue(direct.isSuccess(), "定向外派应成功: " + direct);
        assertEquals(stationB, settleOf(order), "定向外派 = 营收归目标站");
        assertTrue(post("/api/delivery/orders/" + order + "/cancel-dispatch", tokenA, null).isSuccess(),
                "对方还没接单，归属站仍可召回");
        assertEquals(stationA, settleOf(order), "取消外派 = 营收回归属站");

        // ============ 被接单之后：这单归接单站管，归属站不能再插手（2026-09-22 产品裁定） ============
        // 原话：「外派出去的本单就不归本站管了，只能接单站管，联系等都是接单站执行」。
        // ⚠️ 本次是本类**旧断言的反转**：`recall.isSuccess()` 曾经是被**断言为成功**的行为，
        //    现在必须被拒 —— 产品口径变了，不是实现坏了。
        long taken = placeOrder(1, "settle-life-2");
        assertTrue(post("/api/delivery/orders/transfer/" + taken + "/outsource", tokenA, "{}").isSuccess(),
                "放入抢单池应成功");
        assertTrue(post("/api/delivery/orders/" + taken + "/claim-pool", tokenB,
                        "{\"deliveryStaffId\":" + driverB + "}").isSuccess(),
                "B 抢单应成功");
        assertEquals(stationB, settleOf(taken), "抢单 = 营收归抢单站");

        Api recallAfterClaim = post("/api/delivery/orders/" + taken + "/cancel-dispatch", tokenA, null);
        assertFalse(recallAfterClaim.isSuccess(),
                "B 已接单后归属站不得再召回（这单归接单站管）: " + recallAfterClaim);
        assertEquals(stationB, settleOf(taken), "召回被拒后营收仍归接单站");
        assertEquals(String.valueOf(stationB), deliveryStationOf(taken), "履约站也不得被动");

        Api reDispatch = post("/api/delivery/orders/transfer/" + taken + "/outsource", tokenA,
                "{\"targetStationId\":" + stationA + "}");
        assertFalse(reDispatch.isSuccess(), "被接单后归属站也不能再改外派方向: " + reDispatch);

        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE id IN (?, ?) "
                        + "AND NOT (settle_station_id <=> coalesce(delivery_station_id, station_id))",
                        order, taken),
                "走完整条外派链路后，结算站与「两级 coalesce」必须始终同值（写入口径不分叉）");
    }

    // ==================== ⑤ 押金/水票/桶权益仍按归属站 ====================

    @Test
    @DisplayName("跨站单的押金/桶权益仍记归属站 A，只有营收与收款流水走结算站 B")
    void ownerAssetsStayAtOwnerStation() {
        seed();
        // 归属 A / 履约 B 的桶装水单（2 桶 × 30 押金 = 60）：改前形态直接插库，settle 按 v47 规则写
        long barrels = createProduct("结算桶装水", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(stationA, barrels, 100, 1, "18.00");
        createInventoryFull(stationB, barrels, 100, 1, "18.00");
        long order = createOrderCrossStation(customer, address, stationA, stationB, barrels,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */, "40.00", "60.00", "100.00");
        jdbc.update("UPDATE orders SET settle_station_id = delivery_station_id WHERE id=?", order);
        assertEquals(stationB, settleOf(order), "这是跨站单：营收归履约站 B");
        long itemId = createOrderItem(order, barrels, "结算桶装水", 2, "20.00", "30.00", 1);
        createBarrelInTransit(customer, stationA, barrels, 2, "30.00", order, "PENDING");
        createTicketAccount(customer, stationA, barrels, 5);

        // 履约站 B 完成配送（现场未收款）→ 桶权益入账（按归属站）
        Api done = post("/api/delivery/orders/" + order + "/complete", tokenB,
                "{\"itemReturns\":[{\"orderItemId\":" + itemId + ",\"expected\":2,\"actual\":0,"
                        + "\"reasons\":[{\"key\":\"customer_kept\",\"qty\":2}]}]}");
        assertTrue(done.isSuccess(), "履约站完成配送应成功: " + done);

        // B 确认线下收款（现金是履约站收的）→ 收款流水按结算站，押金入账按归属站
        Api pay = post("/api/delivery/orders/" + order + "/confirm-offline-pay", tokenB, null);
        assertTrue(pay.isSuccess(), "履约站确认收款应成功: " + pay);

        assertEquals(stationB, settleOf(order), "全程不得改动结算站");
        assertEquals(stationB, longOf("SELECT station_id FROM payment_record WHERE order_id=? AND status=2", order),
                "收款流水=营收凭证，记结算站 B（v47）");

        // —— 押金：归属站 A ——
        assertEquals(0, decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                        + "WHERE customer_id=? AND station_id=?", customer, stationA)
                        .compareTo(new BigDecimal("60.00")), "预收押金必须入 A 站押金账户");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_deposit_account WHERE customer_id=? AND station_id=?",
                customer, stationB), "B 站不得出现这个客户的押金账户");
        assertEquals(1, intOf("SELECT COUNT(*) FROM deposit_record WHERE related_order_id=? AND station_id=?",
                order, stationA), "押金流水记归属站 A");
        assertEquals(0, intOf("SELECT COUNT(*) FROM deposit_record WHERE related_order_id=? AND station_id=?",
                order, stationB), "押金流水不得记结算站");

        // —— 桶权益：归属站 A ——
        assertEquals(2, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND status=1", customer, stationA), "桶权益记归属站 A");
        // —— 三张资产表在 B 站都不许有行（防止"顺手统一成结算站"）——
        assertEquals(0, intOf("SELECT (SELECT COUNT(*) FROM customer_deposit_account WHERE customer_id=? AND station_id=?) "
                        + "+ (SELECT COUNT(*) FROM customer_barrel_lot WHERE customer_id=? AND station_id=?) "
                        + "+ (SELECT COUNT(*) FROM ticket_account WHERE customer_id=? AND station_id=?)",
                customer, stationB, customer, stationB, customer, stationB),
                "押金/桶权益/水票三张资产表在履约站 B 一行都不许有");
    }

    // ==================== ⑥ 待收款流水跟着结算站走（payment_record 站别） ====================

    /**
     * 流水的站别是在「发起收款」那一刻按当时的站写死的 —— 订单随后被外派时它不会自己跟着走。
     * 不搬的后果有两层：① 履约站收了钱、凭据却挂在归属站（v47 要消灭的钱货分家）；
     * ② 两个站的「待确认收款」列表各错一边（归属站列着一笔它永远收不到的钱、履约站看不到该催的单）。
     * 本用例钉住"搬"这一步，并顺手钉住"只搬 PENDING、不碰已收/已退的历史凭据"。
     */
    @Test
    @DisplayName("待收款流水跟着结算站走：外派后归履约站，归属站再也确认不了")
    void pendingCollectionFollowsSettleStation() {
        seed();
        long order = placeOrder(1, "settle-pending-1");
        assertEquals(stationA, settleOf(order), "刚下单：营收归归属站 A");

        // A 发起收款（现金 → PENDING 流水），此刻这笔待收款是 A 的
        Api created = post("/api/payments", tokenA, "{\"orderId\":" + order + ",\"paymentMethod\":2}");
        assertEquals(0, created.code(), "发起收款应成功: " + created);
        long pendingId = longOf("SELECT id FROM payment_record WHERE order_id=? AND status=1", order);
        assertEquals(stationA, longOf("SELECT station_id FROM payment_record WHERE id=?", pendingId),
                "发起收款当下：待收款流水挂归属站 A");

        // A 定向外派给 B（本用例的商品非桶装水、无押金 → 不触发风险确认闸门）
        Api dispatched = post("/api/delivery/orders/" + order + "/dispatch", tokenA,
                "{\"targetStationId\":" + stationB + "}");
        assertTrue(dispatched.isSuccess(), "定向外派应成功: " + dispatched);
        assertEquals(stationB, settleOf(order), "外派后营收归履约站 B");

        assertEquals(stationB, longOf("SELECT station_id FROM payment_record WHERE id=?", pendingId),
                "待收款流水必须跟着结算站走 —— 否则履约站收了钱、凭据却挂在归属站");
        assertFalse(pendingListContains(tokenA, pendingId), "归属站的「待确认收款」不该再列这笔钱");
        assertTrue(pendingListContains(tokenB, pendingId), "履约站的「待确认收款」必须列出来");

        // 确认收款的判权认履约站：归属站点不动这笔钱
        assertNotEquals(0, put("/api/payments/" + pendingId + "/confirm", tokenA, "{}").code(),
                "归属站不得确认已经归履约站的收款");
        assertEquals(0, put("/api/payments/" + pendingId + "/confirm", tokenB, "{}").code(),
                "履约站确认收款应成功");
        assertEquals(stationB, longOf("SELECT station_id FROM payment_record WHERE id=?", pendingId),
                "确认后凭据仍记履约站");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "订单应闭环为已付款");

        // 反向：召回时还没收的钱要跟着回归属站（已收的那笔是历史凭据，不动）
        long order2 = placeOrder(1, "settle-pending-2");
        assertEquals(0, post("/api/payments", tokenA, "{\"orderId\":" + order2 + ",\"paymentMethod\":2}").code(),
                "第二单发起收款应成功");
        long pending2 = longOf("SELECT id FROM payment_record WHERE order_id=? AND status=1", order2);
        assertTrue(post("/api/delivery/orders/transfer/" + order2 + "/outsource", tokenA,
                "{\"targetStationId\":" + stationB + "}").isSuccess(), "第二单定向外派应成功");
        assertEquals(stationB, longOf("SELECT station_id FROM payment_record WHERE id=?", pending2),
                "外派后待收款归 B");
        assertTrue(post("/api/delivery/orders/" + order2 + "/cancel-dispatch", tokenA, null).isSuccess(),
                "取消外派应成功");
        assertEquals(stationA, settleOf(order2), "召回 = 营收回归属站");
        assertEquals(stationA, longOf("SELECT station_id FROM payment_record WHERE id=?", pending2),
                "召回的待收款要跟着回归属站（钱又归 A 催收）");
        assertEquals(stationB, longOf("SELECT station_id FROM payment_record WHERE id=?", pendingId),
                "已收的那笔（PAID）是历史凭据，任何链路都不得改站");
    }

    // ==================== ⑦ 现金单：已有一条待收款时不许再插第二条活跃流水 ====================

    /**
     * `uk_payment_active_order`（生成列 {@code active_order_id} = status ∈ (1,2) 时的 order_id）
     * 保证<b>一单一条活跃流水</b>。而「先发起收款、后现场收款」是两条不同的写路径：
     * 前者插 PENDING、后者（{@code recordCashCollection}）插 PAID —— 直接插就是 1062，
     * 整个送达事务回滚，配送员点「已收款」拿到 500。
     */
    @Test
    @DisplayName("现金单先发起收款再现场收款：就地确认那一条，不插第二条活跃流水")
    void cashCollectionConfirmsExistingPendingInsteadOfInsertingSecond() {
        seed();
        long order = placeOrder(1, "settle-cash-1");
        // 客户下单后点「去支付」（现金 → PENDING 流水；本用例 seed 已给该客户开了货到付款）
        Api created = post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":2}");
        assertEquals(0, created.code(), "发起收款应成功: " + created);
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=1", order),
                "应有一条待收款流水");

        // 推到配送中，配送员送达并现场收款
        createOrderItem(order, goods, "结算饮水机", 1, "20.00", "0.00", 0);
        jdbc.update("UPDATE orders SET status = 2 WHERE id = ?", order);
        Api done = post("/api/delivery/orders/" + order + "/complete", tokenA, "{\"collected\":true}");
        assertTrue(done.isSuccess(), "现场收款完成配送应成功: " + done);

        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", order),
                "收款后必须有一条已付凭据");
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=1", order),
                "不能再留着待收款那一条 —— 否则履约站的「待确认收款」永远列着一笔已经收到的钱");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status IN (1,2)", order),
                "一单只能有一条活跃流水（uk_payment_active_order）");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "订单应闭环为已付款");
    }

    /** 该笔支付是否出现在这个站的「待确认收款」列表里。 */
    private boolean pendingListContains(String token, long paymentId) {
        Api r = get("/api/payments/pending", token);
        assertEquals(0, r.code(), "待确认收款列表应可读: " + r);
        if (r.data() == null) {
            return false;
        }
        for (com.fasterxml.jackson.databind.JsonNode n : r.data()) {
            if (n.path("id").asLong() == paymentId) {
                return true;
            }
        }
        return false;
    }
}
