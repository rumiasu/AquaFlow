package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 员工绑定审批的**锁序与失败形态**（返工契约 §5 的 V11，返工项 R6）。
 *
 * <p>R6 的形状：两个站的站长同时同意同一个配送员时，「改申请 → 改归属 → 作废其它申请」这三步
 * 横跨两张表，而两个事务的取锁顺序相反即可构成循环等待；MySQL 会挑一个回滚 ——
 * 用户看到 500 + 一条 SYSTEM 告警，而**正常业务竞争必须是可读的 code=1**。
 * 现有用例只断言"成功数=1 + 最终归属"，失败方是不是 500、有没有惊动系统管理员，它都不看。</p>
 *
 * <p>本条用例把这两件事钉住：① 用裸 JDBC 事务**固定交错**（证明审批确实先锁员工行、后动申请行）；
 * ② 断言失败方是 {@code code=1}，且 {@code alert_log} 里没有新增的 SYSTEM 告警。</p>
 */
@DisplayName("员工绑定审批 · 锁序与失败形态（V11）")
class BindingApprovalConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("V11：同员工两站同时审批 —— 恰好一个成功，另一个 code=1，且不惊动系统管理员")
    void twoStationsApprovingSameStaff_fixedInterleaving() throws Exception {
        long stationA = createStation("锁序站A");
        long stationB = createStation("锁序站B");
        long managerA = createStaff("锁序站长A", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("锁序站长B", "STATION_MANAGER", stationB, 1);
        long delivery = createStaff("锁序配送员", "DELIVERY", null, 1);
        long appA = insert("INSERT INTO staff_station_application(staff_id, station_id, type, status) VALUES (?,?,1,1)",
                delivery, stationA);
        long appB = insert("INSERT INTO staff_station_application(staff_id, station_id, type, status) VALUES (?,?,1,1)",
                delivery, stationB);
        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        String mgrB = staffToken(managerB, "STATION_MANAGER", stationB);

        int systemAlertsBefore = intOf("SELECT COUNT(*) FROM alert_log WHERE alert_type='SYSTEM'");

        // 固定交错：第三方事务先占住**员工行**（不是申请行）——
        // 两个审批请求都必须在这里排队，这本身就证明锁序是「员工聚合 → 申请行」。
        Connection blocker = jdbc.getDataSource().getConnection();
        blocker.setAutoCommit(false);
        try (PreparedStatement ps = blocker.prepareStatement("SELECT id FROM staff WHERE id=? FOR UPDATE")) {
            ps.setLong(1, delivery);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "屏障前提：员工行必须存在");
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Api>> futures = new java.util.ArrayList<>();
        try {
            futures.add(pool.submit(approve(start, () -> post("/api/manager/bind/approve", mgrA,
                    "{\"applicationId\":" + appA + "}"))));
            futures.add(pool.submit(approve(start, () -> post("/api/manager/bind/approve", mgrB,
                    "{\"applicationId\":" + appB + "}"))));
            start.countDown();
            awaitWaitingLocksOnStaff(2);   // 两个请求都卡在 staff 行上才算"固定交错"成立
        } finally {
            blocker.commit();
            blocker.close();
        }

        List<Api> results = new java.util.ArrayList<>();
        for (Future<Api> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long okCount = results.stream().filter(Api::isSuccess).count();
        assertEquals(1, okCount, "两个站同时同意同一个配送员，只允许一个成功，实际=" + results);
        for (Api r : results) {
            if (!r.isSuccess()) {
                assertEquals(1, r.code(), "失败方必须是**可读业务拒绝**（code=1），不是 500 / 死锁回滚，实际=" + r);
            }
        }

        long finalStation = longOf("SELECT station_id FROM staff WHERE id=?", delivery);
        assertTrue(finalStation == stationA || finalStation == stationB, "归属必须落在其中一个站上，实际=" + finalStation);
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff_station_application WHERE staff_id=? AND status=2", delivery),
                "只允许一条申请处于已同意(2)");
        assertEquals(finalStation, longOf("SELECT station_id FROM staff_station_application "
                + "WHERE staff_id=? AND status=2", delivery), "已同意那条必须就是最终归属的那个站");
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_station_application WHERE staff_id=? AND status=1", delivery),
                "归属已定，其余待审批申请必须一并作废");

        assertEquals(systemAlertsBefore, intOf("SELECT COUNT(*) FROM alert_log WHERE alert_type='SYSTEM'"),
                "正常审批竞争不得产生 SYSTEM 告警（那是平台管理员才会收到的系统故障）");
    }

    private Callable<Api> approve(CountDownLatch start, Callable<Api> task) {
        return () -> {
            start.await();
            return task.call();
        };
    }

    /**
     * 等到"在 staff 表上排队的锁"达到期望条数。
     * <p>用 {@code performance_schema.data_locks} 而不是 sleep：既能证明等待真的发生在员工行上
     * （锁序判据），又不会因为机器快慢而变成"碰运气"。</p>
     */
    private void awaitWaitingLocksOnStaff(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks "
                    + "WHERE OBJECT_NAME='staff' AND LOCK_STATUS='WAITING'", Integer.class);
            last = n == null ? 0 : n;
            if (last >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("屏障失败：等待 staff 行锁的事务数为 " + last + "，未达到 " + expected
                + "（说明审批没有先锁员工行 —— 这正是 R6 的缺陷形状）");
    }
}
