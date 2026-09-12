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
}
