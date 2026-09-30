package com.example.aquaflow.integration;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.service.StaffBindCodeService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-03③（{@code docs/design/16} §9.3）：员工绑微信的凭据由「姓名 + 手机号」换成
 * **站长签发的一次性绑定码**。
 *
 * <p>这条改动把"谁能绑"从**公开信息**收回到水站手里，所以用例钉的是**安全属性**本身，
 * 而不是"接口能调通"：① 码只能用一次；② 过期即废；③ 只能给本站员工签发（不能跨站夺号）；
 * ④ 已绑过微信的不再签发（否则"再发一个码"就成了绕过解绑的通道）。</p>
 *
 * <p>⚠️ <b>为什么没有"走 HTTP 完成一次真实绑定"的用例</b>：`AuthTokenService.bindStaff`
 * 第一步就要拿 {@code wx.login} 的 code 去调**真实微信接口**换 openid，本机无网/无凭据时
 * 必然失败，而给它塞 mock 又会把"免认证端点"的端到端语义测没。所以这里直接钉
 * {@link StaffBindCodeService}（绑定流程里唯一承载安全属性的那一段），
 * 端到端那一段依赖真机联调 —— 与 {@code bind-staff} 本就零调用方的现状一致。</p>
 */
@DisplayName("F-03③ · 员工绑定码：一次性 / 过期即废 / 只能本站签发")
class StaffBindCodeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StaffBindCodeService staffBindCodeService;

    @Autowired
    private StaffMapper staffMapper;

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
        String code = ((com.fasterxml.jackson.databind.JsonNode) res.data()).get("code").asText();
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
        // 直接造一个"1 分钟前就过期"的未用码：判据必须同时看 used_at 与 expires_at
        insert("insert into staff_bind_code(staff_id, station_id, code, expires_at, create_time) " +
                        "values(?, ?, ?, date_sub(now(), interval 1 minute), now())",
                staffId, stationId, "000001");

        assertThrows(BusinessException.class,
                () -> staffBindCodeService.consume("000001", "openid-expired"),
                "过期码必须被拒");
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
}
