package com.example.aquaflow.integration;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.service.StaffBindCodeService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.support.TestBusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-03③（{@code docs/design/16} §9.3）：员工绑微信的凭据由「姓名 + 手机号」换成
 * **站长签发的一次性绑定码**。
 *
 * <p>这条改动把"谁能绑"从**公开信息**收回到水站手里，所以用例钉的是**安全属性**本身，
 * 而不是"接口能调通"：① 码只能用一次；② 过期即废；③ 只能给本站员工签发（不能跨站夺号）；
 * ④ 已绑过微信的不再签发（否则"再发一个码"就成了绕过解绑的通道）；
 * ⑤ <b>[F-44] 时效判据只有一个时钟</b>：签发与校验都取 {@code util/BusinessTime}，
 * 所以"拨动业务时钟"能确定性地造出"有效期内 / 刚越过有效期"两种状态（见
 * {@link #codeValidityFollowsInjectedClock}）。</p>
 *
 * <p>⚠️ <b>为什么没有"走 HTTP 完成一次真实绑定"的用例</b>：`AuthTokenService.bindStaff`
 * 第一步就要拿 {@code wx.login} 的 code 去调**真实微信接口**换 openid，本机无网/无凭据时
 * 必然失败，而给它塞 mock 又会把"免认证端点"的端到端语义测没。所以这里直接钉
 * {@link StaffBindCodeService}（绑定流程里唯一承载安全属性的那一段），
 * 端到端那一段依赖真机联调 —— 与 {@code bind-staff} 本就零调用方的现状一致。</p>
 */
@DisplayName("F-03③ · 员工绑定码：一次性 / 过期即废 / 只能本站签发")
@Import(TestBusinessClock.Config.class)
class StaffBindCodeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StaffBindCodeService staffBindCodeService;

    @Autowired
    private StaffMapper staffMapper;

    /**
     * [F-44] 每个用例先把业务"现在"钉在**数据库当前时刻**：Java 侧（签发）与 SQL 侧
     * （本类自己的造数/断言）从此读同一个时间值，跨零点不再一边说今天、一边说明天。
     * 要测"时间被拨动"的用例在用例体内显式 {@code TestBusinessClock.freezeAt(...)}。
     */
    @BeforeEach
    void freezeClockAtDbNow() {
        TestBusinessClock.freezeAtDbNow(jdbc);
    }

    @Test
    @DisplayName("站长签发的 6 位码能用一次；第二次必须被拒")
    void codeIsSingleUse() {
        long stationId = createStation("绑定码站");
        long managerId = createStaff("绑定码站长", "STATION_MANAGER", stationId, 1);
        long staffId = createStaff("绑定码配送员", "DELIVERY", stationId, 1);

        var res = post("/api/manager/staff/" + staffId + "/bind-code",
                staffToken(managerId, "STATION_MANAGER", stationId), "{}");
        assertTrue(res.isSuccess(), "站长应能为本站员工签发绑定码：" + res.message());

        // res.data() 是 Jackson 的 JsonNode，不是 Map —— 直接强转 Map 会 ClassCastException
        String code = res.data().get("code").asText();
        assertTrue(code.matches("\\d{6}"), "绑定码应是 6 位数字（允许前导 0），实际=" + code);

        // ① 第一次消费成功，且指向的是这名员工
        assertEquals(staffId, staffBindCodeService.consume(code, "openid-first"));
        // ② 第二次必须被拒 —— 这就是"一次性"的全部意义
        assertThrows(BusinessException.class,
                () -> staffBindCodeService.consume(code, "openid-second"),
                "同一个码第二次必须被拒");
        // ③ 留痕：used_openid 记的是第一个用掉它的人，第二个不能覆盖
        assertEquals(1, intOf("select count(*) from staff_bind_code where code = ? and used_openid = ?",
                code, "openid-first"));
    }

    @Test
    @DisplayName("过期码必须被拒（哪怕从没用过）")
    void expiredCodeRejected() {
        long stationId = createStation("过期码站");
        long staffId = createStaff("过期码配送员", "DELIVERY", stationId, 1);
        // 直接造一个"1 分钟前就过期"的未用码：判据必须同时看 used_at 与 expires_at。
        // [F-43/F-44] 到期时刻由**冻结时钟**算出来当参数传入，不再写 SQL 的 date_sub(now(), …)：
        // 否则"造数的库时钟"与"校验的业务时钟"是两套，冻结时钟的用例会在这里先分叉。
        insert("insert into staff_bind_code(staff_id, station_id, code, expires_at, create_time) " +
                        "values(?, ?, ?, ?, ?)",
                staffId, stationId, "000001",
                TestBusinessClock.now().minusMinutes(1), TestBusinessClock.now());

        assertThrows(BusinessException.class,
                () -> staffBindCodeService.consume("000001", "openid-expired"),
                "过期码必须被拒");
    }

    /**
     * [F-44] 时效判据的**跨时钟边界**用例：签发侧（Java）与校验侧（原先 SQL 的 {@code NOW()}）
     * 必须同源，否则"拨动时钟"这件事根本测不出来。
     *
     * <p>反向验证（把守卫改坏必然变红）：把 {@code StaffBindCodeMapper} 的两条 SQL 改回
     * {@code expires_at > NOW()}（读库的真实时间）—— 本用例冻结在 2099 年，签发的码在
     * 2026 年的库眼里"远未过期"，第 ② 步（越过有效期必须被拒）会失败。</p>
     */
    @Test
    @DisplayName("F-44 · 绑定码时效跟着注入时钟走：有效期内可用 / 越过有效期即拒")
    void codeValidityFollowsInjectedClock() {
        long stationId = createStation("时钟绑定码站");
        long managerId = createStaff("时钟绑定码站长", "STATION_MANAGER", stationId, 1);
        long staffId = createStaff("时钟绑定码配送员", "DELIVERY", stationId, 1);
        String mgr = staffToken(managerId, "STATION_MANAGER", stationId);

        // 冻结在一个与真实时间不可能重合的时刻：这才让"过期"成为可确定性复现的状态
        // （跟随真实时间跑的话，只能在 23:50 之后碰运气触发）。
        LocalDateTime issuedAt = LocalDateTime.of(2099, 6, 1, 10, 0, 0);
        TestBusinessClock.freezeAt(issuedAt);

        // ① 签发后第 9 分钟：仍在 10 分钟有效期内，必须能用
        String inWindowCode = issueBindCode(mgr, staffId);
        TestBusinessClock.freezeAt(issuedAt.plusMinutes(9));
        assertEquals(staffId, staffBindCodeService.consume(inWindowCode, "openid-in-window"),
                "签发后 9 分钟仍在有效期内，必须能用");

        // ② 回到签发时刻再签一个，然后拨到第 11 分钟：越过有效期，必须被拒
        TestBusinessClock.freezeAt(issuedAt);
        String pastWindowCode = issueBindCode(mgr, staffId);
        TestBusinessClock.freezeAt(issuedAt.plusMinutes(11));
        assertThrows(BusinessException.class,
                () -> staffBindCodeService.consume(pastWindowCode, "openid-past-window"),
                "签发后 11 分钟已越过 10 分钟有效期，必须被拒");

        // ③ 库里存的是**注入时钟**算出来的到期时刻（2099-06-01 10:10 附近），
        //    不是库的真实时间 —— 这一条把"签发侧用了哪个时钟"也钉住。
        //    用区间比较而不是字符串相等：避免 JDBC 时区换算把断言弄成假红（那会掩盖真问题）。
        assertEquals(1, intOf("select count(*) from staff_bind_code where code = ? and expires_at between ? and ?",
                        pastWindowCode, issuedAt.plusMinutes(9), issuedAt.plusMinutes(11)),
                "expires_at 必须是「注入时钟的签发时刻 + 10 分钟」");
    }

    @Test
    @DisplayName("不能给别站员工签发绑定码（站别只认登录态，不信请求参数）")
    void cannotGenerateForOtherStationStaff() {
        long stationA = createStation("绑定码A站");
        long stationB = createStation("绑定码B站");
        long managerA = createStaff("A站站长", "STATION_MANAGER", stationA, 1);
        long staffB = createStaff("B站配送员", "DELIVERY", stationB, 1);

        var res = post("/api/manager/staff/" + staffB + "/bind-code",
                staffToken(managerA, "STATION_MANAGER", stationA), "{}");
        assertEquals(1, res.code(), "跨站签发必须被拒（否则等于跨站夺取账号）：" + res.message());
        assertEquals(0, intOf("select count(*) from staff_bind_code where staff_id = ?", staffB),
                "被拒时不该留下任何码");
    }

    @Test
    @DisplayName("已绑过微信的员工不再签发（换微信必须走解绑）")
    void alreadyBoundStaffNotIssued() {
        long stationId = createStation("已绑站");
        long managerId = createStaff("已绑站站长", "STATION_MANAGER", stationId, 1);
        long staffId = createStaff("已绑配送员", "DELIVERY", stationId, 1);
        // 用生产代码的 CAS 置 openid：helper `insert()` 会去取自增主键，拿它跑 UPDATE 会抛
        // 「未取到自增主键」，所以这里走 StaffMapper。
        staffMapper.bindOpenid(staffId, "openid-already-bound");

        var res = post("/api/manager/staff/" + staffId + "/bind-code",
                staffToken(managerId, "STATION_MANAGER", stationId), "{}");
        assertEquals(1, res.code(), "已绑微信的员工不该再签发绑定码：" + res.message());
    }

    /** 走 HTTP 让站长签发一个码，返回码本身（失败即断言失败）。 */
    private String issueBindCode(String managerToken, long staffId) {
        var res = post("/api/manager/staff/" + staffId + "/bind-code", managerToken, "{}");
        assertTrue(res.isSuccess(), "签发绑定码应成功：" + res.message());
        return res.data().get("code").asText();
    }
}
