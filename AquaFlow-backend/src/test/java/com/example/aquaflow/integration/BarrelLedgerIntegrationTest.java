package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桶账（权益 / over / 押金条）回归。
 *
 * <p>被锁死的不变量：</p>
 * <ul>
 *   <li>恒等式 <b>占用 = 权益 + over</b>，其中 over 可为负（多还桶 / 水站暂存），负值是合法状态。</li>
 *   <li>首次购买在<b>配送完成</b>时才建押金条（lot），单价快照下单当时的押金价。</li>
 *   <li>纯还桶只冲 over，不扣权益、不核销批次、不产生退款；同 token 幂等。</li>
 *   <li>退桶退款只认批次买入单价，按 FIFO 核销。</li>
 *   <li>按 (customer, station, product) 三维隔离：A 商品的还桶不能抵 B 商品的欠桶，
 *       同一客户在 S2 的账不能动 S1。</li>
 * </ul>
 */
@DisplayName("Phase B · 桶账（首购/换桶/纯还桶/退桶FIFO/隔离）")
class BarrelLedgerIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 0, "0.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /** 该商品上仍有效的权益（Σ 未退 remain_qty）。 */
    private int right() {
        return intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1", customer, station, product);
    }

    /** over：正=欠桶，负=多还桶（水站暂存）。 */
    private int over() {
        return intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product);
    }

    private Api returnEmpty(String clientToken, int qty, long productId) {
        String body = "{\"customerId\":" + customer + ",\"clientToken\":\"" + clientToken + "\","
                + "\"items\":[{\"productId\":" + productId + ",\"qty\":" + qty + "}]}";
        return post("/api/barrels/return-empty", mgrToken(), body);
    }

    @Test
    @DisplayName("首次购买：配送完成建押金条，权益=1、over=0（恒等式自洽）")
    void firstPurchase_createsLotOnDelivery() {
        seed();
        // 下单时算出的 shortage=1：钱已付、桶还没送到 → 记在「配送中」
        long order = createOrderFull(customer, addr, station, product,
                2 /* 配送中 */, 0, 2 /* 现金 */, "20.00", "30.00", "50.00", true /* 首次桶装水订单 */, 1);
        createOrderItem(order, product, "桶装水18.9L", 1, "20.00", "30.00", 1);
        createBarrelInTransit(customer, station, product, 1, "30.00", order, "PENDING");

        Api res = post("/api/delivery/orders/" + order + "/complete", mgrToken(), "{}");
        assertTrue(res.isSuccess(), "配送完成应成功，实际=" + res);

        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_barrel_lot "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "应建立一张押金条");
        assertEquals(0, new BigDecimal("30.00").compareTo(
                        decimalOf("SELECT unit_price FROM customer_barrel_lot "
                                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product)),
                "押金条单价应快照下单当时押金价 30.00");
        assertEquals(1, right(), "权益应为 1");
        assertEquals(0, over(), "首购买 1 送 1 收 0 → over 应为 0");
        assertEquals(1, intOf("SELECT quantity FROM customer_barrel_asset "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "权益汇总数量应为 1");
        assertEquals("DELIVERED",
                jdbc.queryForObject("SELECT status FROM customer_barrel_in_transit WHERE related_order_id=?",
                        String.class, order),
                "配送中桶应标记 DELIVERED 而非删除");
    }

    @Test
    @DisplayName("换桶：收回 N 送 N，权益与 over 均不变")
    void exchange_keepsRightConstant() {
        seed();
        // 已持有 1 个桶（旧流程已建批次）
        createBarrelLot("DP-EX-1", customer, station, product, "30.00", 1, 1);
        createBarrelAsset(customer, station, product, 1, "30.00");

        long order = createOrderFull(customer, addr, station, product,
                2 /* 配送中 */, 0, 2 /* 现金 */, "20.00", "0.00", "20.00", false, 1);
        long itemId = createOrderItem(order, product, "桶装水18.9L", 1, "20.00", "30.00", 1);

        // 送 1 个满桶、收回 1 个空桶、本单新购 0 → delta = 1-1-0 = 0
        Api res = post("/api/delivery/orders/" + order + "/complete", mgrToken(),
                "{\"itemReturns\":[{\"orderItemId\":" + itemId + ",\"actual\":1}]}");
        assertTrue(res.isSuccess(), "换桶配送完成应成功，实际=" + res);

        assertEquals(1, right(), "换桶不应改变权益");
        assertEquals(0, over(), "收回 1 送 1、新购 0 → over 应仍为 0");
        assertEquals(1, intOf("SELECT remain_qty FROM customer_barrel_lot WHERE lot_no='DP-EX-1'"),
                "换桶不应核销权益批次");
    }

    @Test
    @DisplayName("纯还桶：只冲 over（可为负），不扣权益、不退款；同 token 幂等")
    void pureReturn_reducesOverOnly() {
        seed();
        createBarrelLot("DP-PR-1", customer, station, product, "30.00", 1, 1);
        createBarrelAsset(customer, station, product, 1, "30.00");
        createDepositBalance(customer, station, "30.00");

        Api res = returnEmpty("tok-pr-1", 1, product);
        assertTrue(res.isSuccess(), "纯还桶应成功，实际=" + res);

        assertEquals(-1, over(), "还 1 桶后 over 应为 -1（水站暂存，合法）");
        assertEquals(1, right(), "纯还桶绝不扣权益");
        assertEquals(1, intOf("SELECT remain_qty FROM customer_barrel_lot WHERE lot_no='DP-PR-1'"),
                "批次不应被核销");
        assertEquals(0, new BigDecimal("30.00").compareTo(
                        decimalOf("SELECT IFNULL(balance,0) FROM customer_deposit_account "
                                + "WHERE customer_id=? AND station_id=?", customer, station)),
                "纯还桶不得产生任何退款");
        assertEquals(1, intOf("SELECT COUNT(*) FROM barrel_record WHERE customer_id=? AND type=7", customer),
                "应写一条 type=7 纯还桶流水");

        // 同一 token 重试：必须幂等返回成功，且不能再冲一次 over
        Api retry = returnEmpty("tok-pr-1", 1, product);
        assertTrue(retry.isSuccess(), "同 token 重试应幂等成功，实际=" + retry);
        assertEquals(-1, over(), "幂等重试不得再次冲减 over");
    }

    @Test
    @DisplayName("over<0 在客户摘要中体现为「水站暂存」，不得算作欠桶")
    void negativeOver_isReportedAsStorage() {
        seed();
        createBarrelLot("DP-PR-2", customer, station, product, "30.00", 1, 1);
        createBarrelAsset(customer, station, product, 1, "30.00");

        assertTrue(returnEmpty("tok-pr-2", 1, product).isSuccess());

        Api res = get("/api/barrels/summary?stationId=" + station, customerToken(customer));
        assertTrue(res.isSuccess(), "读取桶摘要应成功，实际=" + res);

        assertEquals(0, res.data().path("owedBuckets").asInt(), "多还桶不是欠桶");
        assertEquals(1, res.data().path("storageBuckets").asInt(), "多还 1 桶应显示为水站暂存 1");
    }

    @Test
    @DisplayName("退桶：按批次 FIFO 核销，退款只认买入单价")
    void returnRefund_isFifoByLotPrice() {
        seed();
        // 先买 3 个 @30，后买 2 个 @40；权益 5 个、可退桶款 170.00
        createBarrelLot("DP-F-1", customer, station, product, "30.00", 3, 3);
        createBarrelLot("DP-F-2", customer, station, product, "40.00", 2, 2);
        createBarrelAsset(customer, station, product, 5, "170.00");
        createDepositBalance(customer, station, "170.00");

        Api res = post("/api/barrels/return", customerToken(customer),
                "{\"stationId\":" + station + ",\"productId\":" + product + ",\"quantity\":4}");
        assertTrue(res.isSuccess(), "退桶申请应成功，实际=" + res);

        long recordId = res.data().path("recordId").asLong();
        // 试算金额 = 3×30 + 1×40 = 130（不是 4×40，也不是 4×商品现价）
        assertEquals(0, new BigDecimal("130.00").compareTo(res.data().path("refundAmount").decimalValue()),
                "试算退款应为 FIFO 核销的 130.00");

        assertTrue(put("/api/barrels/records/" + recordId + "/status", mgrToken(), "{\"status\":2}").isSuccess(),
                "确认收到空桶应成功");
        assertTrue(put("/api/barrels/records/" + recordId + "/status", mgrToken(), "{\"status\":3}").isSuccess(),
                "退押金应成功");

        assertEquals(0, intOf("SELECT remain_qty FROM customer_barrel_lot WHERE lot_no='DP-F-1'"),
                "FIFO 应先核销先买入的批次");
        assertEquals(1, intOf("SELECT remain_qty FROM customer_barrel_lot WHERE lot_no='DP-F-2'"),
                "第二批应只核销 1 个");
        assertEquals(1, intOf("SELECT quantity FROM customer_barrel_asset "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "权益汇总数量应剩 1");
        assertEquals(0, new BigDecimal("40.00").compareTo(
                        decimalOf("SELECT right_amount FROM customer_barrel_asset "
                                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product)),
                "可退桶款应剩余 40.00");
        assertEquals(0, new BigDecimal("40.00").compareTo(
                        decimalOf("SELECT IFNULL(balance,0) FROM customer_deposit_account "
                                + "WHERE customer_id=? AND station_id=?", customer, station)),
                "押金余额应扣减 130 后剩 40.00");
        assertEquals(2, intOf("SELECT COUNT(*) FROM barrel_record_lot WHERE record_id=?", recordId),
                "核销明细应留痕两行（两张批次）");
    }

    @Test
    @DisplayName("跨商品隔离：A 水的还桶不抵 B 水的欠桶")
    void crossProductIsolation() {
        seed();
        long product2 = createProduct("桶装水12L", 1, "15.00", "25.00", 0, "0.00");

        createBarrelLot("DP-P1", customer, station, product, "30.00", 1, 1);
        createBarrelAsset(customer, station, product, 1, "30.00");
        createBarrelLot("DP-P2", customer, station, product2, "25.00", 1, 1);
        createBarrelAsset(customer, station, product2, 1, "25.00");

        assertTrue(returnEmpty("tok-cp", 1, product).isSuccess());

        assertEquals(-1, over(), "P1 应记为多还桶");
        assertEquals(0, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product2),
                "P2 的 over 不得被 P1 的还桶影响");
    }

    @Test
    @DisplayName("跨水站隔离：同一客户在 S2 的还桶不影响 S1")
    void crossStationIsolation() {
        seed();
        long station2 = createStation("S2");

        createBarrelLot("DP-S1", customer, station, product, "30.00", 1, 1);
        createBarrelAsset(customer, station, product, 1, "30.00");
        createBarrelLot("DP-S2", customer, station2, product, "30.00", 1, 1);
        createBarrelAsset(customer, station2, product, 1, "30.00");

        assertTrue(returnEmpty("tok-cs", 1, product).isSuccess());

        assertEquals(-1, over(), "S1 应记为多还桶");
        assertEquals(0, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station2, product),
                "S2 的 over 必须独立，不得共用 S1 的账");
    }
}
