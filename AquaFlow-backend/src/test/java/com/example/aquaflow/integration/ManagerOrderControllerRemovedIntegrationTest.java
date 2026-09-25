package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * [Phase C4] 删除端点回归。
 *
 * <p>ManagerOrderController 的 8 个订单写端点（含可让前端直传 paymentStatus 的越权高危
 * {@code offline-exception}）已被整类删除（无活跃前端调用方，详见 {@code docs/audit/write-path-inventory.md} §2.3）。
 * 一旦有人误把旧接口路径写回前端、或新同学照着老文档接，测试必须立刻亮红灯。</p>
 *
 * <p>断言：带有效令牌访问这些已删除路径 → 项目统一返回 {@code code=404}（接口不存在）；
 * 同时用一个存活的 manager 端点证明服务本身是好的，404 是「路径被删」而非「服务挂了」。</p>
 */
@DisplayName("Phase C4 · ManagerOrderController 端点已删除（返回 404）")
class ManagerOrderControllerRemovedIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("/api/manager/orders/{id}/assign 删除后返回 404")
    void assignEndpoint_removed_returns404() {
        long s1 = createStation("S1");
        long m1 = createStaff("M1", "STATION_MANAGER", s1, 1);
        String token = staffToken(m1, "STATION_MANAGER", s1);

        Api res = post("/api/manager/orders/1/assign", token, "{}");

        assertEquals(404, res.code(), "已删除的 assign 端点应返回 404，实际=" + res);
    }

    @Test
    @DisplayName("/api/manager/offline-exception 删除后返回 404（含越权高危 CORRECT_PAYMENT）")
    void offlineExceptionEndpoint_removed_returns404() {
        long s1 = createStation("S1");
        long m1 = createStaff("M1", "STATION_MANAGER", s1, 1);
        String token = staffToken(m1, "STATION_MANAGER", s1);

        Api res = post("/api/manager/offline-exception", token,
                "{\"action\":\"CORRECT_PAYMENT\",\"orderId\":1,\"paymentStatus\":2}");

        assertEquals(404, res.code(), "已删除的 offline-exception 端点应返回 404，实际=" + res);
    }

    @Test
    @DisplayName("/api/manager/orders/transfer-apply 删除后返回 404")
    void transferApplyEndpoint_removed_returns404() {
        long s1 = createStation("S1");
        long m1 = createStaff("M1", "STATION_MANAGER", s1, 1);
        String token = staffToken(m1, "STATION_MANAGER", s1);

        Api res = post("/api/manager/orders/transfer-apply", token, "{}");

        assertEquals(404, res.code(), "已删除的 transfer-apply 端点应返回 404，实际=" + res);
    }

    @Test
    @DisplayName("存活的 manager 端点仍可达（证明 404 是删除所致，非服务异常）")
    void liveManagerEndpoint_stillReachable() {
        long s1 = createStation("S1");
        long m1 = createStaff("M1", "STATION_MANAGER", s1, 1);
        String token = staffToken(m1, "STATION_MANAGER", s1);

        Api res = get("/api/manager/staff", token);

        assertNotEquals(404, res.code(), "存活的 /api/manager/staff 不应返回 404，实际=" + res);
    }

    /**
     * [2026-09-18] 死端点评估（{@code docs/audit/2026-09-16-死端点评估.md}）里的 4 条已执行删除。
     *
     * <p>为什么钉在这里：其中两条（{@code station-exception} / {@code customer/exceptions/list}）
     * 不是"多余"，而是**会误导人** —— 前者名字叫"异常"、实际返回**取消单**（谁按名字接线，
     * 站长看到的就会是取消单列表）；后者是同一批数据的裸数组冗余版。删掉之后必须有东西拦住
     * "照着旧文档接回来"。</p>
     */
    @Test
    @DisplayName("死端点评估的 4 条已删除端点不再可用")
    void deadEndpointsRemoved_noLongerUsable() {
        long station = createStation("死端点站");
        long manager = createStaff("死端点站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("死端点客户", "openid-dead-endpoint");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 这两条没有同形路由争用 → 精确断言 404（接口不存在）
        assertEquals(404, get("/api/dashboard/order-status", mgr).code(),
                "已删除的看板端点应返回 404（口径与 /report 分叉，看板一律走 /report）");
        assertEquals(404, get("/api/dashboard/order-trend", mgr).code(),
                "已删除的看板端点应返回 404");

        // 这两条会落进同控制器的 /{id} 路由，因此拿到的是"id 非法"的拒绝而不是 404；
        // 共同且必须钉住的是：**不再返回成功**（谁把端点加回来，下面两条立刻变红）。
        assertNotEquals(0, get("/api/delivery/orders/station-exception", mgr).code(),
                "已删除的「异常」端点不应再返回成功（它返回的其实是取消单）");
        assertNotEquals(0, get("/api/customer/exceptions/list", cus).code(),
                "已删除的裸数组兼容端点不应再返回成功（改用 /api/customer/exceptions 分页）");
    }
}
