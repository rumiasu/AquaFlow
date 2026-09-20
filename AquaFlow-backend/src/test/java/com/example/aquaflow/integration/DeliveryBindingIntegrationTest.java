package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送员 ↔ 水站 绑定 / 解绑审批（`DeliveryBindingController` 12 个端点）。
 *
 * <p>2026-09-16 本轮之前**一条用例都没有**。这条链路是"员工归属"的唯一变更入口，
 * 归属又决定他能看到/操作哪些订单与桶账，所以三类守卫必须钉死：</p>
 * <ol>
 *   <li><b>状态机</b>：待审批的申请不能被重复审批；已处理的申请不能再改（否则会重复改 {@code staff.station_id}）。</li>
 *   <li><b>归属</b>：只有目标水站自己的站长能审批；员工只能取消自己发起的申请。</li>
 *   <li><b>不变量</b>：只有「同意绑定 / 同意解绑 / 站长强制解除」才动 {@code staff.station_id}，
 *       「拒绝」与「取消」都必须原样保留（拒绝绑定后员工仍是无站状态，拒绝解绑后仍是本站员工）。</li>
 * </ol>
 */
class DeliveryBindingIntegrationTest extends AbstractIntegrationTest {

    private long bindAppId(long staffId) {
        return longOf("SELECT id FROM staff_station_application WHERE staff_id=? AND type=1 ORDER BY id DESC LIMIT 1",
                staffId);
    }

    private long unbindAppId(long staffId) {
        return longOf("SELECT id FROM staff_station_application WHERE staff_id=? AND type=2 ORDER BY id DESC LIMIT 1",
                staffId);
    }

    @Test
    @DisplayName("绑定申请：申请→站长同意→归属落库；重复申请/重复审批/跨站审批全被拒")
    void bindApplyAndApprove() {
        long stationA = createStation("绑定站A");
        long stationB = createStation("绑定站B");
        long managerA = createStaff("绑定站长A", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("绑定站长B", "STATION_MANAGER", stationB, 1);
        long delivery = createStaff("待绑定配送员", "DELIVERY", null, 1);
        long customer = createCustomer("绑定客户", "bind-openid");

        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        String mgrB = staffToken(managerB, "STATION_MANAGER", stationB);
        String del = staffToken(delivery, "DELIVERY", null);
        String cus = customerToken(customer);

        // 未绑定状态
        Api initial = get("/api/delivery/bind/status", del);
        assertEquals(0, initial.code(), "查绑定状态: " + initial);
        assertEquals("UNBOUND", initial.data().path("bindingStatus").asText());
        assertTrue(initial.data().path("pendingApplication").isNull(), "还没申请时不该有待审单");

        // 目标站不存在必须被拒
        assertNotEquals(0, post("/api/delivery/bind/apply", del, "{\"stationId\":999999}").code(),
                "申请一个不存在的水站应被拒");

        Api applied = post("/api/delivery/bind/apply", del, "{\"stationId\":" + stationA + ",\"applyNote\":\"我想加入\"}");
        assertEquals(0, applied.code(), "配送员申请绑定: " + applied);
        long appId = bindAppId(delivery);
        assertEquals(1, intOf("SELECT status FROM staff_station_application WHERE id=?", appId), "应为待审批(1)");
        assertEquals(stationA, longOf("SELECT station_id FROM staff_station_application WHERE id=?", appId));
        assertNull(jdbc.queryForObject("SELECT station_id FROM staff WHERE id=?", Object.class, delivery),
                "申请阶段不得改员工归属 —— 只有审批通过才动 station_id");

        assertNotEquals(0, post("/api/delivery/bind/apply", del, "{\"stationId\":" + stationA + "}").code(),
                "同一水站的重复待审申请必须被拒");

        Api status = get("/api/delivery/bind/status", del);
        assertEquals("PENDING", status.data().path("bindingStatus").asText());
        assertTrue(get("/api/delivery/bind/status", del).data().path("pendingApplication").path("id").asLong() > 0);

        assertEquals(0, get("/api/delivery/bind/applications", del).code(), "配送员查自己的申请");
        assertEquals(1, get("/api/delivery/bind/applications", del).data().size());

        Api managerList = get("/api/manager/bind/applications?status=PENDING&type=1", mgrA);
        assertEquals(0, managerList.code(), "站长待审列表: " + managerList);
        assertEquals(1, managerList.data().size(), "只看到本站的申请");
        assertEquals(0, get("/api/manager/staff", mgrA).code(), "站长员工名册");
        assertNotEquals(0, get("/api/manager/bind/applications", del).code(), "配送员不得用站长端列表");
        assertNotEquals(0, get("/api/manager/staff", cus).code(), "顾客不得看员工名册");

        // 他站站长不得审批本站申请
        assertNotEquals(0, post("/api/manager/bind/approve", mgrB, "{\"applicationId\":" + appId + "}").code(),
                "他站站长不得审批不属本站的申请");
        assertEquals(1, intOf("SELECT status FROM staff_station_application WHERE id=?", appId), "被拒后仍是待审批");

        Api approved = post("/api/manager/bind/approve", mgrA,
                "{\"applicationId\":" + appId + ",\"handleNote\":\"欢迎\"}");
        assertEquals(0, approved.code(), "站长同意绑定: " + approved);
        assertEquals(stationA, longOf("SELECT station_id FROM staff WHERE id=?", delivery), "归属应落到本站");
        assertEquals(2, intOf("SELECT status FROM staff_station_application WHERE id=?", appId), "申请应为已同意(2)");
        assertEquals(managerA, longOf("SELECT handle_staff_id FROM staff_station_application WHERE id=?", appId),
                "应记录审批人");
        assertEquals("BOUND", get("/api/delivery/bind/status", del).data().path("bindingStatus").asText());

        assertNotEquals(0, post("/api/manager/bind/approve", mgrA, "{\"applicationId\":" + appId + "}").code(),
                "已处理的申请不得重复审批（否则会重复改归属）");
        assertNotEquals(0, post("/api/delivery/bind/apply", del, "{\"stationId\":" + stationB + "}").code(),
                "已绑定水站的员工不得直接申请换站，必须先解绑");

        // 站长不能借这条通道给自己绑站（该接口只服务配送员）
        long unboundManager = createStaff("无站站长", "STATION_MANAGER", null, 1);
        assertNotEquals(0, post("/api/delivery/bind/apply",
                staffToken(unboundManager, "STATION_MANAGER", null), "{\"stationId\":" + stationA + "}").code(),
                "仅配送员可申请绑定");

        assertNotEquals(0, post("/api/delivery/bind/apply", cus, "{\"stationId\":" + stationA + "}").code(),
                "顾客不得申请绑定");
    }

    @Test
    @DisplayName("取消与拒绝：都不改归属，且只能动自己的申请")
    void bindCancelAndReject() {
        long station = createStation("取消站");
        long manager = createStaff("取消站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("取消配送员", "DELIVERY", null, 1);
        long other = createStaff("别人配送员", "DELIVERY", null, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String del = staffToken(delivery, "DELIVERY", null);
        String otherTok = staffToken(other, "DELIVERY", null);

        assertEquals(0, post("/api/delivery/bind/apply", del, "{\"stationId\":" + station + "}").code());
        long appId = bindAppId(delivery);

        // 不能取消别人的申请
        assertNotEquals(0, post("/api/delivery/bind/cancel", otherTok,
                "{\"applicationId\":" + appId + "}").code(), "只能取消自己发起的申请");
        assertEquals(1, intOf("SELECT status FROM staff_station_application WHERE id=?", appId));

        // 本人取消（不传 applicationId 时取自己最新一条待审绑定申请）
        Api cancelled = post("/api/delivery/bind/cancel", del, null);
        assertEquals(0, cancelled.code(), "取消申请: " + cancelled);
        assertEquals(4, intOf("SELECT status FROM staff_station_application WHERE id=?", appId), "应为已取消(4)");
        assertNull(jdbc.queryForObject("SELECT station_id FROM staff WHERE id=?", Object.class, delivery));
        assertEquals("UNBOUND", get("/api/delivery/bind/status", del).data().path("bindingStatus").asText());
        assertNotEquals(0, post("/api/delivery/bind/cancel", del, null).code(), "没有待审申请时取消应报错");

        // 再次申请后由站长拒绝：归属必须仍为空
        assertEquals(0, post("/api/delivery/bind/apply", del, "{\"stationId\":" + station + "}").code());
        long appId2 = bindAppId(delivery);
        assertEquals(0, post("/api/manager/bind/reject", mgr,
                "{\"applicationId\":" + appId2 + ",\"reason\":\"人手够了\"}").code(), "站长拒绝绑定");
        assertEquals(3, intOf("SELECT status FROM staff_station_application WHERE id=?", appId2), "应为已拒绝(3)");
        assertNull(jdbc.queryForObject("SELECT station_id FROM staff WHERE id=?", Object.class, delivery),
                "拒绝绑定不得写入归属");
        assertEquals("UNBOUND", get("/api/delivery/bind/status", del).data().path("bindingStatus").asText());

        // 被拒后可以重新申请（不能因为历史被拒记录就永久卡住）
        assertEquals(0, post("/api/delivery/bind/apply", del, "{\"stationId\":" + station + "}").code(),
                "被拒后应允许重新申请");
    }

    @Test
    @DisplayName("解绑：申请→站长同意清空归属；拒绝则保持已绑定")
    void unbindConfirmAndReject() {
        long station = createStation("解绑站");
        long manager = createStaff("解绑站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("解绑配送员", "DELIVERY", station, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String del = staffToken(delivery, "DELIVERY", station);

        assertEquals("BOUND", get("/api/delivery/bind/status", del).data().path("bindingStatus").asText());
        assertEquals(0, post("/api/delivery/bind/unbind-request", del, "{\"applyNote\":\"回家\"}").code(),
                "配送员申请解绑");
        long appId = unbindAppId(delivery);
        assertEquals(1, intOf("SELECT status FROM staff_station_application WHERE id=?", appId));
        assertEquals(station, longOf("SELECT station_id FROM staff WHERE id=?", delivery), "申请阶段归属不变");
        assertEquals("PENDING_UNBIND", get("/api/delivery/bind/status", del).data().path("bindingStatus").asText());
        assertNotEquals(0, post("/api/delivery/bind/unbind-request", del, null).code(), "不得重复提交解绑申请");

        // 拒绝解绑：仍是本站员工
        assertEquals(0, post("/api/manager/bind/unbind-reject", mgr,
                "{\"applicationId\":" + appId + ",\"reason\":\"旺季缺人\"}").code(), "站长拒绝解绑");
        assertEquals(3, intOf("SELECT status FROM staff_station_application WHERE id=?", appId));
        assertEquals(station, longOf("SELECT station_id FROM staff WHERE id=?", delivery), "拒绝解绑后归属保留");
        assertEquals("BOUND", get("/api/delivery/bind/status", del).data().path("bindingStatus").asText());

        // 再申请一次，这次同意：归属清空
        assertEquals(0, post("/api/delivery/bind/unbind-request", del, null).code());
        long appId2 = unbindAppId(delivery);
        assertEquals(0, post("/api/manager/bind/unbind-confirm", mgr,
                "{\"applicationId\":" + appId2 + ",\"handleNote\":\"同意\"}").code(), "站长同意解绑");
        assertNull(jdbc.queryForObject("SELECT station_id FROM staff WHERE id=?", Object.class, delivery),
                "同意解绑应清空归属");
        assertEquals(2, intOf("SELECT status FROM staff_station_application WHERE id=?", appId2));
        assertEquals("UNBOUND", get("/api/delivery/bind/status", del).data().path("bindingStatus").asText());

        // 未绑定的人不能申请解绑
        assertNotEquals(0, post("/api/delivery/bind/unbind-request", del, null).code(),
                "未绑定任何水站时申请解绑应被拒");

        // 类型错配：拿绑定申请去走解绑审批必须被拒（防止用错接口改归属）
        long unbound = createStaff("另一个配送员", "DELIVERY", null, 1);
        String unboundTok = staffToken(unbound, "DELIVERY", null);
        assertEquals(0, post("/api/delivery/bind/apply", unboundTok, "{\"stationId\":" + station + "}").code());
        long bindApp = bindAppId(unbound);
        assertNotEquals(0, post("/api/manager/bind/unbind-confirm", mgr, "{\"applicationId\":" + bindApp + "}").code(),
                "绑定申请不能走解绑审批");
        assertNotEquals(0, post("/api/manager/bind/unbind-reject", mgr, "{\"applicationId\":" + bindApp + "}").code());
        assertNull(jdbc.queryForObject("SELECT station_id FROM staff WHERE id=?", Object.class, unbound),
                "错配的审批不得改动归属");
    }

    @Test
    @DisplayName("站长强制解除：直接清空本站员工归属，且只能对自己站的员工用")
    void managerReleaseUnbindsDirectly() {
        long stationA = createStation("强制解除站A");
        long stationB = createStation("强制解除站B");
        long managerA = createStaff("解除站长A", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("解除站长B", "STATION_MANAGER", stationB, 1);
        long mine = createStaff("本站配送员", "DELIVERY", stationA, 1);
        long theirs = createStaff("他站配送员", "DELIVERY", stationB, 1);
        long customer = createCustomer("解除客户", "release-openid");

        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        String mgrB = staffToken(managerB, "STATION_MANAGER", stationB);

        assertNotEquals(0, post("/api/manager/bind/release", mgrA, "{\"staffId\":" + theirs + "}").code(),
                "不得强制解除他站员工");
        assertEquals(stationB, longOf("SELECT station_id FROM staff WHERE id=?", theirs));

        assertEquals(0, post("/api/manager/bind/release", mgrA,
                "{\"staffId\":" + mine + ",\"reason\":\"已离职\"}").code(), "站长强制解除本站员工");
        assertNull(jdbc.queryForObject("SELECT station_id FROM staff WHERE id=?", Object.class, mine));
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_station_application WHERE staff_id=?", mine),
                "强制解除不走申请单（它不需要员工同意），不产生申请记录");

        // [2026-09-19] 站长不可被解除 —— 该护栏补之前，这两条都会**成功**，把水站变成没人管的孤儿：
        // staff.station_id 被置 NULL → 站长所有 requireStationId() 端点全废、被路由去"创建水站"，
        // 而站里的客户/订单/库存还在，客户照常下单却没人接。重建水站是新 id，数据搬不回来。
        assertEquals(stationA, longOf("SELECT station_id FROM staff WHERE id=?", managerA),
                "前置：站长自己挂在 stationA 下（listByStationId 不带 role 条件，站长也在列表里）");

        assertNotEquals(0, post("/api/manager/bind/release", mgrA, "{\"staffId\":" + managerA + "}").code(),
                "站长不得解除自己");
        assertEquals(stationA, longOf("SELECT station_id FROM staff WHERE id=?", managerA),
                "被拒后站长归属必须原样保留");

        long managerA2 = createStaff("解除站A第二站长", "STATION_MANAGER", stationA, 1);
        assertNotEquals(0, post("/api/manager/bind/release", mgrA, "{\"staffId\":" + managerA2 + "}").code(),
                "站长不得解除另一个站长（role 门禁，不只看是不是自己）");
        assertEquals(stationA, longOf("SELECT station_id FROM staff WHERE id=?", managerA2),
                "被拒后另一站长的归属必须原样保留");

        // 角色门禁
        assertNotEquals(0, post("/api/manager/bind/release", staffToken(mine, "DELIVERY", null),
                "{\"staffId\":" + theirs + "}").code(), "配送员不得强制解除他人");
        assertNotEquals(0, post("/api/manager/bind/release", customerToken(customer),
                "{\"staffId\":" + mine + "}").code(), "顾客不得强制解除");
        assertNotEquals(0, post("/api/manager/bind/approve", mgrB, "{\"applicationId\":1}").code(),
                "没有待审申请时审批应报错");
    }
}
