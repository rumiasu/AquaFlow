package com.example.aquaflow.integration;

import com.example.aquaflow.config.PasswordInitializer;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.util.PasswordUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「启动补默认密码」的行为回归（2026-09-29 关雷）。
 *
 * <p>三条判据：① 空密码账号能被补上（本地 seed 流程依赖）；
 * ② <b>只填空</b>——已有密码（尤其本人改过的）绝不被启动逻辑覆盖；
 * ③ 补密码<b>不碰 role / station_id</b>（旧实现用整行覆盖的 {@code staffMapper.update()}，
 * 一个补密码的任务握着改员工归属的能力，本身就是隐患）。</p>
 *
 * <p>prod 排除的注解护栏见 {@code config/PasswordInitializerGuardTest}。</p>
 */
class PasswordInitializerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PasswordInitializer initializer;

    @Test
    @DisplayName("空密码员工被补上默认口令，role / station_id 原样不动")
    void fillsEmptyPasswordWithoutClobbering() {
        long stationId = createStation("补密码站");
        long staffId = createStaff("配送员甲", "DELIVERY", stationId, 1);
        assertNullHash(staffId);

        initializer.run();

        String hash = jdbc.queryForObject("SELECT password_hash FROM staff WHERE id=?", String.class, staffId);
        assertNotNull(hash, "空密码没有被补上");
        assertTrue(PasswordUtil.matches("123456", hash), "非 admin 账号的默认口令应为 123456");
        // 判据③：整行覆盖的旧实现一旦回归，role/station_id 会被一起重写
        assertEquals("DELIVERY", jdbc.queryForObject("SELECT role FROM staff WHERE id=?", String.class, staffId));
        assertEquals(stationId,
                jdbc.queryForObject("SELECT station_id FROM staff WHERE id=?", Long.class, staffId));
    }

    @Test
    @DisplayName("名为 admin 的员工补 admin123（本地建站脚本的入口账号）")
    void adminGetsAdmin123() {
        long staffId = createStaff("admin", "STATION_MANAGER", null, 1);

        initializer.run();

        String hash = jdbc.queryForObject("SELECT password_hash FROM staff WHERE id=?", String.class, staffId);
        assertTrue(PasswordUtil.matches("admin123", hash), "admin 账号的默认口令应为 admin123");
    }

    @Test
    @DisplayName("已有密码绝不被启动逻辑覆盖（CAS 只填空）")
    void existingPasswordNotOverwritten() {
        long staffId = createStaff("老员工", "DELIVERY", null, 1);
        jdbc.update("UPDATE staff SET password_hash=? WHERE id=?", PasswordUtil.encode("keep-me-9"), staffId);

        initializer.run();

        String hash = jdbc.queryForObject("SELECT password_hash FROM staff WHERE id=?", String.class, staffId);
        assertTrue(PasswordUtil.matches("keep-me-9", hash), "已有密码被启动逻辑改写了");
        assertFalse(PasswordUtil.matches("123456", hash));
    }

    private void assertNullHash(long staffId) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM staff WHERE id=?", String.class, staffId);
        assertTrue(hash == null || hash.isEmpty(), "前置条件不成立：造出来的员工应当没有密码");
    }
}
