package com.example.aquaflow.integration;

import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP employee guards and real MySQL CAS; execution belongs to the serial database validation task. */
class StaffManagementGuardIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private StaffMapper staffMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void creationRejectsManagerAliasesUnknownRolesAndInvalidStatuses() {
        long station = createStation("员工创建站");
        long manager = createStaff("站长", "manager", station, 1);
        String token = staffToken(manager, "manager", station);
        for (String role : new String[]{"STATION_MANAGER", "manager", "ADMIN", "FACTORY_ADMIN", "unknown", "Delivery"}) {
            assertEquals(1, post("/api/staff", token,
                    "{\"name\":\"非法员工\",\"role\":\"" + role + "\"}").code(), role);
        }
        assertEquals(1, post("/api/staff", token, "{\"name\":\"缺角色\"}").code());
        assertEquals(1, post("/api/staff", token, "{\"name\":\"空角色\",\"role\":null}").code());
        for (int status : new int[]{-1, 0, 3, 99}) {
            assertEquals(1, post("/api/staff", token,
                    "{\"name\":\"非法状态\",\"role\":\"DELIVERY\",\"status\":" + status + "}").code());
        }
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff"), "所有被拒请求不得建员工");

        long otherStation = createStation("伪造归属站");
        for (String role : new String[]{"DELIVERY", "delivery"}) {
            // MySQL 名称列不区分大小写；两种角色输入的夹具名称须真正不同，避免查到两行。
            String name = "配送员-" + ("DELIVERY".equals(role) ? "正式角色" : "兼容角色");
            assertEquals(0, post("/api/staff", token,
                    "{\"name\":\"" + name + "\",\"phone\":\"13800000001\",\"role\":\"" + role
                            + "\",\"stationId\":" + otherStation + "}").code());
            long staff = longOf("SELECT id FROM staff WHERE name=?", name);
            assertEquals("DELIVERY", jdbc.queryForObject("SELECT role FROM staff WHERE id=?", String.class, staff));
            assertEquals(station, longOf("SELECT station_id FROM staff WHERE id=?", staff));
            assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", staff));
        }
        assertEquals(3, get("/api/staff", token).data().size(), "前端省略 status 时新员工也应在列表中");
    }

    @Test
    void selfSoleManagerAndPeerManagersCannotBeDisabledOrDeleted() {
        long station = createStation("管理入口保护站");
        long manager = createStaff("唯一站长", "STATION_MANAGER", station, 1);
        String token = staffToken(manager, "STATION_MANAGER", station);
        assertEquals(1, put("/api/staff/" + manager, token, "{\"status\":2}").code());
        assertEquals(1, delete("/api/staff/" + manager, token).code());
        assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", manager));
        assertEquals(0, get("/api/staff", token).code(), "被拒后管理入口仍可访问");

        long peer = createStaff("别名站长", "manager", station, 1);
        String peerToken = staffToken(peer, "manager", station);
        assertEquals(1, put("/api/staff/" + peer, token, "{\"status\":2}").code());
        assertEquals(1, delete("/api/staff/" + peer, token).code());
        assertEquals(1, put("/api/staff/" + manager, peerToken, "{\"status\":2}").code());
        assertEquals(1, delete("/api/staff/" + manager, peerToken).code());
        assertEquals(1, put("/api/staff/" + peer, peerToken, "{\"status\":2}").code());
        assertEquals(1, delete("/api/staff/" + peer, peerToken).code());
        assertEquals(2, intOf("SELECT COUNT(*) FROM staff WHERE station_id=? AND status=1", station));

        assertEquals(0, put("/api/staff/" + peer, peerToken,
                "{\"name\":\"改名站长\",\"phone\":\"13800000002\",\"status\":1,"
                        + "\"role\":\"DELIVERY\",\"stationId\":99999,\"passwordHash\":\"forged\"}").code());
        assertEquals("manager", jdbc.queryForObject("SELECT role FROM staff WHERE id=?", String.class, peer));
        assertEquals(station, longOf("SELECT station_id FROM staff WHERE id=?", peer));
        assertNull(jdbc.queryForObject("SELECT password_hash FROM staff WHERE id=?", String.class, peer));
        assertEquals("改名站长", jdbc.queryForObject("SELECT name FROM staff WHERE id=?", String.class, peer));
    }

    @Test
    void deliveryDepartureReactivationAndTenantBoundariesRemainValid() {
        long station = createStation("员工状态站");
        long otherStation = createStation("他站");
        long manager = createStaff("站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("配送员", "DELIVERY", station, 1);
        long foreign = createStaff("他站配送员", "DELIVERY", otherStation, 1);
        long unbound = createStaff("未绑定配送员", "DELIVERY", null, 1);
        String token = staffToken(manager, "STATION_MANAGER", station);
        for (int status : new int[]{-1, 0, 3, 99}) {
            assertEquals(1, put("/api/staff/" + delivery, token, "{\"status\":" + status + "}").code());
        }
        assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", delivery));
        for (long target : new long[]{foreign, unbound, delivery + 99999}) {
            assertEquals(1, put("/api/staff/" + target, token, "{\"name\":\"越权改名\",\"status\":2}").code());
            assertEquals(1, delete("/api/staff/" + target, token).code());
        }
        assertEquals(4, intOf("SELECT COUNT(*) FROM staff"));
        assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", foreign));
        assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", unbound));

        assertEquals(0, put("/api/staff/" + delivery, token, "{\"status\":2}").code());
        assertEquals(2, intOf("SELECT status FROM staff WHERE id=?", delivery));
        assertEquals(1, get("/api/staff", token).data().size());
        assertEquals(0, put("/api/staff/" + delivery, token, "{\"name\":\"离职配送员\"}").code());
        assertEquals(2, intOf("SELECT status FROM staff WHERE id=?", delivery), "省略状态不得意外复职");
        assertEquals(0, put("/api/staff/" + delivery, token, "{\"status\":1}").code());
        assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", delivery));
        assertEquals(0, put("/api/staff/" + delivery, token, "{}").code(), "空白重试不得因为零变化而假报冲突");
    }

    @Test
    void employeeWritesRejectDeliveryCustomerAndUnauthenticatedCallers() {
        long station = createStation("员工权限站");
        long manager = createStaff("站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("配送员", "DELIVERY", station, 1);
        long customer = createCustomer("客户", "employee-guard-customer");
        for (String token : new String[]{staffToken(delivery, "DELIVERY", station), customerToken(customer)}) {
            assertEquals(1, post("/api/staff", token, "{\"name\":\"越权创建\",\"role\":\"DELIVERY\"}").code());
            assertEquals(1, put("/api/staff/" + manager, token, "{\"status\":2}").code());
            assertEquals(1, delete("/api/staff/" + delivery, token).code());
        }
        assertEquals(401, post("/api/staff", null, "{\"name\":\"未登录\",\"role\":\"DELIVERY\"}").status());
        assertEquals(401, put("/api/staff/" + delivery, null, "{\"status\":2}").status());
        assertEquals(401, delete("/api/staff/" + delivery, null).status());
        assertEquals(2, intOf("SELECT COUNT(*) FROM staff"));
        assertEquals(2, intOf("SELECT COUNT(*) FROM staff WHERE status=1"));
    }

    @Test
    void departureAndDeletionPreserveExistingEarningsAndPayroll() {
        long station = createStation("员工历史站");
        long manager = createStaff("站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("历史配送员", "DELIVERY", station, 1);
        String token = staffToken(manager, "STATION_MANAGER", station);
        long payroll = insert("INSERT INTO staff_payroll(payroll_no,station_id,staff_id,period_start,period_end,total_amount,status) "
                + "VALUES ('PR-EMPLOYEE-GUARD',?,?,'2026-09-01','2026-09-30',10.00,1)", station, delivery);
        long earning = insert("INSERT INTO staff_earning(station_id,staff_id,kind,amount,payroll_id) "
                + "VALUES (?,?,'ADJUST',10.00,?)", station, delivery, payroll);

        assertEquals(0, put("/api/staff/" + delivery, token, "{\"status\":2}").code());
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff_earning WHERE id=? AND staff_id=? AND amount=10.00 AND payroll_id=?",
                earning, delivery, payroll));
        assertEquals(0, delete("/api/staff/" + delivery, token).code());
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff WHERE id=?", delivery));
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff_earning WHERE id=? AND staff_id=? AND amount=10.00 AND payroll_id=?",
                earning, delivery, payroll));
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff_payroll WHERE id=? AND staff_id=? AND station_id=? AND total_amount=10.00",
                payroll, delivery, station));
        Api history = get("/api/manager/payroll", token);
        assertEquals(0, history.code(), "原结算站仍可查询已删除员工的工资历史");
        assertEquals(1, history.data().size());
        assertEquals(payroll, history.data().get(0).get("id").asLong());
        assertEquals(0, get("/api/manager/earnings?staffId=" + delivery + "&payrollId=" + payroll, token).code());
        long otherStation = createStation("无历史关系站");
        long otherManager = createStaff("他站站长", "STATION_MANAGER", otherStation, 1);
        String otherToken = staffToken(otherManager, "STATION_MANAGER", otherStation);
        assertEquals(0, get("/api/manager/payroll", otherToken).data().size());
        assertEquals(1, get("/api/manager/earnings?staffId=" + delivery + "&payrollId=" + payroll,
                otherToken).code(), "员工删除不得扩大历史工资的查看权限");

        // 删除员工不删除债权，也不能让真实历史收益无法生成、确认和登记结清。
        insert("INSERT INTO staff_earning(station_id,staff_id,kind,amount,create_time) "
                + "VALUES (?,?,'ADJUST',5.00,'2026-10-01 12:00:00')", station, delivery);
        String generate = "{\"staffId\":" + delivery
                + ",\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-01\"}";
        assertEquals(1, post("/api/manager/payroll", otherToken, generate).code());
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff_payroll"), "无关系站不得生成工资单");
        Api generated = post("/api/manager/payroll", token, generate);
        assertEquals(0, generated.code(), generated.toString());
        long remainingPayroll = generated.data().path("payrollId").asLong();
        assertEquals(0, decimalOf("SELECT total_amount FROM staff_payroll WHERE id=?", remainingPayroll)
                .compareTo(new BigDecimal("5.00")));
        for (long id : new long[]{payroll, remainingPayroll}) {
            assertEquals(1, post("/api/manager/payroll/" + id + "/confirm", otherToken, "{}").code());
            assertEquals(0, post("/api/manager/payroll/" + id + "/confirm", token, "{}").code());
            assertEquals(1, post("/api/manager/payroll/" + id + "/pay", otherToken, "{}").code());
            assertEquals(0, post("/api/manager/payroll/" + id + "/pay", token, "{}").code());
            assertEquals(3, intOf("SELECT status FROM staff_payroll WHERE id=?", id));
            assertEquals(1, intOf("SELECT COUNT(*) FROM staff_payroll WHERE id=? AND paid_time IS NOT NULL AND operator_id=?",
                    id, manager));
        }
    }

    @Test
    void transferCommittedWhileUpdateWaitsCannotBeOverwrittenByTheOldStation() throws Exception {
        transferDuringManagementCommand(false);
    }

    @Test
    void transferCommittedWhileDeleteWaitsCannotBeDeletedByTheOldStation() throws Exception {
        transferDuringManagementCommand(true);
    }

    private void transferDuringManagementCommand(boolean deleteEmployee) throws Exception {
        long oldStation = createStation("员工并发旧站");
        long newStation = createStation("员工并发新站");
        long manager = createStaff("旧站站长", "STATION_MANAGER", oldStation, 1);
        long delivery = createStaff("并发换站配送员", "DELIVERY", oldStation, 1);
        String token = staffToken(manager, "STATION_MANAGER", oldStation);
        var pool = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        try {
            Future<Api> command = new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM staff WHERE id=? FOR UPDATE", Long.class, delivery);
                Future<Api> waiting = pool.submit(() -> {
                    started.countDown();
                    return deleteEmployee ? delete("/api/staff/" + delivery, token)
                            : put("/api/staff/" + delivery, token, "{\"name\":\"旧站越权改名\",\"status\":2}");
                });
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                assertThrows(TimeoutException.class, () -> waiting.get(250, TimeUnit.MILLISECONDS),
                        "持有员工行锁时管理写入须等待，不能按旧快照提前成功");
                assertEquals(1, jdbc.update("UPDATE staff SET station_id=? WHERE id=?", newStation, delivery));
                return waiting;
            });
            assertNotNull(command);
            assertEquals(1, command.get(30, TimeUnit.SECONDS).code(), "拿锁后必须按换站后的归属拒绝旧站命令");
            assertEquals(newStation, longOf("SELECT station_id FROM staff WHERE id=?", delivery));
            assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", delivery));
            assertEquals("并发换站配送员", jdbc.queryForObject("SELECT name FROM staff WHERE id=?", String.class, delivery));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "测试请求必须结束后才能恢复数据库");
        }
    }

    @Test
    void mapperCasChecksStationRoleAndOriginalStatusInRealMysql() {
        long station = createStation("员工CAS站");
        long otherStation = createStation("员工CAS他站");
        long delivery = createStaff("CAS配送员", "DELIVERY", station, 1);
        assertEquals(0, staffMapper.updateBaseInfoIf(delivery, otherStation, "DELIVERY", 1, "错站改名", null, 2));
        assertEquals(0, staffMapper.updateBaseInfoIf(delivery, station, "manager", 1, "错角色改名", null, 2));
        assertEquals(0, staffMapper.updateBaseInfoIf(delivery, station, "DELIVERY", 2, "错状态改名", null, 2));
        assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", delivery));
        assertEquals(1, staffMapper.updateBaseInfoIf(delivery, station, "DELIVERY", 1, "CAS离职配送员", null, 2));
        assertEquals(2, intOf("SELECT status FROM staff WHERE id=?", delivery));
        assertEquals(0, staffMapper.deleteIf(delivery, otherStation, "DELIVERY", 2));
        assertEquals(0, staffMapper.deleteIf(delivery, station, "manager", 2));
        assertEquals(0, staffMapper.deleteIf(delivery, station, "DELIVERY", 1));
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff WHERE id=?", delivery));
        assertEquals(1, staffMapper.deleteIf(delivery, station, "DELIVERY", 2));

        long legacy = createStaff("历史空状态配送员", "DELIVERY", station, 1);
        jdbc.update("UPDATE staff SET status=NULL WHERE id=?", legacy);
        assertEquals(1, staffMapper.updateBaseInfoIf(legacy, station, "DELIVERY", null, "已修复配送员", null, 1));
        assertEquals(1, intOf("SELECT status FROM staff WHERE id=?", legacy));
    }
}
