package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长资产调整单（station_adjustment）回归。
 *
 * <p>被锁死的不变量：</p>
 * <ol>
 *   <li><b>唯一写入口</b>：桶类调整写进 {@code customer_barrel_lot}（真相源）并同步汇总，
 *       押金走 {@code deposit_record} 成对写；不出现"直写余额"的第二条路径。</li>
 *   <li><b>一张单只生效一次</b>：执行是 CAS；重复执行被拒绝；{@code clientToken} 幂等。</li>
 *   <li><b>不改历史</b>：撤销生成反向单，原单置 REVERSED。</li>
 *   <li><b>跨站与角色</b>：他站客户、DELIVERY 角色一律拒绝。</li>
 *   <li><b>对账不误报</b>：人工调整落 barrel_record type=6/9 后，E5 物理桶守恒仍为 0；
 *       补录权益 + 补录押金之后整体对账 totalDiff 为 0。</li>
 * </ol>
 */
@DisplayName("站长资产调整单（补录/订正/撤销/幂等/对账）")
class StationAdjustmentIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 0, "0.00");
        // 期初库存必须同时有对应流水，否则对账等式4（inventory.quantity vs Σinventory_record.delta）不平。
        // 本项目「库存变动必须留流水」（AQ-029）——造数也要遵守，否则对账会把夹具问题当业务问题报出来。
        createInventoryRecord(station, product, 10, "INBOUND", 0);
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
        createCustomerStationConfig(customer, station, 1);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    private Api createAdjustment(String type, Long productId, Integer qty, String amount,
                                 String unitPrice, String token) {
        String body = "{\"customerId\":" + customer
                + ",\"adjustType\":\"" + type + "\""
                + (productId == null ? "" : ",\"productId\":" + productId)
                + (qty == null ? "" : ",\"qty\":" + qty)
                + (amount == null ? "" : ",\"amount\":" + amount)
                + (unitPrice == null ? "" : ",\"unitPrice\":" + unitPrice)
                + ",\"reason\":\"历史补录\",\"clientToken\":\"" + token + "\"}";
        return post("/api/manager/adjustments", mgrToken(), body);
    }

    private long createAndExecute(String type, Long productId, Integer qty, String amount,
                                 String unitPrice, String token) {
        Api created = createAdjustment(type, productId, qty, amount, unitPrice, token);
        assertTrue(created.isSuccess(), "创建调整单应成功，实际=" + created);
        long id = created.data().path("id").asLong();
        Api executed = post("/api/manager/adjustments/" + id + "/execute", mgrToken(), "{}");
        assertTrue(executed.isSuccess(), "执行调整单应成功，实际=" + executed);
        return id;
    }

    private int right() {
        return intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1", customer, station, product);
    }

    private BigDecimal depositBalance() {
        return decimalOf("SELECT IFNULL(balance,0) FROM customer_deposit_account WHERE customer_id=? AND station_id=?",
                customer, station);
    }

    @Test
    @DisplayName("补录桶权益 + 补录押金：写进真相源、客户可退桶、对账不误报")
    void barrelGrant_withDeposit_isReturnableAndReconciliationStaysClean() {
        seed();

        // 0) 试算接口（只读）：应给出前后对比，且不落库
        Api preview = post("/api/manager/adjustments/preview", mgrToken(),
                "{\"customerId\":" + customer + ",\"adjustType\":\"BARREL_GRANT\",\"productId\":" + product
                        + ",\"qty\":5}");
        assertTrue(preview.isSuccess(), "试算应成功，实际=" + preview);
        assertEquals(0, preview.data().path("before").path("right").asInt(), "试算前权益应为 0");
        assertEquals(5, preview.data().path("after").path("right").asInt(), "试算后权益应为 5");
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_adjustment"), "试算不得落库");

        // 1) 补录 5 个桶权益（不给单价 → 回退商品押金 30.00，并标记为推断值）
        long rId = createAndExecute("BARREL_GRANT", product, 5, null, null, "TOK-BARREL-GRANT-1");

        assertEquals(5, right(), "权益必须落在批次真相源（修复前直写 asset.quantity，rightQty 恒为 0）");
        assertEquals(5, intOf("SELECT quantity FROM customer_barrel_asset "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "asset.quantity 必须等于 Σlot.remain_qty");
        assertEquals(3, intOf("SELECT source_type FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "批次来源应为 3=人工补录");
        assertEquals(1, intOf("SELECT is_migrated FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "未给单价时单价为推断值，应标记 is_migrated=1");
        assertEquals(0, decimalOf("SELECT unit_price FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product)
                .compareTo(new BigDecimal("30.00")), "单价应回退到商品押金 30.00");
        assertEquals(1, intOf("SELECT COUNT(*) FROM barrel_record WHERE type=6 AND adjustment_id=?", rId),
                "应写一条 type=6（人工调整-增加）且带 adjustment_id 的桶流水");

        // 2) 补录押金 150（5 × 30），与权益配套 —— 历史客户当初确实交过这笔钱
        createAndExecute("DEPOSIT_GRANT", null, null, "150.00", null, "TOK-DEPOSIT-GRANT-1");
        assertEquals(0, depositBalance().compareTo(new BigDecimal("150.00")), "押金余额应为 150");
        assertEquals(0, decimalOf("SELECT amount FROM deposit_record WHERE type=9").compareTo(new BigDecimal("150.00")),
                "9 类流水应为正数（余额增加）");

        // 3) 对账必须干净：E5 已把人工调整纳入守恒，E6 穿底也因押金配套而平衡
        //    注意：本站对账接口返回的是 station-scoped 结果（只含本水站），不含全平台数据
        Api recon = get("/api/manager/reconciliation", mgrToken());
        assertTrue(recon.isSuccess(), "对账应可执行，实际=" + recon);
        assertEquals(0, recon.data().path("checks").path("SE5_physicalConservation").asInt(),
                "E5 物理桶守恒应为 0（人工调整 type=6/9 必须纳入守恒，否则每次补录都误报）");
        assertEquals(0, recon.data().path("totalDiff").asInt(), "本站整体对账不应有差异，实际=" + recon);

        // 4) 客户能申请退桶（补录前这里是"可退权益不足"）
        Api apply = post("/api/barrels/return", customerToken(customer),
                "{\"stationId\":" + station + ",\"productId\":" + product + ",\"quantity\":5}");
        assertTrue(apply.isSuccess(), "补录后客户应能申请退桶，实际=" + apply);
    }

    @Test
    @DisplayName("幂等：同一 clientToken 重复提交返回原单，不产生第二条")
    void duplicateClientToken_returnsSameAdjustment() {
        seed();
        Api first = createAdjustment("BARREL_GRANT", product, 3, null, null, "TOK-DUP-1");
        Api second = createAdjustment("BARREL_GRANT", product, 3, null, null, "TOK-DUP-1");
        assertTrue(first.isSuccess() && second.isSuccess(), "两次提交都应返回成功");
        assertEquals(first.data().path("id").asLong(), second.data().path("id").asLong(),
                "同一 clientToken 必须返回同一张单");
        assertEquals(1, intOf("SELECT COUNT(*) FROM station_adjustment"), "库中只应有一张单");
    }

    @Test
    @DisplayName("一张单只生效一次：重复执行被拒绝，状态保持不变")
    void executeTwice_isRejected() {
        seed();
        long id = createAndExecute("BARREL_GRANT", product, 2, null, null, "TOK-EXEC-1");
        Api again = post("/api/manager/adjustments/" + id + "/execute", mgrToken(), "{}");
        assertFalse(again.isSuccess(), "重复执行应被拒绝，实际=" + again);
        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM station_adjustment WHERE id=?", String.class, id), "状态应保持已生效");
        assertEquals(2, right(), "权益不应被重复发放");
    }

    @Test
    @DisplayName("撤销：生成反向单，资产回到调整前，原单置 REVERSED")
    void reverse_restoresAssetAndMarksOriginal() {
        seed();
        long id = createAndExecute("DEPOSIT_GRANT", null, null, "80.00", null, "TOK-REV-1");
        assertEquals(0, depositBalance().compareTo(new BigDecimal("80.00")), "撤销前余额应为 80");

        Api rev = post("/api/manager/adjustments/" + id + "/reverse", mgrToken(),
                "{\"reason\":\"录错了\",\"clientToken\":\"TOK-REV-1-R\"}");
        assertTrue(rev.isSuccess(), "撤销应成功，实际=" + rev);

        assertEquals(0, depositBalance().compareTo(BigDecimal.ZERO), "撤销后余额应回到 0");
        assertEquals("REVERSED", jdbc.queryForObject(
                "SELECT status FROM station_adjustment WHERE id=?", String.class, id), "原单应置为已撤销");
        assertNotNull(jdbc.queryForObject(
                "SELECT reversed_by FROM station_adjustment WHERE id=?", Long.class, id), "原单应记录反向单 id");
        assertEquals(0, depositBalance().compareTo(decimalOf(
                        "SELECT IFNULL(SUM(amount),0) FROM deposit_record WHERE customer_id=? AND station_id=?",
                        customer, station)),
                "对账等式1：撤销后余额仍须等于流水合计");
    }

    @Test
    @DisplayName("越权与非法输入：他站客户、DELIVERY 角色、超量核销欠桶一律拒绝")
    void authorizationAndValidation() {
        seed();

        // 他站客户
        long otherStation = createStation("S2");
        long otherCustomer = createCustomer("Bob", "openid-bob");
        createCustomerStationConfig(otherCustomer, otherStation, 1);
        Api cross = post("/api/manager/adjustments", mgrToken(),
                "{\"customerId\":" + otherCustomer + ",\"adjustType\":\"DEPOSIT_GRANT\",\"amount\":10,"
                        + "\"reason\":\"越权尝试\",\"clientToken\":\"TOK-CROSS-1\"}");
        assertFalse(cross.isSuccess(), "他站客户应被拒绝，实际=" + cross);
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_adjustment"), "越权请求不得留下单据");

        // DELIVERY 角色
        long delivery = createStaff("D1", "DELIVERY", station, 1);
        Api byDelivery = post("/api/manager/adjustments", staffToken(delivery, "DELIVERY", station),
                "{\"customerId\":" + customer + ",\"adjustType\":\"DEPOSIT_GRANT\",\"amount\":10,"
                        + "\"reason\":\"越权尝试\",\"clientToken\":\"TOK-ROLE-1\"}");
        assertFalse(byDelivery.isSuccess(), "配送员不得调整资产，实际=" + byDelivery);

        // 原因必填
        Api noReason = post("/api/manager/adjustments", mgrToken(),
                "{\"customerId\":" + customer + ",\"adjustType\":\"DEPOSIT_GRANT\",\"amount\":10,"
                        + "\"clientToken\":\"TOK-NOREASON-1\"}");
        assertFalse(noReason.isSuccess(), "缺少调整原因应被拒绝，实际=" + noReason);

        // 欠桶核销不能把占用调成负数
        Api over = createAdjustment("OVER_ADJUST", product, -3, null, null, "TOK-OVER-1");
        assertTrue(over.isSuccess(), "创建应成功（校验在执行期）");
        Api overExec = post("/api/manager/adjustments/" + over.data().path("id").asLong() + "/execute",
                mgrToken(), "{}");
        assertFalse(overExec.isSuccess(), "占用会变负的欠桶核销应被拒绝，实际=" + overExec);
        assertEquals(0, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "失败的调整不得留下 over 变化");
    }

    @Test
    @DisplayName("订正欠桶：补记后可核销，且对账 E5 仍守恒")
    void overAdjust_keepsConservation() {
        seed();
        // 先给权益，否则占用为负
        createAndExecute("BARREL_GRANT", product, 3, null, null, "TOK-BG-2");
        // 补记 2 个欠桶（客户借桶未还）
        createAndExecute("OVER_ADJUST", product, 2, null, null, "TOK-OV-2");
        assertEquals(2, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "over 应为 2（欠桶）");
        // 核销 1 个
        createAndExecute("OVER_ADJUST", product, -1, null, null, "TOK-OV-3");
        assertEquals(1, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "over 应为 1");

        Api recon = get("/api/manager/reconciliation", mgrToken());
        assertEquals(0, recon.data().path("checks").path("SE5_physicalConservation").asInt(),
                "补记/核销欠桶后 E5 仍应守恒，实际=" + recon);
    }
}
