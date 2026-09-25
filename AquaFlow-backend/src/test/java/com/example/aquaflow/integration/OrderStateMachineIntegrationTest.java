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
 *
 * <p><b>2026-09-14 改造</b>：本类原先用 {@code PUT /api/orders/{id}/status} 直接改状态来测状态机，
 * 该端点已作为 P0-4 删除（它只改 status 字段，不执行退款/退票/退押金/回补库存等副作用，
 * 是绕过全部资金正确性工作的一道后门）。现改为走<b>具名业务入口</b>（accept / complete），
 * 验证「端点各自的前置状态门槛 + CAS」，与线上真实调用路径一致；
 * 并新增一条守护用例，防止该旁路端点被无意恢复。</p>
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

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /** 接单（真实入口）：1 待配送 → 2 配送中，带 CAS 与配送员绑定副作用。 */
    private Api accept(long orderId) {
        return post("/api/delivery/orders/" + orderId + "/accept", mgrToken(), null);
    }

    /** 完成配送（真实入口）：前置状态必须是「配送中」，否则拒绝。 */
    private Api complete(long orderId) {
        return post("/api/delivery/orders/" + orderId + "/complete", mgrToken(), "{}");
    }

    private int dbStatus(long orderId) {
        Integer v = jdbc.queryForObject("SELECT status FROM orders WHERE id=?", Integer.class, orderId);
        return v == null ? -1 : v;
    }

    @Test
    @DisplayName("合法流转 1 待配送 → 2 配送中 成功且落库")
    void legalTransition_succeeds() {
        seedBase();
        // 已付(2)：本用例盯的是状态流转本身；"没收到钱不许接单"是另一条规则（2026-09-18），
        // 用待收款造数会让它被那条规则挡掉，测不出流转
        long order = createOrder(alice, addr, station, product, 1, 2);

        Api res = accept(order);

        assertTrue(res.isSuccess(), "1→2 应成功，实际=" + res);
        assertEquals(2, dbStatus(order), "状态应已更新为配送中");
    }

    @Test
    @DisplayName("非法跳转 1 待配送 → 4 已完成 被拒且不改库")
    void illegalSkip_isRejected() {
        seedBase();
        long order = createOrder(alice, addr, station, product, 1, 1);

        // 1 待配送时直接调「完成配送」= 跳级，必须被前置状态门槛拒绝
        Api res = complete(order);

        assertFalse(res.isSuccess(), "1→4 跳级应被拒，实际=" + res);
        assertEquals(1, dbStatus(order), "被拒后状态必须保持原状");
    }

    @Test
    @DisplayName("P0-4 守护：状态旁路端点 PUT /api/orders/{id}/status 必须不存在")
    void statusBypassEndpoint_isGone() {
        seedBase();
        long order = createOrder(alice, addr, station, product, 1, 1);

        // 该端点能一步把订单改成已完成/已取消却跳过全部资金与资产副作用，已于 2026-09-14 删除。
        // 此用例防止它被无意恢复：一旦有人加回来，这里立刻变红。
        Api res = put("/api/orders/" + order + "/status?status=4", mgrToken(), null);

        assertFalse(res.isSuccess(), "旁路端点必须已删除，实际=" + res);
        assertEquals(1, dbStatus(order), "状态必须保持原状");
    }

    @Test
    @DisplayName("终态 4 已完成 不可再流转")
    void terminalState_cannotTransition() {
        seedBase();
        long order = createOrder(alice, addr, station, product, 4, 2);

        Api res = accept(order);

        assertFalse(res.isSuccess(), "已完成订单不应可再流转，实际=" + res);
        assertEquals(4, dbStatus(order), "被拒后状态必须保持原状");
    }
}
