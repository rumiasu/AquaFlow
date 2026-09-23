package com.example.aquaflow.integration.scenario;

import com.example.aquaflow.support.AbstractScenarioTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 S3：<b>现金（货到付款）单的全生命周期</b> —— 第一条「跑业务流程而不是跑接口」的用例。
 *
 * <p><b>它和 {@code DeliveryCompleteIntegrationTest} / {@code PaymentFlowIntegrationTest} 的区别</b>：
 * 那两个类各自盯住一个环节的契约（{@code collected} 字段会不会被静默丢弃、支付状态会不会被倒滚），
 * <b>造数直接 INSERT 一张"配送中"的订单</b>就开跑。本类从<b>客户下单那一刻</b>起走完整条链 ——
 * 下单 → 站长/配送员视野 → 接单 → 送达 → 收款 —— 每一步都走 HTTP 正门，
 * 并在链路走完后断言<b>跨模块的全局不变量</b>（对账 V1/V2 全平、站长端对账零差异、
 * 库存守恒、桶账闭环、结算站落位、账证一致）。</p>
 *
 * <p><b>为什么这层不能让环节用例替代</b>：同一份口径在 A 处写、在 B 处读，两边各自"正确"时
 * 环节用例全绿，而链路是自相矛盾的。这类缺陷在本仓是惯犯：
 * AGENTS.md §8.15（DTO 收敛时漏字段）、§8.16（只遍历 {@code customer_barrel_asset}，第 4 次）、
 * §1「归属 = 绑定 ∪ 本站订单」口径（第 5 次）。它们共同的形状是
 * <b>"界面/一端说做了，另一端没做"</b> —— 只有把链走完才看得见。</p>
 *
 * <p>场景编号对应 {@code docs/audit/2026-09-16-场景测试矩阵.md} 的 S2 / S3.1 / S3.7 / S4.1 / S4.3 / S4.6。</p>
 */
@DisplayName("场景 S3 · 现金单全生命周期：下单→接单→送达→收款→对账全平")
class CashOrderLifecycleScenarioTest extends AbstractScenarioTest {

    private static final int CASH = 2;

    private int status(long orderId) {
        return intOf("SELECT status FROM orders WHERE id=?", orderId);
    }

    private int paymentStatus(long orderId) {
        return intOf("SELECT payment_status FROM orders WHERE id=?", orderId);
    }

    private int paidRecords(long orderId) {
        return intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", orderId);
    }

    /**
     * 主链：客户下单（现金 2 桶）→ 两张待办列表可见 → 配送员接单 → 送达时<b>现场收款</b> → 闭环。
     *
     * <p>金额口径：20 元/桶 × 2 = 水费 40，押金 30 元/桶 × 2（首单，权益为 0）= 60，应收合计 100.00。
     * 押金<b>不在下单时入账</b>，只在真收到钱那一步入账（否则客户一分未付却已"交过押金"）。</p>
     */
    @Test
    @DisplayName("现金单现场收款：4 已完成 + 2 已付 + PAID 流水 + 押金入账，且对账全平")
    void cashCollectedOnDelivery_closesLoopAndStaysBalanced() {
        World w = openStation("链路站A");
        int stockBefore = w.initialStock();

        // ---- 1) 客户下单（现金是「先收到钱才派单」的例外：下单即进站长/配送员视野）----
        assertEquals(0, placeOrder(w, "sc-cash-1", CASH, 2).code(), "已开通货到付款的客户应能下现金单");
        long order = orderIdOf("sc-cash-1");
        assertTrue(order > 0, "下单后应能按幂等键查到订单");

        assertEquals(1, status(order), "下单后应为 待配送(1)");
        assertEquals(1, paymentStatus(order), "现金单下单即 待收款(1)：钱要当面收");
        assertEquals(CASH, intOf("SELECT payment_method FROM orders WHERE id=?", order), "支付方式应为现金");
        assertEquals(1, intOf("SELECT first_barrel_order FROM orders WHERE id=?", order),
                "本站第一笔买桶单，应标记首单（首单整段跳过回桶核对）");
        assertSettleStationBooked(order, w.stationId(), "下单那一刻");

        // 下单就写「配送中(PENDING)」并在途锁定 —— 真实下单路径一定会写这条，
        // 不写的话送达时会把全额算成 over，占用 = 权益 + over 会多算一倍。
        assertEquals(2, intOf("SELECT IFNULL(SUM(qty),0) FROM customer_barrel_in_transit "
                + "WHERE customer_id=? AND station_id=? AND status='PENDING'", w.customerId(), w.stationId()),
                "下单应记 2 桶「配送中」");
        assertEquals(stockBefore - 2,
                intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                        w.stationId(), w.productId()),
                "下单应扣 2 件库存");
        assertEquals(0, depositBalance(w).compareTo(BigDecimal.ZERO), "下单时客户一分未付，押金不得入账");

        assertTrue(inList("/api/delivery/orders/station-pending", w.managerToken(), order),
                "货到付款是例外：钱还没到也要推进站长视野，否则没人去送、也就收不到钱");
        assertTrue(inList("/api/delivery/orders/pending", w.driverToken(), order), "配送员同样应看得到");

        // ---- 2) 配送员接单（1 待配送 → 2 配送中）----
        assertEquals(0, acceptOrder(w, order).code(), "已支付的现金单应可接单");
        assertEquals(2, status(order), "接单后应为 配送中(2)");

        // ---- 3) 送达 + 现场收款（2 配送中 → 4 已完成，一步闭环）----
        assertEquals(0, completeDelivery(w, order, 2, 0, true, "链路用例：客户当场付清").code(),
                "现场收款后完成配送应成功");
        assertEquals(4, status(order), "现场收款应直接闭环为 已完成(4)，不停留在已送达");
        assertEquals(2, paymentStatus(order), "收到钱才写 已付款(2)");
        assertPaymentBookedOnce(order, "100.00", "现场收款");
        assertEquals(0, depositBalance(w).compareTo(new BigDecimal("60.00")),
                "已付款后应把预收桶押金 60.00 入账（applyDepositOnPaid）");

        // ---- 4) 链路走完之后的全局不变量 ----
        assertSettleStationBooked(order, w.stationId(), "收款之后");
        assertBarrelLedgerClosed(w, "现金单闭环后");
        assertInventoryConserved(w, "现金单闭环后");
        assertReconcileBalanced("现金单闭环后");
        assertStationReconcileClean(w, "现金单闭环后");
    }

    /**
     * 分支链：<b>送达时没收钱，回头再收</b>。
     *
     * <p>这是现金单最常见的真实形态（客户不在家、下次送水一起结），也是「支付状态只前进、不倒滚」
     * 这条不变式最容易破的地方：送达未收款时若把 {@code payment_status} 写成 未付(0)，
     * 这笔应收就会从站长「待收款」合计（口径 = {@code payment_status=1 且未取消}）里<b>凭空消失</b>，
     * 站长再也看不到该催谁（§8 缺陷 D）。</p>
     */
    @Test
    @DisplayName("送达未收款 → 应收仍在站长视野 → 后补收款闭环，闭环后对账全平")
    void cashDeliveredUnpaid_thenCollectedLater_staysBalancedThroughout() {
        World w = openStation("链路站B");

        assertEquals(0, placeOrder(w, "sc-cash-2", CASH, 1).code(), "下单应成功");
        long order = orderIdOf("sc-cash-2");
        assertEquals(0, acceptOrder(w, order).code(), "接单应成功");

        // ---- 送达但不收款：停在 已送达(3) + 待收款(1) ----
        assertEquals(0, completeDelivery(w, order, 1, 0, false, null).code(),
                "未收款也应允许完成配送（只是不能标已付）");
        assertEquals(3, status(order), "未收款应停在 已送达(3)");
        assertEquals(1, paymentStatus(order), "送达未收款仍须保持 待收款(1)，不得倒回 未付(0)");
        assertEquals(0, paidRecords(order), "未收款绝不能有 PAID 流水");
        assertEquals(0, depositBalance(w).compareTo(BigDecimal.ZERO), "未付款不得入账押金");

        // 这笔应收必须留在站长看得见的列表里
        assertTrue(inList("/api/delivery/orders/delivered-unpaid", w.managerToken(), order),
                "送达未收款必须出现在站长「已送达未收款」列表里（否则没人去催）");

        // 此时链路尚未收钱，系统仍须自洽
        assertBarrelLedgerClosed(w, "送达未收款时");
        assertInventoryConserved(w, "送达未收款时");
        // [2026-09-21 已修] 这里原本必须放行一条差异：对账的「押金穿底」把"送达未收款的时间差"
        // 算成了差异（权益在送达那刻建、押金要收到钱才入账），于是每天 03:00 报 SYSTEM 告警。
        // 现在判据多了"该客户在本站有**已过应付日期**的未结账单"这个前提 ——
        // 本用例是散户（没有账期、due_date 为空）且订单未逾期，所以**必须全平**。
        // 判据若被改坏，这里会立刻变红。
        assertReconcileBalanced("送达未收款时");

        // ---- 站长后补收款（正门：confirm-offline-pay，已在 已送达 时顺带闭环）----
        assertEquals(0, confirmOfflinePay(w, order).code(), "确认线下收款应成功");
        assertEquals(2, paymentStatus(order), "收款后应置 已付款(2)");
        assertEquals(4, status(order), "已送达时确认收款应顺带闭环为 已完成(4)");
        assertPaymentBookedOnce(order, "50.00", "后补收款");
        assertEquals(0, depositBalance(w).compareTo(new BigDecimal("30.00")), "收款后应入账押金 30.00");

        // 闭环后应收列表里不该再有它（否则站长会一直看到一笔已经收到的钱）
        assertTrue(!inList("/api/delivery/orders/delivered-unpaid", w.managerToken(), order),
                "已闭环的订单不该继续留在「已送达未收款」列表里");

        assertSettleStationBooked(order, w.stationId(), "后补收款之后");
        assertBarrelLedgerClosed(w, "后补收款之后");
        assertInventoryConserved(w, "后补收款之后");
        assertReconcileBalanced("后补收款之后");
        assertStationReconcileClean(w, "后补收款之后");
    }

    /**
     * 对照链：<b>整段不下发 {@code collected} 字段</b>时，业务上必须等价于「未收款」——
     * 钱不进账，但那笔应收仍在站长视野里。
     *
     * <p>与上一个用例组成一对：如果哪天 DTO 又把 {@code collected} 丢了
     * （f3e702f 那次，见 §8.15），"传了 collected=true"的用例会红、本用例保持绿，
     * 两条一起才说明"传了确实有用"。</p>
     */
    @Test
    @DisplayName("不传 collected 字段：等价于未收款，钱不入账但应收仍可见（含 1 条已知对账误报）")
    void omittedCollectedField_keepsReceivableVisibleAndBooksNothing() {
        World w = openStation("链路站C");

        assertEquals(0, placeOrder(w, "sc-cash-3", CASH, 1).code(), "下单应成功");
        long order = orderIdOf("sc-cash-3");
        assertEquals(0, acceptOrder(w, order).code(), "接单应成功");

        assertEquals(0, completeDeliveryNoCollectedField(w, order, 1, 0).code(),
                "缺省 collected 应仍能完成配送");
        assertEquals(3, status(order), "不传 collected 必须按『未收款』处理，不能默认已收");
        assertEquals(1, paymentStatus(order), "未收款不得改动支付状态");
        assertEquals(0, paidRecords(order), "不传 collected 不得写 PAID 流水");
        assertEquals(0, depositBalance(w).compareTo(BigDecimal.ZERO), "未收款不得入账押金");
        assertTrue(inList("/api/delivery/orders/delivered-unpaid", w.managerToken(), order),
                "这笔钱没收，但它必须仍然看得见");

        assertBarrelLedgerClosed(w, "缺省 collected 送达后");
        assertInventoryConserved(w, "缺省 collected 送达后");
        // 同上一个用例：第②步之后，"送达未收款的时间差"不再进差异计数 → 必须全平
        assertReconcileBalanced("缺省 collected 送达后");
    }

    /**
     * [2026-09-21 裁定改写] <b>已送达的现金单不能再取消</b> —— 这曾经是个真敞口，现在是"走不通"。
     *
     * <p><b>改之前是什么样</b>（留档，别照旧版做）：这条用例原来断言"已送达未收款单被客户申请取消、
     * 站长同意 → 订单变已取消(5)"，并同时钉住一个**缺陷**：送达那刻建立的桶权益**没有被撤销**
     * —— 客户手上留着可退押金的权益，而我们从未收到过那笔押金，订单却已取消、这笔钱再也不会收
     * （对账的「押金穿底」会在那时永久报 1 条，而且**报得对**）。</p>
     *
     * <p><b>现在的规则</b>：已送达(3) 被排除出可取消状态（见 {@code OrderStatus.isCancellable}），
     * 于是整条链**不可达** —— 取消链里那段"撤销权益"的代码因此不需要补（补了也是死代码），
     * 只在 {@code PaymentServiceImpl.refundOrder} 留了一行护栏注释，写明**放松门槛时必须同时补什么**。</p>
     *
     * <p><b>本用例的新价值</b>：用真实链路证明"已送达单确实取消不了"，并证明**拒绝之后账上一动不动**
     * —— 权益还在、押金还是 0、库存没回补、订单没变。它同时是那条护栏注释的执行者：
     * 谁放松了门槛，这条立刻变红。</p>
     */
    @Test
    @DisplayName("取消链：已送达的现金单取消不了（规则变更后的护栏），且拒绝后账上一动不动")
    void deliveredUnpaidOrderCannotBeCancelled_systemStaysUntouched() {
        World w = openStation("链路站D");

        assertEquals(0, placeOrder(w, "sc-cash-4", CASH, 1).code(), "下单应成功");
        long order = orderIdOf("sc-cash-4");
        assertEquals(0, acceptOrder(w, order).code(), "接单应成功");
        assertEquals(0, completeDelivery(w, order, 1, 0, false, null).code(), "送达（不收款）应成功");
        assertEquals(3, status(order), "应停在 已送达(3)");

        assertEquals(1, rightsQty(w), "送达后客户应拿到 1 桶权益（此时押金还没收）");
        assertEquals(0, depositBalance(w).compareTo(BigDecimal.ZERO), "此时押金余额仍是 0");

        // ① 客户发起取消 → 拒，而且**连取消申请都不该落库**
        Api byCustomer = put("/api/orders/" + order + "/customer-cancel", w.customerToken(), null);
        assertTrue(!byCustomer.isSuccess(), "已送达单客户不得取消，实际=" + byCustomer);
        assertTrue(byCustomer.message() != null && byCustomer.message().contains("配送异常"),
                "拒绝文案要指向异常流程，实际=" + byCustomer.message());

        // ② 站长直接"拒单"→ 拒
        Api byReject = post("/api/delivery/orders/reject/" + order, w.managerToken(), "{}");
        assertTrue(!byReject.isSuccess(), "已送达单不得拒单，实际=" + byReject);

        // ③ 站长"解决/拒单"（会触发退款链的那条）→ 拒
        Api byResolve = post("/api/delivery/orders/" + order + "/resolve", w.managerToken(),
                "{\"reason\":\"客户拒付\"}");
        assertTrue(!byResolve.isSuccess(), "已送达单不得走解决/拒单，实际=" + byResolve);

        // ④ 配送员/站长「申请取消」→ 拒。
        //    ⚠️ 这是原实现**漏掉的一条路**：`requestCancelByStaff` 当时写的是
        //    `cur != DELIVERING && cur != DELIVERED`（放行已送达），而下游审批用的是 `isCancellable`（拒绝已送达）
        //    —— 两个入口口径不一致，于是配送员能给一张已送达的单提交申请，它进了站长的 P0 审批列表，
        //    站长点「同意」才被拒。**站长唯一的出路是点「拒绝」，等于凭空多一件只能驳回的活。**
        Api byStaffRequest = post("/api/delivery/orders/" + order + "/cancel-request",
                w.driverToken(), "{}");
        assertTrue(!byStaffRequest.isSuccess(), "已送达单配送员也不得提交取消申请，实际=" + byStaffRequest);
        assertTrue(byStaffRequest.message() != null && byStaffRequest.message().contains("配送异常"),
                "拒绝文案要指向异常流程，实际=" + byStaffRequest.message());

        // ⑤ 手工造一条待审批的取消申请（模拟"有人绕过入口直接写库"），站长也同意不了 ——
        //    这才是 `approveCancelRequest` 里第二道闸门（isCancellable）的**真正**验证点：
        //    不造这条的话，上一步只验到"该订单没有待审批的取消申请"，根本走不到状态门槛。
        insertPendingCancelRequest(order, w.driverId());
        Api byApprove = post("/api/delivery/orders/cancel-request/" + order + "/approve",
                w.managerToken(), "{}");
        assertTrue(!byApprove.isSuccess(), "已送达单的取消申请不应能审批通过，实际=" + byApprove);
        assertTrue(byApprove.message() != null && byApprove.message().contains("配送异常"),
                "拒绝文案要指向异常流程，实际=" + byApprove.message());
        // 清掉手工造的这条：下面"不得留下待审批的申请"要断言的是**真实入口**有没有留下东西
        jdbc.update("DELETE FROM order_transfer WHERE order_id=? AND sub_kind='CANCEL_REQUEST'", order);

        // 拒绝之后账上必须一动不动
        assertEquals(3, status(order), "四条路都不许改订单状态");
        assertEquals(1, paymentStatus(order), "支付状态仍是 待收款(1)");
        assertEquals(1, rightsQty(w), "权益不得被动过");
        assertEquals(0, overQty(w), "也不该凭空出现欠桶");
        assertEquals(0, depositBalance(w).compareTo(BigDecimal.ZERO), "押金仍为 0");
        assertEquals(w.initialStock() - 1,
                intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                        w.stationId(), w.productId()),
                "库存不得回补（货已经送出去了）");
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? "
                        + "AND status='PENDING' AND sub_kind='CANCEL_REQUEST'", order),
                "不得留下待审批的取消申请");

        // 账仍要自洽。此时对账**全平**（第②步之后）：
        // 客户手上的桶权益 > 押金余额这件事仍然存在，但它是**收款前的时间差**，不是穿底 ——
        // 判据要求"该客户在本站有已过应付日期的未结账单"才算差异，而本单没有账期、也没逾期。
        // 一旦收款（上个用例），押金入账、时间差自然消失。
        assertBarrelLedgerClosed(w, "已送达未收款时");
        assertInventoryConserved(w, "已送达未收款时");
        assertReconcileBalanced("已送达未收款时");
    }

    /**
     * 对账判据的**另一半**：账期一过，同一条差异就必须报出来。
     *
     * <p><b>为什么必须有这条</b>：上一个用例证明"没逾期时不算差异"，但光有它是不够的 ——
     * 把判据直接写死成"永远返回 0"也能让它通过。本用例把时间往后推：
     * <b>同一张单、同一批数据</b>，只把应付日期挪到昨天，对账就必须报出「押金穿底」。</p>
     *
     * <p>它同时钉住了 2026-09-21 那套口径的完整语义：
     * 权益 &gt; 押金余额这件事<b>一直存在</b>，区别只在"这笔钱过没过应付日期"
     * —— 没过是时间差（不报），过了才是真穿底（报）。</p>
     */
    @Test
    @DisplayName("逾期之后必须报：同一张单，账期一过，押金穿底就进差异计数（防判据被写死成 0）")
    void overdueUnsettledOrderIsReportedAsShortfall() {
        World w = openStation("链路站E");

        // 先给客户设 30 天账期，再下现金单 —— 应付日期会在下单那一刻按它快照
        // 路径正本：ManagerReceivableController 类级 @RequestMapping("/api/manager") + 方法级 /customers/{id}/credit-terms
        assertEquals(0, put("/api/manager/customers/" + w.customerId() + "/credit-terms",
                w.managerToken(), "{\"dueDays\":30}").code(), "给客户设账期应成功");

        assertEquals(0, placeOrder(w, "sc-cash-5", CASH, 1).code(), "下单应成功");
        long order = orderIdOf("sc-cash-5");
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE id=? AND due_date IS NOT NULL", order),
                "挂了账期的单，下单时必须快照出应付日期");
        assertEquals(0, acceptOrder(w, order).code(), "接单应成功");
        assertEquals(0, completeDelivery(w, order, 1, 0, false, null).code(), "送达（不收款）应成功");
        assertEquals(3, status(order), "应停在 已送达(3)");

        // ① 账期内：权益 > 押金 这件事是事实，但**不进差异**（时间差）
        assertEquals(1, rightsQty(w), "客户已拿到 1 桶权益");
        assertEquals(0, depositBalance(w).compareTo(BigDecimal.ZERO), "押金一分没收到");
        assertReconcileBalanced("账期内（不得把时间差算成穿底）");

        // ② 造数：把应付日期推到昨天 = 这笔钱已经逾期
        assertEquals(1, jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY) "
                + "WHERE id=?", order), "造数：应付日期推到昨天");

        // ③ 逾期未结 + 权益 > 押金 ⇒ **必须报**
        Map<String, Integer> v1 = reconciliationService.runReconcile();
        assertEquals(1, v1.get("barrelState"), "逾期后押金穿底必须进 V1 差异计数，实际=" + v1);
        Map<String, Integer> v2 = reconciliationService.runReconcileV2();
        assertEquals(1, v2.get("E6_depositShortfall"), "V2 必须同样报（两处共用同一段 SQL），实际=" + v2);
        Api stationCheck = get("/api/manager/reconciliation", w.managerToken());
        assertEquals(0, stationCheck.code(), "站长端对账应可读: " + stationCheck);
        assertEquals(1, stationCheck.data().path("totalDiff").asInt(),
                "站长端也必须看得到这 1 条（三处判据同源），实际=" + stationCheck.data());

        // ④ 收到钱 → 押金入账、账单不再是"未结" → 归零
        assertEquals(0, confirmOfflinePay(w, order).code(), "确认线下收款应成功");
        assertEquals(2, paymentStatus(order), "收款后应置 已付款(2)");
        assertEquals(0, depositBalance(w).compareTo(new BigDecimal("30.00")), "押金应入账 30.00");
        assertReconcileBalanced("收款之后");
    }

    /** 该客户在本站的桶权益（真相源是批次 remain_qty，不是派生的 asset）。 */
    private int rightsQty(World w) {
        return intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND status=1", w.customerId(), w.stationId());
    }
    /** 该客户在本站的 over（>0 欠桶 / <0 水站暂存）。 */
    private int overQty(World w) {
        return intOf("SELECT IFNULL(SUM(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=?", w.customerId(), w.stationId());
    }

    /**
     * 直接在 {@code order_transfer} 里插一条**待审批的取消申请** —— 模拟"有人绕过入口直接写库"。
     *
     * <p>为什么要能造这种"不该存在"的状态：审批端点的状态门槛只有在**确实有申请**时才会被执行到，
     * 否则会先撞上"该订单没有待审批的取消申请"那一句，用例看似通过、门槛其实一行没跑
     * （本仓"用例绿了但没验到东西"的形状）。造完记得清掉，别污染后面"不得留下待审批申请"的断言。</p>
     */
    private void insertPendingCancelRequest(long orderId, long staffId) {
        jdbc.update("INSERT INTO order_transfer (order_id, kind, sub_kind, from_staff_id, status, reason, create_time) "
                + "VALUES (?, 'STAFF', 'CANCEL_REQUEST', ?, 'PENDING', '用例手工造数', NOW())", orderId, staffId);
    }
}
