package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /api/auth/select-role} 的身份来源回归。
 *
 * <p>背景：员工首次进入配送端时还没有 staff 记录，接口需要知道"这个微信是哪个 openid"
 * 才能建记录。旧实现是让客户端把 openid 放在请求体里回传（{@code _pendingOpenid}），
 * 等于<b>让调用方自报身份</b>：任何持 UNSELECTED token 的人把该字段换成别人的 openid，
 * 就能把那个 openid 绑到自己新建的员工记录上；配合 {@code staff.uk_staff_openid} 唯一键，
 * 真实主人之后再登录会直接落到这条被抢绑的记录上。</p>
 *
 * <p>现在的约束：openid 在 {@code wx-login-staff} 签发 token 时就签入 JWT claims，
 * {@code selectRole} 只读 {@code AuthContext.getPendingOpenid()}，
 * <b>客户端传什么参数都不影响结果</b>。本用例锁的就是这条。</p>
 *
 * <p>[2026-09-17 补充] 「选择 ≠ 生效」：未挂到水站上（{@code staff.station_id} 为空）时允许改选，
 * 生效后拒绝。注意 {@code pendingOpenid} <b>只用于"还没有记录、需要新建"这条路径</b>：
 * 改选路径的凭据是 JWT 里的 {@code userId}（它本身就是那条员工记录），
 * 而身份选定后签发的 token 是四参重载、{@code pendingOpenid} 为 null —— 所以
 * <b>openid 校验必须放在改选分支之后</b>，否则改选会永远以「身份信息缺失」失败。</p>
 */
@DisplayName("Phase B · select-role 身份来源（JWT 而非请求体）+ 未生效可改选")
class SelectRoleIdentityIntegrationTest extends AbstractIntegrationTest {

    private static final long VIRTUAL_USER_ID = -123456789L;

    @Test
    @DisplayName("请求体伪造 _pendingOpenid 无效：只认 JWT 里签入的 openid")
    void forgedPendingOpenidInBody_isIgnored() {
        String token = unselectedStaffToken(VIRTUAL_USER_ID, "openid-real-owner");

        // 攻击形态：token 是真实持有者的，但请求体把 openid 换成受害者的
        Api res = post("/api/auth/select-role", token,
                "{\"role\":\"DELIVERY\",\"nickname\":\"攻击者\",\"phone\":\"13900000000\","
                        + "\"_pendingOpenid\":\"openid-victim\"}");

        assertTrue(res.isSuccess(), "首次选身份应成功，实际=" + res);

        // 关键断言：落库的 openid 必须是 token 里的那个，绝不是请求体里的
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff WHERE openid=?", "openid-real-owner"),
                "必须绑定 JWT 中签入的 openid");
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff WHERE openid=?", "openid-victim"),
                "请求体里的伪造 openid 绝不能落库（否则可抢绑他人微信）");
    }

    @Test
    @DisplayName("token 中没有 openid（非 UNSELECTED 会话）→ 拒绝，不得建员工记录")
    void missingOpenidInToken_isRejected() {
        // 用一个普通 staff token（没有 pendingOpenid claim）调用
        long station = createStation("S1");
        long staff = createStaff("已有员工", "DELIVERY", station, 1);
        String token = staffToken(staff, "DELIVERY", station);

        Api res = post("/api/auth/select-role", token,
                "{\"role\":\"DELIVERY\",\"nickname\":\"想混进来\",\"phone\":\"13900000001\","
                        + "\"_pendingOpenid\":\"openid-whatever\"}");

        assertTrue(!res.isSuccess(), "无 token 内 openid 时必须拒绝，实际=" + res);
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff"), "不得新增任何员工记录");
    }

    @Test
    @DisplayName("该微信已绑定过员工 → 拒绝重复建记录（而不是撞唯一键报数据库错误）")
    void alreadyBoundOpenid_isRejectedWithFriendlyMessage() {
        long station = createStation("S1");
        insert("INSERT INTO staff(name, role, station_id, status, openid) VALUES (?,?,?,1,?)",
                "已绑定员工", "DELIVERY", station, "openid-taken");

        String token = unselectedStaffToken(VIRTUAL_USER_ID, "openid-taken");
        Api res = post("/api/auth/select-role", token,
                "{\"role\":\"DELIVERY\",\"nickname\":\"重复\",\"phone\":\"13900000002\","
                        + "\"_pendingOpenid\":\"openid-taken\"}");

        assertTrue(!res.isSuccess(), "已绑定的 openid 不应能再建记录，实际=" + res);
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff WHERE openid=?", "openid-taken"),
                "同一 openid 只能有一条员工记录");
    }

    // ==================== [2026-09-17] 「选择 ≠ 生效」：未生效可改选 ====================
    // 产品口径：选角色只是登记意向，还没挂到水站上就不算生效 → 允许返回重选；
    // 生效后（staff.station_id 非空）禁止自行更改，须走管理员。

    @Test
    @DisplayName("未生效可改选：配送员还没被批准绑定 → 就地改成站长（不新增记录）")
    void reselectRoleBeforeEffective_isAllowed() {
        long staffId = createStaff("待定身份", "DELIVERY", null, 1);
        String token = staffToken(staffId, "DELIVERY", null);

        Api res = post("/api/auth/select-role", token, "{\"role\":\"STATION_MANAGER\"}");

        assertTrue(res.isSuccess(), "未生效应允许改选，实际=" + res);
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff WHERE id=? AND role='STATION_MANAGER'", staffId),
                "角色应被就地改成 STATION_MANAGER");
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff"),
                "不得新增员工记录（改选是改既有行，新建会撞 uk_staff_openid）");
    }

    @Test
    @DisplayName("已生效禁止改选：已绑定水站 → 拒绝，且角色不被改动")
    void reselectRoleAfterEffective_isRejected() {
        long station = createStation("S1");
        long staffId = createStaff("已绑定配送员", "DELIVERY", station, 1);
        String token = staffToken(staffId, "DELIVERY", station);

        Api res = post("/api/auth/select-role", token, "{\"role\":\"STATION_MANAGER\"}");

        assertTrue(!res.isSuccess(), "已生效不允许自行改选，实际=" + res);
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff WHERE id=? AND role='DELIVERY'", staffId),
                "角色必须保持不变");
    }

    @Test
    @DisplayName("改选时撤掉悬挂的待审批绑定申请（否则站长看到一个永远批不掉的申请）")
    void reselectCancelsDanglingPendingApplication() {
        long station = createStation("S1");
        long staffId = createStaff("想改行的配送员", "DELIVERY", null, 1);
        insert("INSERT INTO staff_station_application(staff_id, station_id, type, status) VALUES (?,?,1,1)",
                staffId, station);
        String token = staffToken(staffId, "DELIVERY", null);

        Api res = post("/api/auth/select-role", token, "{\"role\":\"STATION_MANAGER\"}");

        assertTrue(res.isSuccess(), "未生效应允许改选，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_station_application "
                        + "WHERE staff_id=? AND status=1", staffId),
                "改选后不得再留下待审批的绑定申请（对方已不是配送员，approveBind 会以"
                        + "「仅配送员可审批绑定」拒掉，等于让站长白点一次）");
    }
}
