package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [WP1 · F-00] 已退款(3) 的订单<b>不许</b>被「确认线下收款」复活成已付款(2)。
 *
 * <p><b>原来错在哪</b>：{@code OrderWorkflowServiceImpl.confirmOfflinePay} 把<b>刚读到的</b>
 * {@code payment_status} 当作 CAS 的 expected 传下去（{@code updatePaymentStatusIf(id, payCur, PAID)}）——
 * expected 恒等于现值，这个 CAS 对"从哪个状态迁入"没有任何限制，等于没有守卫。</p>
 *
 * <p><b>后果链（本用例走过的就是它）</b>：站长手工退款只把 {@code payment_status} 置 3、
 * <b>不动</b> {@code orders.status} ⇒ 该单仍停在 已送达(3)，而「已送达未收款」列表按 status=3 取数，
 * 界面上就有「确认收款」按钮；点下去，旧实现会把 3 改回 2，并且
 * {@code recordCashCollection} 的幂等判据（{@code countByOrderIdAndStatus(orderId, PAID) > 0}）
 * 已被这次改写清零，于是它<b>再插一条全额 PAID 流水</b> —— 客户拿到了退款，报表上却显示
 * 这笔钱又被收了一次，且对账等式2 不会报（"已付款"与"有 PAID 凭证"同时成立）。</p>
 *
 * <p>这三条用例一起锁住修复的两半：① 终态被明确拒绝；② 原有的「已付即跳过」短路没有被
 * 顺手改坏（那是重复点确认不报错的前提），以及正常前进路径 1 → 2 仍然可用。</p>
 *
 * <p>走的是真实 HTTP（{@code AbstractIntegrationTest} 起完整容器 + 真 JWT），不是直接调 service：
 * 判权（履约站 / 结算站）、拦截器与事务边界都是这条链的一部分。</p>
 */
@DisplayName("F-00 · 已退款订单不得被复活为已付款")
class PaymentRefundResurrectionIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("已退款订单确认收款必须被拒，且不得补出第二条全额 PAID 流水")
    void refundedOrderCannotBeCollectedAgain() {
        long station = createStation("退款复活站");
        long manager = createStaff("退款复活站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("退款复活客户", "refund-resurrection-openid");
        long product = createProduct("退款复活水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "退款复活地址");

        // 现场形态：现金(2) 货到付款单 —— 已送达(3) 且已付款(2)，账上有一条已付款流水。
        long orderId = createOrderFull(customer, address, station, product, 3, 2, 2,
                "10.00", "30.00", "40.00", false, 0);
        long paymentId = createPaymentRecord(orderId, customer, station, "40.00", 2, 2);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 站长手工退款：只把 payment_record 与 orders.payment_status 置 3，**不动 orders.status**
        Api refunded = put("/api/payments/" + paymentId + "/refund", mgr, "{\"note\":\"客户投诉多收\"}");
        assertEquals(0, refunded.code(), "站长手工退款应成功: " + refunded);
        assertEquals(3, intOf("SELECT payment_status FROM orders WHERE id=?", orderId),
                "退款后订单支付状态必须是已退款(3)");
        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", orderId),
                "手工退款不取消订单：仍停在已送达(3) —— 这正是旧实现能把它复活成已付款的入口");
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", orderId),
                "退款后不该再有已付款流水（原流水与负金额冲正流水都是已退款）");

        // 界面上的「确认收款」按钮此时仍然在（delivered-unpaid 列表按 status=3 取数），点下去必须被拒
        Api collect = post("/api/delivery/orders/" + orderId + "/confirm-offline-pay", mgr, null);
        assertNotEquals(0, collect.code(), "已退款订单确认收款必须被拒: " + collect);
        assertTrue(collect.message().contains("已退款"),
                "拒绝文案要指明已退款，不能笼统报「状态已变更」: " + collect.message());
        assertEquals(3, intOf("SELECT payment_status FROM orders WHERE id=?", orderId),
                "被拒后支付状态不得被复活为已付款(2)");
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", orderId),
                "被拒后不得补出第二条全额 PAID 流水 —— 那会让报表上的退款被静默抵消");
        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", orderId),
                "被拒后订单状态也不得前进（整笔回滚）");
    }

    @Test
    @DisplayName("已送达(3)+待收款(1) 正常收款：支付状态前进到 2、状态闭环为已完成(4) 并补出 PAID 流水")
    void pendingDeliveredOrderIsCollectedAndCompleted() {
        long station = createStation("正常收款站");
        long manager = createStaff("正常收款站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("正常收款客户", "refund-resurrection-normal-openid");
        long product = createProduct("正常收款水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "正常收款地址");

        // 现金单送到门口、钱还没收：已送达(3) + 待收款(1)，押金 0 以免牵进押金账户夹具
        long orderId = createOrderFull(customer, address, station, product, 3, 1, 2,
                "10.00", "0.00", "10.00", false, 0);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api collect = post("/api/delivery/orders/" + orderId + "/confirm-offline-pay", mgr, null);
        assertEquals(0, collect.code(), "待收款单确认收款必须成功（别为了堵 3/4 把正常路径一起收紧）: " + collect);
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", orderId),
                "支付状态应前进到已付款(2)");
        assertEquals(4, intOf("SELECT status FROM orders WHERE id=?", orderId),
                "已送达顺带闭环为已完成(4)");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", orderId),
                "确认收款必须补出恰好一条 PAID 流水（订单已付却无凭证则日结对不上）");
    }

    @Test
    @DisplayName("已付款(2) 的单重复确认收款仍幂等成功（「已付即跳过」短路不能丢）")
    void alreadyPaidOrderConfirmStaysIdempotent() {
        long station = createStation("幂等收款站");
        long manager = createStaff("幂等收款站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("幂等收款客户", "refund-resurrection-idem-openid");
        long product = createProduct("幂等收款水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "幂等收款地址");

        // 配送中(2) 且已付款(2)：现场再点一次「确认收款」是正常的重复操作，不该报错
        long orderId = createOrderFull(customer, address, station, product, 2, 2, 2,
                "10.00", "0.00", "10.00", false, 0);
        createPaymentRecord(orderId, customer, station, "10.00", 2, 2);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api first = post("/api/delivery/orders/" + orderId + "/confirm-offline-pay", mgr, null);
        assertEquals(0, first.code(), "已付款单重复确认应幂等成功，而不是报「支付状态已变更」: " + first);
        Api second = post("/api/delivery/orders/" + orderId + "/confirm-offline-pay", mgr, null);
        assertEquals(0, second.code(), "第二次重复确认仍应幂等成功: " + second);
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", orderId),
                "幂等重复不得改动支付状态");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", orderId),
                "配送中(2) 不闭环为已完成（只有已送达才闭环）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", orderId),
                "幂等重复不得插出第二条 PAID 流水");
    }
}
