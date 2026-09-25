package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建站必填：**名称 + 联系电话**（2026-09-19 产品裁定「电话也加到建立水站时的必填项吧」）。
 *
 * <p>依据：`station.phone` 可空，而真实库已有水站的电话是空串（济阳水站），客户在下单页看到的是
 * 「水站电话：请咨询客服」—— 退桶、催单、投诉都找不到人。</p>
 *
 * <p><b>两条建站路径都要堵</b>（只堵一条等于没堵）：</p>
 * <ol>
 *   <li>{@code POST /api/stations} —— 站长端「我的水站」里的建站入口（走 {@code StationServiceImpl.save}）；</li>
 *   <li>{@code POST /api/auth/create-station} —— 首次登录引导里的建站（走 {@code createStationAndBind}，**站长实际走的那条**）。</li>
 * </ol>
 *
 * <p>坐标**刻意不强制**：站长可能当场拿不到定位（用户拒授权/室内无信号），强制会把建站卡死；
 * 缺坐标由「信息完善引导」以 P0 提示（见 {@code StationSetupGuideIntegrationTest}）。</p>
 */
@DisplayName("建站必填：名称与联系电话（两条建站路径都校验）")
class StationCreationPhoneIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("POST /api/stations：没电话被拒、有电话才建得出来")
    void stationControllerRequiresPhone() {
        long station = createStation("电话校验站");
        long manager = createStaff("电话校验站长", "STATION_MANAGER", station, 1);
        String token = staffToken(manager, "STATION_MANAGER", station);

        Api noPhone = post("/api/stations", token, "{\"name\":\"没电话的站\"}");
        assertNotEquals(0, noPhone.code(), "没电话必须被拒，实际=" + noPhone);
        assertTrue(noPhone.message() != null && noPhone.message().contains("联系电话"),
                "拒绝文案要点名缺什么，实际=" + noPhone.message());

        Api ok = post("/api/stations", token,
                "{\"name\":\"有电话的站\",\"phone\":\"0531-77777777\",\"address\":\"历下区某路 1 号\"}");
        assertEquals(0, ok.code(), "有电话应能创建，实际=" + ok);
        assertEquals(1, intOf("SELECT COUNT(*) FROM station WHERE name='有电话的站' AND phone='0531-77777777'"),
                "电话要真的落库");
        assertEquals(0, intOf("SELECT COUNT(*) FROM station WHERE name='没电话的站'"), "被拒的站不得留痕");
    }

    @Test
    @DisplayName("POST /api/auth/create-station（首次登录引导那条路）：同样必须给电话")
    void loginCreateStationRequiresPhone() {
        // 该端点要求：staff 角色 = STATION_MANAGER 且还没绑站（staff.station_id 为 NULL）
        long manager = createStaff("新站长", "STATION_MANAGER", null, 1);
        String token = staffToken(manager, "STATION_MANAGER", null);

        Api noPhone = post("/api/auth/create-station", token, "{\"name\":\"引导建站没电话\"}");
        assertNotEquals(0, noPhone.code(), "没电话必须被拒，实际=" + noPhone);
        assertTrue(noPhone.message() != null && noPhone.message().contains("联系电话"),
                "拒绝文案要点名缺什么，实际=" + noPhone.message());
        assertEquals(0, intOf("SELECT COUNT(*) FROM station WHERE name='引导建站没电话'"), "被拒不得留痕");
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff WHERE id=? AND station_id IS NOT NULL", manager),
                "被拒时不得把 staff 绑到站上（整个事务回滚）");

        Api ok = post("/api/auth/create-station", token,
                "{\"name\":\"引导建站有电话\",\"phone\":\"0531-66666666\",\"province\":\"山东省\","
                        + "\"city\":\"济南市\",\"district\":\"历城区\",\"address\":\"某小区 8 栋\","
                        + "\"latitude\":36.6812,\"longitude\":117.0654}");
        assertEquals(0, ok.code(), "有电话应能建站，实际=" + ok);
        assertEquals(1, intOf("SELECT COUNT(*) FROM station WHERE name='引导建站有电话' AND phone='0531-66666666'"),
                "电话要真的落库");
        // 坐标仍允许为空（本端点刻意不强制）：上面这一次给了坐标，应当落库
        assertEquals(1, intOf("SELECT COUNT(*) FROM station WHERE name='引导建站有电话' AND lat IS NOT NULL"),
                "给了坐标要落库（v34 修过的静默丢弃不能再犯）");
    }

    @Test
    @DisplayName("名称也为空时同样被拒（既有行为不回退）")
    void blankNameStillRejected() {
        long station = createStation("空名校验站");
        long manager = createStaff("空名校验站长", "STATION_MANAGER", station, 1);
        Api res = post("/api/stations", staffToken(manager, "STATION_MANAGER", station),
                "{\"name\":\"   \",\"phone\":\"0531-55555555\"}");
        assertNotEquals(0, res.code(), "空白名称必须被拒，实际=" + res);
    }
}
