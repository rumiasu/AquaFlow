package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨站外派单取消退款回归（双水站语义边界）。
 *
 * <p>水站 A 的订单被外派给水站 B 履约（{@code station_id=A, delivery_station_id=B}）后取消，
 * 「钱」与「货」的归属是两条不同的线，历史上 {@code PaymentServiceImpl.refundOrder}
 * 把它们混用了：</p>
 *
 * <pre>
 *   钱/票（收款流水、预收押金、水票扣减）→ 记在【归属站 A】，取消必须退回 A
 *   货（库存扣减）                      → 扣在【履约站 B】，取消必须回补 B
 * </pre>
 *
 * <p>混用的后果不是"报错"，而是静默错账：押金从没收到过钱的 B 站扣（余额不足直接静默跳过，
 * 押金永远留在 A 站账上）；库存回补到 A（B 站库存凭空少）；水票退到 B
 * （{@code refundTicket} 在账户不存在时会新建账户，于是 B 站凭空多出一个水票账户）。
 * 这些都不会抛异常、也不会被单站用例发现，只能靠显式构造外派单来锁。</p>
 */
@DisplayName("Phase B · 跨站外派单取消退款")
class CrossStationRefundIntegrationTest extends AbstractIntegrationTest {

    private long stationA;   // 归属站：客户下单的服务站
    private long stationB;   // 履约站：实际配送的外派站
    private long product;
    private long customer;
    private long addr;

    private void seed() {
        stationA = createStation("归属站A");
        stationB = createStation("履约站B");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        customer = createCustomer("老王", "openid-wang");
        addr = createAddress(customer, "某小区1号");
        // 客户归属 A 站（货到付款授权也挂在 A）
        createCustomerStationConfig(customer, stationA, 1);
        // 下单时扣的是履约站 B 的库存
        createInventory(stationB, product, 10);
        createInventoryRecord(stationB, product, -4, "CONSUME", 0);
    }

    /** 造一张 A 站下单、B 站履约、水票已付、且已预收押金的外派单。 */
    private long seedCrossStationTicketOrder() {
        long order = createOrderCrossStation(customer, addr, stationA, stationB, product,
                1 /* 待配送 */, 2 /* 已付款 */, 3 /* 水票 */, "40.00", "60.00", "100.00");
        // deducted_qty 必须与"下单实际扣减量"一致：取消回补走的就是这一列
        // （为 0 表示下单时库存不足、一桶都没扣，此时不回补才是正确行为）。
        createOrderItemFull(order, product, "桶装水18.9L", 4, 4, "10.00", "0.00");
        // 水票扣在归属站 A，且唯一键是 (order_id, product_id, source)
        // 注意 helper 签名是 (customerId, stationId, productId, remainQuantity)
        createTicketAccount(customer, stationA, product, 2);
        insert("INSERT INTO ticket_record(customer_id, product_id, station_id, increase_qty, decrease_qty, "
                + "order_id, source, ticket_source, create_time) VALUES (?,?,?,0,4,?,'消费',1,NOW())",
                customer, product, stationA, order);
        // 押金入在归属站 A（AQ-009：支付成功时入账）。
        // 必须同时造出【入账凭据】(deposit_record 的 PREPAID 流水) 与账户余额：
        // 取消时是否需要释放押金，锚定的就是这张凭据（orders.deposit_amount 只是"应收"，未付款单也有值）。
        createDepositBalance(customer, stationA, "60.00");
        insert("INSERT INTO deposit_record(customer_id, station_id, type, amount, related_order_id, note, create_time) "
                        + "VALUES (?,?,5,?,?,'订单支付成功预收桶押金',NOW())",
                customer, stationA, new java.math.BigDecimal("60.00"), order);
        return order;
    }

    @Test
    @DisplayName("水票已付的跨站外派单取消：水票回归属站、押金从归属站释放、库存补履约站")
    void crossStationCancel_keepsMoneyAndGoodsOnTheirOwnStations() {
        seed();
        long order = seedCrossStationTicketOrder();

        Api res = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);
        assertTrue(res.isSuccess(), "客户取消待配送的外派单应成功，实际=" + res);

        assertEquals(5, intOf("SELECT status FROM orders WHERE id=?", order), "订单应置已取消");

        // ---- 票：扣在 A、退在 A ----
        // 造数时 A 站余额已是"扣完 4 张"的 2 张，退 4 张后应回到 6 张
        assertEquals(6, intOf("SELECT remain_quantity FROM ticket_account "
                        + "WHERE customer_id=? AND product_id=? AND station_id=?", customer, product, stationA),
                "水票必须退回【归属站 A】的账户");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_account "
                        + "WHERE customer_id=? AND product_id=? AND station_id=?", customer, product, stationB),
                "履约站 B 不该凭空多出一个水票账户");

        // ---- 钱：入在 A、退在 A ----
        assertEquals(0, decimalOf("SELECT balance FROM customer_deposit_account "
                        + "WHERE customer_id=? AND station_id=?", customer, stationA).compareTo(java.math.BigDecimal.ZERO),
                "归属站 A 的预收押金必须被释放；实际余额行=" + jdbc.queryForList(
                        "SELECT customer_id,station_id,balance FROM customer_deposit_account WHERE customer_id=?",
                        customer));
        assertEquals(1, intOf("SELECT COUNT(*) FROM deposit_record "
                        + "WHERE related_order_id=? AND station_id=? AND type=8", order, stationA),
                "必须留下一条记在 A 站名下的释放流水（旧实现会被静默跳过）；实际该单押金流水=" + jdbc.queryForList(
                        "SELECT id,customer_id,station_id,type,amount,related_order_id FROM deposit_record WHERE related_order_id=?",
                        order));

        // ---- 货：扣在 B、补在 B ----
        assertEquals(14, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                        stationB, product),
                "库存必须回补【履约站 B】");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record "
                        + "WHERE ref_id=? AND station_id=? AND type='REFUND_RESTORE'", order, stationB),
                "回补流水也必须记在 B 站；实际该单流水=" + jdbc.queryForList(
                        "SELECT id,station_id,delta,type,ref_id FROM inventory_record WHERE ref_id=?", order));
    }

    @Test
    @DisplayName("归属站押金余额不足时，取消必须显式失败而不是静默留下敞口")
    void crossStationCancel_depositShortfall_shouldFailLoudly() {
        seed();
        long order = seedCrossStationTicketOrder();
        // 把钱挪走：归属站押金账户余额不足（模拟账已被人为改动 / 押金曾被手工退过）
        jdbc.update("UPDATE customer_deposit_account SET balance = 0 WHERE customer_id=? AND station_id=?",
                customer, stationA);

        Api res = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);

        // 旧实现：affected=0 被静默跳过 → 返回成功，押金留在账上但没有任何流水可查。
        // 现要求：拒绝并给出可排查的文案，事务整体回滚（订单保持待配送）。
        assertTrue(!res.isSuccess(), "押金余额不足时不应静默成功，实际=" + res);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order),
                "取消失败必须整体回滚，订单仍是待配送");
    }

    @Test
    @DisplayName("重复取消同一单：押金只能被释放一次（取消 CAS 必须检查 affected rows）")
    void cancelTwice_releasesDepositOnlyOnce() {
        seed();
        long order = seedCrossStationTicketOrder();

        Api first = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);
        assertTrue(first.isSuccess(), "首次取消应成功，实际=" + first);

        // 第二次：订单已是已取消，业务上必须被拒。
        // 旧实现在 refundOrder 末尾不检查 CAS 的 affected rows，重复调用会再走一遍释放段。
        Api second = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);
        assertTrue(!second.isSuccess(), "重复取消必须被拒，实际=" + second);

        assertEquals(1, intOf("SELECT COUNT(*) FROM deposit_record "
                        + "WHERE related_order_id=? AND type=8", order),
                "同一订单只允许存在一条预收押金释放流水");
        assertEquals(0, decimalOf("SELECT balance FROM customer_deposit_account "
                        + "WHERE customer_id=? AND station_id=?", customer, stationA)
                        .compareTo(java.math.BigDecimal.ZERO),
                "押金余额不能被扣成负数");
    }
}
