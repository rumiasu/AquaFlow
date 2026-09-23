package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractScenarioTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒付结案（核销认损）：给「已送达 + 客户拒付」一条**收口出路**。
 *
 * <p><b>为什么必须有它</b>：自 2026-09-21 起「已送达(3)」不再可取消（货已交付，取消会把桶账搞乱），
 * 于是客户拒付的订单<b>没有别的出路</b> —— 钱收不回来、桶追不回来、
 * 那笔应收永远挂在站长的「待收款」台账上。本类钉住那个收口动作。</p>
 *
 * <p><b>结案做三件事</b>（缺一不可，理由见 {@code OrderBarrelExceptionServiceImpl.writeOffForRefusal}）：</p>
 * <ol>
 *   <li>核销应收：{@code payment_status} 待收款(1) → 已取消(4) → 不再计入待收款台账；</li>
 *   <li>撤销该单送出、客户尚未归还的桶权益（权益是"可退押金的桶"，而我们从未收到那笔押金）；</li>
 *   <li>把等量桶记成<b>客户欠桶</b> → 占用 = 权益(0) + over(N) = N，与实物仍然一致。</li>
 * </ol>
 *
 * <p>⚠️ 第 2、3 步必须成对：只撤权益会让"占用"凭空少掉（物理桶在账上消失），
 * 只记欠桶则客户仍能拿权益去退押金。</p>
 */
@DisplayName("拒付结案：手工发起异常单 + 核销认损（应收核销 / 撤权益 / 记欠桶）")
class CustomerRefusalWriteOffIntegrationTest extends AbstractScenarioTest {

    private static final int CASH = 2;

    private int status(long orderId) {
        return intOf("SELECT status FROM orders WHERE id=?", orderId);
    }

    private int paymentStatus(long orderId) {
        return intOf("SELECT payment_status FROM orders WHERE id=?", orderId);
    }

    private int rightsQty(World w) {
        return intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND status=1", w.customerId(), w.stationId());
    }

    private int overQty(World w) {
        return intOf("SELECT IFNULL(SUM(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=?", w.customerId(), w.stationId());
    }

    /** 走真实链路造一张「已送达未收款」的现金单（此时权益已建、押金为 0）。 */
    private long deliveredUnpaidCashOrder(World w, String key, int qty) {
        assertEquals(0, placeOrder(w, key, CASH, qty).code(), "下单应成功");
        long order = orderIdOf(key);
        assertEquals(0, acceptOrder(w, order).code(), "接单应成功");
        assertEquals(0, completeDelivery(w, order, qty, 0, false, null).code(), "送达（不收款）应成功");
        assertEquals(3, status(order), "应停在 已送达(3)");
        return order;
    }

    /** 站长手工发起一条异常单，返回异常单 id。 */
    private long createException(World w, long orderId, String category) {
        Api res = post("/api/manager/exceptions?orderId=" + orderId, w.managerToken(),
                "{\"category\":\"" + category + "\",\"staffNote\":\"客户拒付\"}");
        assertEquals(0, res.code(), "手工发起异常单应成功: " + res);
        return longOf("SELECT id FROM order_barrel_exception WHERE order_id=? ORDER BY id DESC LIMIT 1", orderId);
    }

    /**
     * 异常类别必须走**白名单**（2026-09-23 修）。
     *
     * <p>这个入口的 {@code category} 来自请求体，原来直接落库 —— 能写进任意字符串。
     * 2026-09-22 的业务实测就真的写进过一个不在集合里的值（{@code REFUSAL}）：
     * 之后所有 {@code switch} 都认不出它，界面显示英文代号、统计漏掉它，
     * 而**没有任何一处报错**。判据同 AGENTS §6「请求体的枚举入参必须白名单校验」。</p>
     */
    @Test
    @DisplayName("异常类别白名单：不认识的类别必须当场拒，且不落库（不能静默洗成「其他」）")
    void categoryMustBeWhitelisted() {
        World w = openStation("拒付站D");
        long order = deliveredUnpaidCashOrder(w, "wo-4", 1);

        Api bad = post("/api/manager/exceptions?orderId=" + order, w.managerToken(),
                "{\"category\":\"REFUSAL\"}");
        assertTrue(!bad.isSuccess(), "不在白名单里的类别必须被拒，实际=" + bad);
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id=?", order),
                "被拒之后不得留下异常单（更不能静默改成「其他」）");

        // 合法类别照常能用 —— 证明上一条不是把整条链路堵死了
        long exId = createException(w, order, "CUSTOMER_REFUSE");
        assertTrue(exId > 0, "合法类别应照常建单");
        assertEquals("CUSTOMER_REFUSE",
                jdbc.queryForObject("SELECT category FROM order_barrel_exception WHERE id=?", String.class, exId),
                "落库的是白名单里的代号本身");
        Api detail = get("/api/manager/exceptions/" + exId, w.managerToken());
        assertEquals("客户拒收", detail.data().path("categoryText").asText(),
                "中文文案由后端从 constant/ExceptionCategory 下发，前端不自带映射表");
    }

    @Test
    @DisplayName("拒付结案三件事都做到：应收核销出账、权益撤销、等量记成客户欠桶（占用不变）")
    void writeOffDoesAllThreeThings() {
        World w = openStation("拒付站A");
        long order = deliveredUnpaidCashOrder(w, "wo-1", 1);

        // 结案前：权益 1 个桶（可退 30 元）、押金 0、订单是 待收款、那笔钱在站长台账里
        assertEquals(1, rightsQty(w), "送达后客户应有 1 个桶权益");
        assertEquals(0, overQty(w), "此时不该有欠桶");
        assertEquals(1, paymentStatus(order), "现金单送达未收款 = 待收款(1)");
        BigDecimal outstandingBefore = decimalOf(
                "SELECT IFNULL(SUM(total_amount),0) FROM orders WHERE station_id=? AND payment_status=1 AND status<>5",
                w.stationId());
        assertTrue(outstandingBefore.signum() > 0, "结案前这笔应收应挂在台账上，实际=" + outstandingBefore);
        BigDecimal orderAmount = decimalOf("SELECT total_amount FROM orders WHERE id=?", order);

        // ① 手工发起异常单（这条链路此前**没有入口**：异常单只能由"少收空桶"自动生成，
        //    而拒付场景桶可能一个不少地还回来了，差异为 0 → 根本不会触发）
        long exId = createException(w, order, "CUSTOMER_REFUSE");
        assertTrue(exId > 0, "异常单应落库");

        // ② 核销认损
        assertEquals(0, post("/api/manager/exceptions/" + exId + "/write-off", w.managerToken(),
                "{\"managerNote\":\"客户收了水拒不付款，站长认损\"}").code(), "拒付结案应成功");

        // 第 1 件：应收核销 —— 支付状态进终态，且不再计入待收款
        assertEquals(4, paymentStatus(order), "应收应被核销（payment_status = 已取消 4）");
        assertEquals(3, status(order), "⚠️ 订单状态**不动**：货已交付，仍是 已送达(3)");
        BigDecimal outstandingAfter = decimalOf(
                "SELECT IFNULL(SUM(total_amount),0) FROM orders WHERE station_id=? AND payment_status=1 AND status<>5",
                w.stationId());
        assertEquals(0, outstandingAfter.compareTo(outstandingBefore.subtract(orderAmount)),
                "那笔应收必须从待收款台账里出账，实际=" + outstandingAfter);

        // 第 2 / 3 件：撤权益 + 记欠桶，且**占用不变**（物理桶数没有凭空消失）
        assertEquals(0, rightsQty(w), "权益必须被撤销（否则客户还能拿它退押金）");
        assertEquals(1, overQty(w), "等量的桶必须记成客户欠桶");
        assertBarrelLedgerClosed(w, "拒付结案之后");
        assertInventoryConserved(w, "拒付结案之后");

        // 留痕：异常单结案 + 订单流水写清是谁在什么时候做的
        assertEquals("EXECUTED", jdbc.queryForObject(
                "SELECT status FROM order_barrel_exception WHERE id=?", String.class, exId));
        String note = jdbc.queryForObject("SELECT special_note FROM orders WHERE id=?", String.class, order);
        assertTrue(note != null && note.contains("[拒付核销]"),
                "订单必须留痕（否则账上少了一笔应收却查不出原因），实际=" + note);

        // 对账仍要平：权益 0、押金 0 → 不再有"穿底"
        assertReconcileBalanced("拒付结案之后");
    }

    @Test
    @DisplayName("结案是终态：重复点核销被拒，且钱与桶账都不再被改动")
    void writeOffIsTerminal() {
        World w = openStation("拒付站B");
        long order = deliveredUnpaidCashOrder(w, "wo-2", 1);
        long exId = createException(w, order, "CUSTOMER_REFUSE");

        assertEquals(0, post("/api/manager/exceptions/" + exId + "/write-off", w.managerToken(), "{}").code(),
                "首次结案应成功");
        int rightsAfterFirst = rightsQty(w);
        int overAfterFirst = overQty(w);

        Api again = post("/api/manager/exceptions/" + exId + "/write-off", w.managerToken(), "{}");
        assertNotEquals(0, again.code(), "已结案的异常不得重复核销，实际=" + again);
        assertEquals(rightsAfterFirst, rightsQty(w), "重复点击不得再撤一次权益");
        assertEquals(overAfterFirst, overQty(w), "重复点击不得再记一次欠桶");
        assertEquals(4, paymentStatus(order), "支付状态应保持已核销");
    }

    @Test
    @DisplayName("越权：他站站长既不能给本站订单建异常单，也不能核销本站的异常单")
    void otherStationCannotCreateOrWriteOff() {
        World a = openStation("拒付站C");
        World b = openStation("拒付站D");
        long order = deliveredUnpaidCashOrder(a, "wo-3", 1);

        // 用 B 站站长的令牌，拿 A 站订单的 id 建异常单 → 拒
        Api create = post("/api/manager/exceptions?orderId=" + order, b.managerToken(),
                "{\"category\":\"CUSTOMER_REFUSE\"}");
        assertNotEquals(0, create.code(), "他站不得给本站订单建异常单，实际=" + create);
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id=?", order),
                "被拒后不得留下任何异常单");

        // 正主建一张，再让他站站长核销 → 拒
        long exId = createException(a, order, "CUSTOMER_REFUSE");
        Api writeOff = post("/api/manager/exceptions/" + exId + "/write-off", b.managerToken(), "{}");
        assertNotEquals(0, writeOff.code(), "他站不得核销本站异常单，实际=" + writeOff);
        assertEquals(1, paymentStatus(order), "被拒后那笔应收必须原样挂着（更不许被核销掉）");
        assertEquals(1, rightsQty(a), "被拒后权益也不得被动过");
    }
}
