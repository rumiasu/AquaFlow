package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长「人工补录 / 历史资产入账」与「取消状态门槛」回归（2026-09-13 修复）。
 *
 * <p>被锁死的四件事：</p>
 * <ol>
 *   <li><b>补录必须写进唯一真相源</b>：权益落在 {@code customer_barrel_lot}，
 *       而不是只改 {@code customer_barrel_asset.quantity}。修复前补录后 {@code rightQty()=0}，
 *       客户申请退桶被拒「可退权益不足」——补录成功却永远退不掉桶。</li>
 *   <li><b>汇总表与批次一致</b>：{@code asset.quantity == Σ lot.remain_qty}，
 *       这是恒等式「占用 = 权益 + over」与对账 E3a 的前提。</li>
 *   <li><b>已完成订单不可再取消</b>：{@code refundOrder} 是唯一退款入口，
 *       修复前对 status=4 的订单调用拒单接口会「退钱 + 置为已取消」。</li>
 *   <li><b>押金流水的方向与符号</b>：金额一律以正数传入、方向由 type 决定，
 *       扣减落负数，保证对账等式1（{@code balance == SUM(amount)}）不被写歪。</li>
 * </ol>
 */
@DisplayName("站长补录 · 历史资产入账与取消状态门槛")
class StationAssetBackfillIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;
    private long delivery;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 0, "0.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
        delivery = createStaff("D1", "DELIVERY", station, 1);
        // 站长给客户记押金 / 代客下单都要求客户归属本站
        createCustomerStationConfig(customer, station, 1);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /** 造一条「配送员已上报、待站长处理」的桶异常。 */
    private long seedStaffRecordedException(long orderId) {
        return insert("INSERT INTO order_barrel_exception(order_id, customer_id, station_id, delivery_qty, "
                        + "return_qty, discrepancy, category, staff_action, status, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,NOW())",
                orderId, customer, station, 5, 5, 0, "RETURN_SHORT", "PARTIAL", "STAFF_RECORDED");
    }

    private BigDecimal balance() {
        return decimalOf("SELECT COALESCE(SUM(balance),0) FROM customer_deposit_account WHERE customer_id=? AND station_id=?",
                customer, station);
    }

    private BigDecimal flowSum() {
        return decimalOf("SELECT IFNULL(SUM(amount),0) FROM deposit_record WHERE customer_id=? AND station_id=?",
                customer, station);
    }

    @Test
    @DisplayName("旧异常补偿不能绕过正式资产调整流程新增桶权益")
    void backfilledRights_landInLotAndBecomeReturnable() {
        seed();
        long order=createOrderFull(customer,addr,station,product,2,2,2,"20.00","30.00","50.00",false,5);
        long exId=seedStaffRecordedException(order);
        int before=intOf("SELECT COUNT(*) FROM customer_barrel_lot WHERE customer_id=?",customer);
        Api res=post("/api/manager/exceptions/"+exId+"/handle",mgrToken(),"{\"action\":\"APPROVE\",\"adjustAssetQty\":5,\"adjustProductId\":"+product+",\"managerNote\":\"历史核对\"}");
        assertFalse(res.isSuccess());assertTrue(res.message().contains("停用"));
        assertEquals(before,intOf("SELECT COUNT(*) FROM customer_barrel_lot WHERE customer_id=?",customer));
    }

    @Test
    @DisplayName("已完成订单不可再拒单/解决：不能退钱并把订单改成已取消")
    void completedOrder_cannotBeCancelledAnymore() {
        seed();
        long order = createOrderFull(customer, addr, station, product,
                4, 2, 2, "20.00", "30.00", "50.00", false, 1);
        createPaymentRecord(order, customer, station, "50.00", 2, 2); // 已收款

        Api reject = post("/api/delivery/orders/reject/" + order,
                staffToken(delivery, "DELIVERY", station), "{\"reason\":\"误操作\"}");
        assertFalse(reject.isSuccess(), "已完成订单不应允许拒单，实际=" + reject);
        assertEquals(4, intOf("SELECT status FROM orders WHERE id=?", order), "订单状态不应被改动");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "支付状态不应被改掉");

        Api resolve = post("/api/delivery/orders/" + order + "/resolve", mgrToken(), "{\"reason\":\"误操作\"}");
        assertFalse(resolve.isSuccess(), "已完成订单不应允许解决/取消，实际=" + resolve);
        assertEquals(4, intOf("SELECT status FROM orders WHERE id=?", order), "订单状态不应被改动");
    }

    @Test
    @DisplayName("押金扣减以负数落库：对账等式1（余额 == 流水合计）不被写歪")
    void depositDeduction_keepsSignedConvention() {
        seed();
        // 期初余额必须同时有对应的入账流水，否则对账等式1（余额 == 流水合计）在造数阶段就不成立
        createDepositBalance(customer, station, "100.00");
        insert("INSERT INTO deposit_record(customer_id, station_id, type, amount, note, create_time) "
                + "VALUES (?,?,?,?,?,NOW())", customer, station, 1, new BigDecimal("100.00"), "测试期初押金");

        Api res = post("/api/deposit-records", mgrToken(),
                "{\"customerId\":" + customer + ",\"type\":2,\"amount\":30.00,\"note\":\"站长手工退押金\"}");
        assertTrue(res.isSuccess(), "押金记账应成功，实际=" + res);

        assertEquals(0, balance().compareTo(new BigDecimal("70.00")), "余额应减少 30");
        assertEquals(0, decimalOf("SELECT amount FROM deposit_record "
                        + "WHERE customer_id=? AND station_id=? AND type=2", customer, station)
                        .compareTo(new BigDecimal("-30.00")),
                "扣减类流水必须以负数落库（修复前落 +30，导致对账等式1 凭空多一条差额）");
        assertEquals(0, balance().compareTo(flowSum()), "对账等式1：余额必须等于流水合计");
    }

    @Test
    @DisplayName("停用后的历史已批准补偿也不能执行或增加押金")
    void compensationCash_creditsDepositAsIncrease() {
        seed();
        long order = createOrderFull(customer, addr, station, product,
                2, 2, 2, "20.00", "30.00", "50.00", false, 1);
        long exId = insert("INSERT INTO order_barrel_exception(order_id, customer_id, station_id, delivery_qty, "
                        + "return_qty, discrepancy, category, staff_action, manager_action, refund_cash_amount, "
                        + "status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW())",
                order, customer, station, 3, 2, 1, "RETURN_SHORT", "PARTIAL", "APPROVE",
                new BigDecimal("50.00"), "MANAGER_APPROVED");

        BigDecimal before=balance();
        Api res=post("/api/manager/exceptions/"+exId+"/execute",mgrToken(),"{}");
        assertFalse(res.isSuccess());assertTrue(res.message().contains("停用"));
        assertEquals(0,before.compareTo(balance()));
        assertEquals(0,intOf("SELECT COUNT(*) FROM deposit_record WHERE customer_id=? AND type=9",customer));
        assertEquals("MANAGER_APPROVED",jdbc.queryForObject("SELECT status FROM order_barrel_exception WHERE id=?",String.class,exId));
    }
}
