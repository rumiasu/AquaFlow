package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长资产调整单 —— <b>类型覆盖补测</b>（水票 / 撤桶权益）。
 *
 * <p><b>为什么单独建这个类：</b>{@code AdjustType} 一共 7 种，原有 10 个用例只覆盖了
 * {@code BARREL_GRANT}、{@code DEPOSIT_GRANT}、{@code OVER_ADJUST} 三种；
 * <b>水票（TICKET_GRANT / TICKET_DEDUCT）与撤桶权益（BARREL_REVOKE）零覆盖</b>。
 * 而这几类恰恰都直接动「钱 / 资产」：水票是预付款、押金是钱、撤权益要按 FIFO 核销批次并退款，
 * 方向写反就是资损，且要等对账 E5 才暴露。
 * {@code AdjustType} 的类注释里记着的历史事故（"DepositType 7 在两条路径里一个加一个减"）
 * 正属于同一类风险 —— 当事类型至今没有测试。</p>
 *
 * <p>本类守四条：① 补水票会建账户并落流水；② 扣水票余额不足必须被拒（不得扣成负数）；
 * ③ 撤销的反向单类型必须<b>镜像正确</b>（GRANT ↔ DEDUCT）；④ 撤桶权益按 FIFO 核销且对账仍守恒。</p>
 */
@DisplayName("Phase B · 资产调整单 · 类型覆盖（水票 / 撤桶权益）")
class StationAdjustmentTypeCoverageIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 0, "0.00");
        // 期初库存必须同时有流水，否则对账等式 4 不平（本项目「库存变动必须留流水」AQ-029）。
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

    /**
     * 水票余额（真相源 {@code ticket_account}）；账户不存在时为 0。
     *
     * <p>必须写成「标量子查询 + IFNULL」而非 {@code SELECT IFNULL(remain_quantity,0) ... WHERE ...}：
     * 后者在<b>没有匹配行</b>时返回的是空结果集（不是一行 0），会让取值直接抛
     * {@code EmptyResultDataAccessException} —— 而"账户还没有"恰恰是本用例的前置条件。</p>
     */
    private int ticketRemain() {
        return intOf("SELECT IFNULL((SELECT remain_quantity FROM ticket_account "
                + "WHERE customer_id=? AND station_id=? AND product_id=?), 0)", customer, station, product);
    }

    /** 桶权益（批次真相源 Σ{@code remain_qty}）。 */
    private int right() {
        return intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1", customer, station, product);
    }

    private int reconDiff() {
        Api recon = get("/api/manager/reconciliation", mgrToken());
        assertTrue(recon.isSuccess(), "对账应可执行，实际=" + recon);
        return recon.data().path("totalDiff").asInt();
    }

    /* ==================== 水票 ==================== */

    @Test
    @DisplayName("补水票：客户无账户时自动建账，余额与流水同步落库，对账无差异")
    void ticketGrant_createsAccount() {
        seed();
        assertEquals(0, ticketRemain(), "前置：客户尚无该商品的水票账户");

        createAndExecute("TICKET_GRANT", product, 5, null, null, "TOK-TKT-GRANT-1");

        assertEquals(5, ticketRemain(), "补水票 5 张后余额应为 5（不存在账户时应自动建账）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record "
                        + "WHERE customer_id=? AND product_id=? AND station_id=?",
                customer, product, station), "应落一条水票流水");
        assertEquals(0, reconDiff(), "补水票后对账不应出现差异");
    }

    @Test
    @DisplayName("扣水票：余额正确减少；余额不足必须被拒，不得扣成负数")
    void ticketDeduct_decrementsAndRejectsOverdraft() {
        seed();
        createAndExecute("TICKET_GRANT", product, 5, null, null, "TOK-TKT-GRANT-2");

        createAndExecute("TICKET_DEDUCT", product, 3, null, null, "TOK-TKT-DEDUCT-1");
        assertEquals(2, ticketRemain(), "扣 3 张后余额应为 2");

        // 再扣 10 张（远超余额）——创建或执行至少要有一处拦住它，且余额必须纹丝不动。
        Api over = createAdjustment("TICKET_DEDUCT", product, 10, null, null, "TOK-TKT-DEDUCT-OVER");
        if (over.isSuccess()) {
            Api exec = post("/api/manager/adjustments/" + over.data().path("id").asLong() + "/execute",
                    mgrToken(), "{}");
            assertFalse(exec.isSuccess(), "余额不足时执行必须失败，实际=" + exec);
        }
        assertEquals(2, ticketRemain(), "超额扣减被拒后余额必须仍为 2 —— 绝不能扣成负数");
    }

    @Test
    @DisplayName("撤销补水票单：反向单类型镜像为 DEDUCT，余额精确回到调整前")
    void reverseTicketGrant_mirrorsToDeduct() {
        seed();
        long id = createAndExecute("TICKET_GRANT", product, 5, null, null, "TOK-TKT-REV-1");
        assertEquals(5, ticketRemain(), "前置：补录后余额应为 5");

        Api rev = post("/api/manager/adjustments/" + id + "/reverse", mgrToken(),
                "{\"reason\":\"录错了\",\"clientToken\":\"TOK-TKT-REV-1-R\"}");
        assertTrue(rev.isSuccess(), "撤销应成功，实际=" + rev);

        assertEquals(0, ticketRemain(), "撤销后余额必须精确回到 0（不多不少）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM station_adjustment WHERE reverses=?", id),
                "应生成一张反向单并指向原单");
        assertEquals(1, intOf("SELECT COUNT(*) FROM station_adjustment "
                        + "WHERE reverses=? AND adjust_type='TICKET_DEDUCT'", id),
                "反向单类型必须镜像为 TICKET_DEDUCT —— 写反就成了「撤销补票」反而再补一次，纯资损");
        assertEquals(1, intOf("SELECT COUNT(*) FROM station_adjustment WHERE id=? AND status='REVERSED'", id),
                "原单应置 REVERSED");
        assertEquals(0, reconDiff(), "撤销后对账不应出现差异");
    }

    /* ==================== 撤桶权益 ==================== */

    @Test
    @DisplayName("撤桶权益：按 FIFO 核销批次，权益减少且对账 E5 仍守恒")
    void barrelRevoke_consumesLotsByFifo() {
        seed();
        createAndExecute("BARREL_GRANT", product, 5, null, "30.00", "TOK-BR-GRANT-1");
        // 必须配套补押金：权益可退金额 > 押金余额会触发对账 E6「押金穿底」，
        // 那是夹具不全，不是业务错（既有用例的注释也强调了这一点）。5 × 30 = 150。
        createAndExecute("DEPOSIT_GRANT", null, null, "150.00", null, "TOK-BR-DEP-1");
        assertEquals(5, right(), "前置：补录后应有 5 个权益");
        // 中间断言：把"补权益"与"撤权益"两步分开，否则一旦不平无法判断是哪一步引入的
        assertEquals(0, reconDiff(), "只补权益（且押金配套）时应守恒 —— 若此处不平，问题在 GRANT 不在 REVOKE");

        long revokeId = createAndExecute("BARREL_REVOKE", product, 2, null, null, "TOK-BR-REVOKE-1");

        assertEquals(3, right(), "撤 2 个权益后批次余额应为 3");
        assertEquals(3, intOf("SELECT quantity FROM customer_barrel_asset "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "asset.quantity 必须等于 Σlot.remain_qty（两处不能写歪）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM barrel_record WHERE type=9 AND adjustment_id=?", revokeId),
                "应写一条 type=9（人工调整-减少）且带 adjustment_id 的桶流水");
        assertEquals(0, reconDiff(), "撤权益后对账 E5 物理桶守恒仍应为 0");
    }
}
