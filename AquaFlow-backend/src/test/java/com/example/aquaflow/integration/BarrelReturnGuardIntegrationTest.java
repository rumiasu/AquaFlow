package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退桶（退押金）与欠桶的互斥规则：<b>欠着空桶就不许退桶，先还清</b>。
 *
 * <p>业务理由：欠桶 = 客户手上端着比他的权益更多的桶（占用 = 权益 + over）。这时再把押金退给他，
 * 水站就失去了唯一的担保物 —— 桶在别人手里、押金也退了。所以规则是"先还桶、再退钱"。</p>
 *
 * <p>规则落在两处（都是硬拦，都是按商品各算各的，A 水的多还不能抵 B 水的欠）：
 * <ul>
 *   <li><b>申请时</b>：{@code BarrelServiceImpl.previewReturn} 返回 blocked → 控制器直接拒，
 *       不建 barrel_record（否则客户以为申请成功、白等一场）；</li>
 *   <li><b>退押金时</b>：{@code BarrelServiceImpl.doRefund} 再查一次 over ——
 *       因为申请之后、审批之前客户完全可能又欠上桶（短回空桶 / 站长补记欠桶）。</li>
 * </ul>
 *
 * <p>为什么要有这个类：这两处硬拦此前<b>一条用例都没有</b>。而且它属于"看起来实现了、
 * 实际上可能被绕过"的那类规则 —— 例如 {@code StationAdjustmentServiceImpl} 撤桶权益走的是
 * {@code consumeLots} 直连，<b>不经过</b>这道校验（那是站长人工订正通道，见类尾说明）。</p>
 */
@DisplayName("退桶与欠桶互斥 · 欠桶时先还清才能退押金")
class BarrelReturnGuardIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long mgr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /** 客户有 1 个桶权益（押金 30 已入账），可退。 */
    private void giveOneRefundableBarrel() {
        createBarrelLot("LOT-G-1", customer, station, product, "30.00", 1, 1);
        createBarrelAsset(customer, station, product, 1, "30.00");
        createDepositBalance(customer, station, "30.00");
    }

    private Api applyReturn() {
        return post("/api/barrels/return", customerToken(customer),
                "{\"stationId\":" + station + ",\"productId\":" + product + ",\"quantity\":1}");
    }

    private Api approve(long recordId, int status) {
        return put("/api/barrels/records/" + recordId + "/status", mgrToken(), "{\"status\":" + status + "}");
    }

    private int right() {
        return intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1", customer, station, product);
    }

    private BigDecimal depositBalance() {
        return decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                + "WHERE customer_id=? AND station_id=?", customer, station);
    }

    @Test
    @DisplayName("欠桶时申请退桶：直接拒绝，且不留下任何申请记录")
    void cannotApplyReturnWhileOwingBarrels() {
        seed();
        giveOneRefundableBarrel();
        createBarrelOver(customer, station, product, 2);   // 欠 2 个空桶

        Api res = applyReturn();

        assertFalse(res.isSuccess(), "欠桶时申请退桶必须被拒，实际=" + res);
        assertTrue(res.message() != null && res.message().contains("欠桶"),
                "拒绝原因必须点明欠桶（否则客户不知道为什么要先还桶），实际=" + res.message());
        assertEquals(0, intOf("SELECT COUNT(*) FROM barrel_record WHERE customer_id=? AND type=2", customer),
                "被拒后不得留下退桶申请记录（否则客户以为提交成功了）");
        assertEquals(1, right(), "被拒后权益不得变");
        assertEquals(0, depositBalance().compareTo(new BigDecimal("30.00")), "被拒后押金不得变");
    }

    @Test
    @DisplayName("申请之后才欠上桶：确认收到可以，但退押金必须被拒（钱不能给出担保物之外）")
    void refundIsBlockedWhenDebtAppearsAfterApproval() {
        seed();
        giveOneRefundableBarrel();

        Api apply = applyReturn();
        assertTrue(apply.isSuccess(), "无欠桶时申请应成功，实际=" + apply);
        long recordId = apply.data().path("recordId").asLong();

        // 申请之后、审批之前欠上桶：配送短回空桶 / 站长补记欠桶都会造成这种时序
        createBarrelOver(customer, station, product, 1);

        assertTrue(approve(recordId, 2).isSuccess(), "确认收到空桶只是登记、不动账，应仍可进行");

        Api refund = approve(recordId, 3);
        assertFalse(refund.isSuccess(), "欠桶未清时退押金必须被拒，实际=" + refund);
        assertTrue(refund.message() != null && refund.message().contains("欠桶"),
                "拒绝原因必须点明欠桶，实际=" + refund.message());

        assertEquals(1, right(), "退押金被拒 → 权益不得被核销");
        assertEquals(0, depositBalance().compareTo(new BigDecimal("30.00")), "退押金被拒 → 押金不得减少");
        assertEquals(0, intOf("SELECT COUNT(*) FROM barrel_record_lot WHERE record_id=?", recordId),
                "退押金被拒 → 不得留下批次核销明细");
        assertEquals(2, intOf("SELECT status FROM barrel_record WHERE id=?", recordId),
                "申请应停在「已确认收到」(2)，等欠桶还清后再退");
    }

    @Test
    @DisplayName("站长审批列表必须带欠桶标记（前端据此标红「需先归还欠桶才能退押金」）")
    void allRecordsAnnotatesOwedBarrelsForManager() {
        seed();
        giveOneRefundableBarrel();

        Api apply = applyReturn();
        long recordId = apply.data().path("recordId").asLong();

        // 无欠桶时标记为 0
        Api clean = get("/api/barrels/all-records", mgrToken());
        assertTrue(clean.isSuccess(), "站长查退桶记录应成功，实际=" + clean);
        assertEquals(0, rowOf(clean, recordId).path("owedBuckets").asInt(),
                "未欠桶时 owedBuckets 应为 0（前端按 >0 才标红）");

        // 申请之后欠上 2 个：站长列表必须立刻能看到，否则他点「退押金」才吃一个错误
        createBarrelOver(customer, station, product, 2);
        Api owing = get("/api/barrels/all-records", mgrToken());
        assertEquals(2, rowOf(owing, recordId).path("owedBuckets").asInt(),
                "欠桶数必须按商品下发到该条退桶申请上（字段名 owedBuckets，前端 barrel-return 页读它）");
    }

    @Test
    @DisplayName("还清欠桶后可以正常走完退桶：权益核销、押金退回，over 归零")
    void returnSucceedsAfterDebtIsCleared() {
        seed();
        giveOneRefundableBarrel();
        createBarrelOver(customer, station, product, 2);

        // 客户把欠的 2 个空桶还回来 → over 归零（这是"还清"的唯一动作）
        Api empty = post("/api/barrels/return-empty", mgrToken(),
                "{\"customerId\":" + customer + ",\"clientToken\":\"guard-clear-1\","
                        + "\"items\":[{\"productId\":" + product + ",\"qty\":2}]}");
        assertTrue(empty.isSuccess(), "还桶应成功，实际=" + empty);
        assertEquals(0, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "还清后 over 应归零");

        Api apply = applyReturn();
        assertTrue(apply.isSuccess(), "欠桶已清 → 申请应成功，实际=" + apply);
        long recordId = apply.data().path("recordId").asLong();
        assertTrue(approve(recordId, 2).isSuccess(), "确认收到应成功");
        assertTrue(approve(recordId, 3).isSuccess(), "欠桶已清 → 退押金应成功");

        assertEquals(0, right(), "权益应被核销为 0");
        assertEquals(0, depositBalance().compareTo(BigDecimal.ZERO), "押金应退回为 0");
        assertEquals(3, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "申请应置已退押金(3)");
    }

    /** 在站长退桶记录列表里按 id 找出那一行；找不到直接失败，避免断言被静默跳过。 */
    private com.fasterxml.jackson.databind.JsonNode rowOf(Api res, long recordId) {
        for (com.fasterxml.jackson.databind.JsonNode row : res.data()) {
            if (row.path("id").asLong() == recordId) return row;
        }
        throw new AssertionError("退桶记录列表里没有 id=" + recordId + " 的行：" + res.data());
    }
}
