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
 */
@DisplayName("Phase B · select-role 身份来源（JWT 而非请求体）")
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
}
