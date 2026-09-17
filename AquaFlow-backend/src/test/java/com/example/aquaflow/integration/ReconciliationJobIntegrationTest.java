package com.example.aquaflow.integration;

import com.example.aquaflow.service.NotificationService;
import com.example.aquaflow.service.ReconciliationService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每日 03:00 的日结对账（{@code @Scheduled}）与通知服务。
 *
 * <p><b>为什么能这么测</b>：定时任务的方法本身是普通 public 方法（{@code dailyReconcile()}），
 * 直接调用即可覆盖"跑什么、算出什么、落哪张表"，不必等时钟 —— 需要另外证明的只是"它被排进了
 * 调度"，那由 {@code @Scheduled} 注解本身保证。</p>
 *
 * <p>被锁死的三件事：</p>
 * <ol>
 *   <li>干净数据上 V1（押金/支付/桶/库存）与 V2（权益批次/占用恒等/物理守恒/穿底）全部为 0；</li>
 *   <li><b>真的能发现差异</b>：造一条"押金账户有余额、却没有对应流水"的脏数据 → V1 的
 *       {@code depositAccount} 必须 > 0（对账最怕的是恒 0 的假绿）；</li>
 *   <li>结果落 {@code reconciliation_result}，且同日重跑是<b>覆盖</b>不是堆积（唯一键 upsert）。</li>
 * </ol>
 */
@DisplayName("日结对账定时任务 · 结果落表 / 同日覆盖 / 脏数据能被检出")
class ReconciliationJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private NotificationService notificationService;

    private void assertAllZero(Map<String, Integer> counts, String what) {
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            assertEquals(0, e.getValue(), what + " 的检查项 " + e.getKey() + " 应为 0，实际=" + e.getValue());
        }
    }

    @Test
    @DisplayName("干净数据：V1 与 V2 的每个检查项都为 0")
    void cleanDataPassesBothGenerations() {
        // 只建水站，不塞任何库存/资产：造数必须自洽，否则「对账报差异」测的就是我自己造的假状态
        //（库存表有数量、却没有对应库存流水，inventory 项必然不平 —— 那是造数问题，不是产品问题）。
        createStation("S1");

        Map<String, Integer> v1 = reconciliationService.runReconcile();
        assertTrue(v1.size() >= 4, "V1 至少应含 押金/支付/桶/库存 四项，实际=" + v1.keySet());
        assertAllZero(v1, "V1");

        Map<String, Integer> v2 = reconciliationService.runReconcileV2();
        assertTrue(v2.size() >= 3, "V2 应含权益批次/占用恒等/物理守恒等项，实际=" + v2.keySet());
        assertAllZero(v2, "V2");
    }

    @Test
    @DisplayName("脏数据能被检出：押金账户有余额却无流水 → V1 的 depositAccount > 0")
    void dirtyDepositBalanceIsDetected() {
        long station = createStation("S1");
        long customer = createCustomer("Alice", "openid-alice");
        // 只给账户塞余额，不写任何押金流水 —— 等式1（balance == SUM(deposit_record.amount)）必被打破
        createDepositBalance(customer, station, "50.00");

        Map<String, Integer> v1 = reconciliationService.runReconcile();

        assertTrue(v1.get("depositAccount") != null && v1.get("depositAccount") > 0,
                "对账必须发现「余额与流水对不上」，实际=" + v1);
    }

    @Test
    @DisplayName("结果落 reconciliation_result，且同日重跑覆盖而不是堆积")
    void dailyReconcilePersistsAndOverwritesSameDay() {
        createStation("S1");

        reconciliationService.dailyReconcile();
        int rowsAfterFirst = intOf("SELECT COUNT(*) FROM reconciliation_result WHERE run_date = CURDATE()");
        assertTrue(rowsAfterFirst > 0, "日结对账必须把结果落表（此前只写日志，事后无法追查）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM reconciliation_result "
                        + "WHERE run_date = CURDATE() AND check_key = 'depositAccount'"),
                "每个检查项当天只应有一行");

        reconciliationService.dailyReconcile();
        assertEquals(rowsAfterFirst, intOf("SELECT COUNT(*) FROM reconciliation_result WHERE run_date = CURDATE()"),
                "同日重跑应覆盖（ON DUPLICATE KEY UPDATE），不得堆积出重复行");

        int levels = intOf("SELECT COUNT(*) FROM reconciliation_result "
                + "WHERE run_date = CURDATE() AND level IN ('ERROR','WARN')");
        assertEquals(rowsAfterFirst, levels, "每行都应带 ERROR/WARN 级别（供运维按级别筛选）");
    }

    @Test
    @DisplayName("通知服务当前是「只打日志」的桩：任何一类推送都不得抛异常、也不得动业务数据")
    void notificationStubIsHarmless() {
        long station = createStation("S1");
        long customer = createCustomer("Alice", "openid-alice");
        createStaff("M1", "STATION_MANAGER", station, 1);

        // 当前实现只 log.info（微信订阅消息是 TODO，测试号也发不出去）。
        // 本用例的价值是钉住"桩不许变成会改数据的副作用"，以及"缺它的调用点不会炸"。
        notificationService.pushExceptionCreated(station, 1L, "1", "RETURN_SHORT", 2);
        notificationService.pushExceptionApproved(station, 1L, "APPROVE");
        notificationService.pushCompensationExecuted(customer, 1L, "APPROVE", "退现金 30");
        notificationService.pushStationShortageNegotiation(customer, 1L, "明天补送");
        notificationService.pushStaffRecordedException(station, 1L, "配送员A", "RETURN_SHORT");
        notificationService.pushBatchSummary(station, "今日异常 1 条");

        assertEquals(0, intOf("SELECT COUNT(*) FROM deposit_record"), "通知服务不得写任何资金流水");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_record"), "通知服务不得写任何水票流水");
    }
}
