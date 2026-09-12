package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单状态机逐边回归。
 *
 * <p>合法边：1→2、1→5、2→3、2→4、2→5、3→4、3→5；终态 4/5 不可再流转。
 * 非法跳转必须被拒绝<b>且不改库</b> —— 只返回失败、库里却已经变了，比不做校验更危险。</p>
 */
@DisplayName("Phase B · 订单状态机")
class OrderStateMachineIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long mgr;
    private long alice;
    private long addr;

    private void seedBase() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
        alice = createCustomer("Alice", "openid-alice");
        addr = createAddress(alice, "某小区1号");
    }

    private Api putStatus(long orderId, int status) {
        return put("/api/orders/" + orderId + "/status?status=" + status,
                staffToken(mgr, "STATION_MANAGER", station), null);
    }

    private int dbStatus(long orderId) {
        Integer v = jdbc.queryForObject("SELECT status FROM orders WHERE id=?", Integer.class, orderId);
        return v == null ? -1 : v;
    }

    @Test
    @DisplayName("合法流转 1 待配送 → 2 配送中 成功且落库")
    void legalTransition_succeeds() {
        seedBase();
        long order = createOrder(alice, addr, station, product, 1, 1);

        Api res = putStatus(order, 2);

        assertTrue(res.isSuccess(), "1→2 应成功，实际=" + res);
        assertEquals(2, dbStatus(order), "状态应已更新为配送中");
    }

    @Test
    @DisplayName("非法跳转 1 待配送 → 4 已完成 被拒且不改库")
    void illegalSkip_isRejected() {
        seedBase();
        long order = createOrder(alice, addr, station, product, 1, 1);

        Api res = putStatus(order, 4);

        assertFalse(res.isSuccess(), "1→4 跳级应被拒，实际=" + res);
        assertEquals(1, dbStatus(order), "被拒后状态必须保持原状");
    }

    @Test
    @DisplayName("终态 4 已完成 不可再流转")
    void terminalState_cannotTransition() {
        seedBase();
        long order = createOrder(alice, addr, station, product, 4, 2);

        Api res = putStatus(order, 2);

        assertFalse(res.isSuccess(), "已完成订单不应可再流转，实际=" + res);
        assertEquals(4, dbStatus(order), "被拒后状态必须保持原状");
    }
}
