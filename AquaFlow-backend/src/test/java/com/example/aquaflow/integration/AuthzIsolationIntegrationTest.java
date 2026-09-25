package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 越权与站点隔离回归。
 *
 * <p>护栏目标：客户只能碰自己的订单；员工只能碰本站（履约站）的订单。
 * 越权调用必须<b>既被拒绝、又给出「无权」这类可读提示</b>——
 * 只返回一句「系统错误」，等于把权限问题和代码 bug 混为一谈，线上根本查不出来。</p>
 */
@DisplayName("Phase B · 越权隔离（客户 / 员工 / 无令牌）")
class AuthzIsolationIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("无令牌访问受保护接口 → 401")
    void noToken_isRejectedWith401() {
        Api res = get("/api/orders/1", null);

        assertEquals(401, res.status(), "无令牌应被 AuthInterceptor 拒绝，实际=" + res);
    }

    @Test
    @DisplayName("客户 B 不能读取客户 A 的订单")
    void customerCannotReadAnotherCustomersOrder() {
        long s1 = createStation("S1");
        long p = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        long alice = createCustomer("Alice", "openid-alice");
        long bob = createCustomer("Bob", "openid-bob");
        long addr = createAddress(alice, "某小区1号");
        long order = createOrder(alice, addr, s1, p, 1, 1);

        Api res = get("/api/orders/" + order, customerToken(bob));

        assertFalse(res.isSuccess(), "Bob 不应读到 Alice 的订单，实际=" + res);
        assertTrue(res.message().contains("无权"), "应返回越权提示，实际=" + res.message());
    }

    @Test
    @DisplayName("客户不能调用员工专属的订单操作接口")
    void customerCannotCallStaffOnlyEndpoint() {
        long s1 = createStation("S1");
        long p = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        long alice = createCustomer("Alice", "openid-alice");
        long addr = createAddress(alice, "某小区1号");
        long order = createOrder(alice, addr, s1, p, 1, 1);

        // 原用已作为 P0-4 删除的 PUT /api/orders/{id}/status。必须改走仍存在的员工专属入口：
        // 否则 404 同样满足 assertFalse，用例会退化成永远为真的空断言（2026-09-14 发现并修正）。
        Api res = post("/api/delivery/orders/" + order + "/accept", customerToken(alice), null);

        assertFalse(res.isSuccess(), "客户不应能调用员工专属接口，实际=" + res);
    }

    @Test
    @DisplayName("S1 员工不能读取 S2 的订单")
    void staffCannotReadAnotherStationsOrder() {
        long s1 = createStation("S1");
        long s2 = createStation("S2");
        long p = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        long m1 = createStaff("M1", "STATION_MANAGER", s1, 1);
        long alice = createCustomer("Alice", "openid-alice");
        long addr = createAddress(alice, "某小区1号");
        long order = createOrder(alice, addr, s2, p, 1, 1);

        Api res = get("/api/orders/" + order, staffToken(m1, "STATION_MANAGER", s1));

        assertFalse(res.isSuccess(), "S1 员工不应读到 S2 的订单，实际=" + res);
        assertTrue(res.message().contains("无权"), "应返回越权提示，实际=" + res.message());
    }

    @Test
    @DisplayName("客户可正常读取自己的订单")
    void customerCanReadOwnOrder() {
        long s1 = createStation("S1");
        long p = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        long alice = createCustomer("Alice", "openid-alice");
        long addr = createAddress(alice, "某小区1号");
        long order = createOrder(alice, addr, s1, p, 1, 1);

        Api res = get("/api/orders/" + order, customerToken(alice));

        assertTrue(res.isSuccess(), "客户应能读取自己的订单，实际=" + res);
        assertEquals(order, res.data().path("id").asLong());
    }

    @Test
    @DisplayName("S1 员工不能操作 S2 的订单（跨站写）")
    void staffCannotOperateAnotherStationsOrder() {
        long s1 = createStation("S1");
        long s2 = createStation("S2");
        long p = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        long m1 = createStaff("M1", "STATION_MANAGER", s1, 1);
        long alice = createCustomer("Alice", "openid-alice");
        long addr = createAddress(alice, "某小区1号");
        long order = createOrder(alice, addr, s2, p, 1, 1);

        // 同上：改走真实接单入口，才会真正打到 acceptOrder 里的「只能接本站履约的订单」校验。
        Api res = post("/api/delivery/orders/" + order + "/accept",
                staffToken(m1, "STATION_MANAGER", s1), null);

        assertFalse(res.isSuccess(), "S1 员工不应能操作 S2 订单，实际=" + res);
        Integer status = jdbc.queryForObject("SELECT status FROM orders WHERE id=?", Integer.class, order);
        assertEquals(1, status, "被越权拒绝后订单状态必须保持原状");
    }
}
