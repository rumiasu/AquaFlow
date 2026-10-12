package com.example.aquaflow.integration;

import com.example.aquaflow.service.UnpaidWechatOrderSweeper;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 微信未付单超时自动取消（2026-09-18 产品裁定：只对微信单，货到付款是特殊）。
 *
 * <p>它与「先付款后派单」是一对：未付微信单不进站长/配送员视野，但不能永远占着库存 ——
 * 到点自动取消，且取消必须走**取消订单的唯一编排入口**（回补库存、清配送中桶…），
 * 否则就会出现"库存扣了、单没了"的窟窿。</p>
 *
 * <p>用例直接调定时任务用的那个方法（{@code sweepOnce}），不依赖 cron 时点；
 * 造数用"真实下单 + 回拨 create_time"，以覆盖真实链路（含库存扣减）。</p>
 */
@DisplayName("微信未付单超时自动取消：只碰微信单，取消要回补库存")
class UnpaidWechatOrderTimeoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UnpaidWechatOrderSweeper sweeper;

    private long station;
    private long customer;
    private long address;
    private long goods;
    private String cus;
    private String mgrToken;

    private void seed() {
        station = createStation("超时站");
        long mgr = createStaff("超时站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("超时客户", "timeout-openid");
        createCustomerStationConfig(customer, station, 1);
        address = createAddress(customer, "超时小区1号");
        goods = createProduct("超时饮水机", 2, "20.00", "0.00", 0, "0.00");
        createInventoryFull(station, goods, 100, 0, "0.00");
        cus = customerToken(customer);
        mgrToken = staffToken(mgr, "STATION_MANAGER", station);
    }

    private long place(int payMethod, String key) {
        assertEquals(0, post("/api/orders/create", cus, "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":" + payMethod + ",\"idempotencyKey\":\"" + key + "\","
                + "\"items\":[{\"productId\":" + goods + ",\"quantity\":1}]}").code(), "下单应成功");
        return longOf("SELECT id FROM orders WHERE idempotency_key=?", key);
    }

    /** 把建单时间回拨到超时之前（造数手法：不动业务字段，只挪时间）。 */
    private void backdate(long orderId, int minutes) {
        jdbc.update("UPDATE orders SET create_time = date_sub(now(), interval ? minute) WHERE id = ?", minutes, orderId);
    }

    private int stock() {
        return intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", station, goods);
    }

    /** 本站该商品"活跃预留"总量（[2026-09-25 库存预留模型]：下单占的是它，不是实物）。 */
    private int reserved() {
        return intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE station_id=? AND product_id=? AND status=1", station, goods);
    }

    @Test
    @DisplayName("超时的微信未付单被取消并释放预留；未超时的、现金的、已付的都不动")
    void onlyTimedOutUnpaidWechatOrdersAreCancelled() {
        seed();
        int stockBefore = stock();

        // ① 超时未付的微信单（31 分钟前下的）→ 取消 + 释放预留
        long timedOut = place(1, "timeout-wechat-old");
        backdate(timedOut, 31);
        // [2026-09-25 库存预留模型] 下单只预留、不动实物（旧口径这里是 stockBefore-1）
        assertEquals(stockBefore, stock(), "下单不动实物");
        assertEquals(1, reserved(), "下单应预留 1 件");

        // ② 刚下的微信单（不该被动）
        long fresh = place(1, "timeout-wechat-fresh");

        // ③ 现金单（货到付款是特殊：未付是正常经营状态，永远不该被自动取消）
        long cash = place(2, "timeout-cash-old");
        backdate(cash, 600);

        // ④ 已付款的微信单（站长手工确认过到账）—— 钱已到手，不是"未支付"，同样不动
        long paid = place(1, "timeout-wechat-paid");
        backdate(paid, 600);
        assertEquals(0, post("/api/payments", mgrToken,
                "{\"orderId\":" + paid + ",\"paymentMethod\":1}").code(), "发起收款应成功");
        long paymentId = longOf("SELECT id FROM payment_record WHERE order_id=? AND status=1", paid);
        assertEquals(0, put("/api/payments/" + paymentId + "/confirm", mgrToken, "{}").code(),
                "站长确认到账（微信渠道未接入时的人工通道）");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", paid), "应已置已付");

        // 四张单各预留 1 件（此时还没取消任何一张），实物一件都没动
        assertEquals(4, reserved(), "四张单各预留 1 件");
        assertEquals(stockBefore, stock(), "下单不动实物");

        int cancelled = sweeper.sweepOnce();
        assertEquals(1, cancelled, "只应取消那一张超时的微信未付单");

        assertEquals(5, intOf("SELECT status FROM orders WHERE id=?", timedOut), "超时单应已取消");
        // [2026-09-25 库存预留模型] 取消走的是"释放预留"，**不回补库存**（实物从没减过）——
        // 旧口径在这里断言 stockBefore-3（四张扣 4、取消还 1），新模型下实物全程是 stockBefore。
        assertEquals(3, reserved(), "取消必须释放那张单的预留：四张占 4、释放 1 ⇒ 剩 3");
        assertEquals(stockBefore, stock(), "取消不得回补库存（实物从没被扣过）");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_reservation "
                + "WHERE order_id=? AND status=1", timedOut), "超时单的预留必须已释放");

        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", fresh), "刚下的单不该被动");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", cash), "现金单（货到付款）永远不自动取消");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", paid), "已付款的单不该被动");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", paid), "已付款状态不得被改写");

        // 幂等：再扫一轮没有可取消的（已取消的单不再命中判据）
        assertEquals(0, sweeper.sweepOnce(), "重复扫描不得重复取消");
        assertEquals(3, reserved(), "重复扫描不得重复释放预留");
        assertEquals(stockBefore, stock(), "实物始终不变");
    }

    @Test
    @DisplayName("未付款但已出车的微信单不自动取消（status 门槛）")
    void deliveringOrderIsNotAutoCancelled() {
        seed();
        long order = place(1, "timeout-wechat-delivering");
        backdate(order, 600);
        // 已出车（配送中）：判据里 status = 1 那一关把它挡在外面
        jdbc.update("UPDATE orders SET status = 2 WHERE id = ?", order);

        assertEquals(0, sweeper.sweepOnce(), "配送中的单不该被自动取消");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "状态不得被改动");
    }

    @Test
    @DisplayName("前200张有待收款流水时，末尾无流水的超时微信单仍会取消")
    void activePaymentsDoNotStarveTimedOutOrderPastBatchLimit() {
        seed();
        assertEquals(2, intOf("SELECT category FROM product WHERE id=?", goods), "反例商品须是非桶装水商品");
        jdbc.update("UPDATE inventory SET quantity=500 WHERE station_id=? AND product_id=?", station, goods);
        int stockBefore = stock();
        long lastOrder = 0;

        // 201 张旧的非桶装水微信单按 id 入队；前 200 张有活跃 PENDING 流水，末张没有。
        for (int i = 0; i < 201; i++) {
            String key = "timeout-starvation-" + i;
            long order = place(1, key);
            backdate(order, 600);
            if (i < 200) {
                createPaymentRecord(order, customer, station, "20.00", 1, 1);
            } else {
                lastOrder = order;
            }
        }

        assertEquals(200, intOf("SELECT COUNT(*) FROM orders o JOIN payment_record p ON p.order_id=o.id "
                + "WHERE o.idempotency_key LIKE 'timeout-starvation-%' AND p.status=1"),
                "前200张订单各有一条待收款流水");
        assertEquals(201, intOf("SELECT COUNT(*) FROM orders WHERE idempotency_key LIKE 'timeout-starvation-%' "
                + "AND payment_status=1"), "201 张订单初始状态均为 PENDING");
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status IN (1,2)", lastOrder),
                "末张超时单无活跃支付流水");
        assertEquals(201, reserved(), "201 张单均已预留库存");
        assertEquals(stockBefore, stock(), "预留不会扣减实物库存");

        assertEquals(1, sweeper.sweepOnce(), "候选批次应跳过有活跃流水的前200张并取消末张");
        assertEquals(5, intOf("SELECT status FROM orders WHERE id=?", lastOrder), "末张应取消");
        assertEquals(4, intOf("SELECT payment_status FROM orders WHERE id=?", lastOrder), "未收款末张应记为已取消");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory_reservation WHERE order_id=? AND status=1", lastOrder),
                "末张取消后应释放活跃库存预留");
        assertEquals(200, intOf("SELECT COUNT(*) FROM orders o JOIN payment_record p ON p.order_id=o.id "
                + "WHERE o.idempotency_key LIKE 'timeout-starvation-%' AND o.status=1 "
                + "AND o.payment_status=1 AND p.status=1"),
                "前200张有待收款流水的订单不得取消、改写 PENDING 支付状态或改动流水");
        assertEquals(200, reserved(), "只释放末张的预留，库存预留不可多退");
        assertEquals(stockBefore, stock(), "取消不应改动未扣减的实物库存");

        assertEquals(0, sweeper.sweepOnce(), "重复扫描不得再次取消或产生资金动作");
        assertEquals(200, intOf("SELECT COUNT(*) FROM payment_record p JOIN orders o ON o.id=p.order_id "
                + "WHERE o.idempotency_key LIKE 'timeout-starvation-%' AND p.status=1"),
                "前200笔资金请求仍保持待核实");
        assertEquals(200, reserved(), "重复扫描不得重复释放库存预留");
        assertEquals(stockBefore, stock(), "重复扫描不得改变实物库存");
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=?", lastOrder),
                "末张无支付流水，不得凭空生成退款流水");
    }
}
