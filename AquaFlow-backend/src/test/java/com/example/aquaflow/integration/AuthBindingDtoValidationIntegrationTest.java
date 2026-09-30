package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase F 契约测试：认证/绑定/反馈/商品管理入口的边界校验。
 * 非法请求体必须被 @Valid 在边界拒回（code=1），且不落库；
 * 合法请求体仍正常放行（契约保形）。
 */
class AuthBindingDtoValidationIntegrationTest extends AbstractIntegrationTest {

    // ---- 认证族 ----

    @Test
    @DisplayName("wx-login 缺 code 被边界拒回且不创建客户")
    void wxLoginMissingCodeRejected() {
        Api res = post("/api/auth/wx-login", null, "{}");
        assertTrue(!res.isSuccess(), "缺 code 应被拒: " + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer"), "不应创建客户");
    }

    @Test
    @DisplayName("login 缺 username 被边界拒回")
    void loginMissingUsernameRejected() {
        Api res = post("/api/auth/login", null, "{\"password\":\"whatever\"}");
        assertTrue(!res.isSuccess(), "缺 username 应被拒: " + res);
        // [F-31 2026-09-30] 原来只断言 !isSuccess —— 密码是假的，登录本来就会失败，
        // 于是把 username 的 @NotBlank 摘掉这条用例照样绿（走错分支也算过）。
        // 现钉住拒绝文案来自 Bean Validation，而不是"用户名或密码错误"那条业务分支。
        assertTrue(res.message() != null && res.message().contains("用户名和密码不能为空"),
                "拒绝必须来自 username 的 @NotBlank，实际=" + res.message());
    }

    @Test
    @DisplayName("change-password 新密码过短被边界拒回（@Size(min=6)）")
    void changePasswordTooShortRejected() {
        long staff = createStaff("改密员", "DELIVERY", null, 1);
        String token = staffToken(staff, "DELIVERY", null);
        Api res = post("/api/auth/change-password", token,
                "{\"oldPassword\":\"123456\",\"newPassword\":\"123\"}");
        assertTrue(!res.isSuccess(), "过短新密码应被拒: " + res);
        // [F-31 2026-09-30] 原来只断言 !isSuccess —— staffToken 造的会话本身也可能被别的
        // 前置校验挡下，只说"不成功"证明不了 @Size(min=6) 还在。现钉住字段级文案。
        assertTrue(res.message() != null && res.message().contains("新密码长度不能少于6位"),
                "拒绝必须来自 newPassword 的 @Size(min=6)，实际=" + res.message());
    }

    // ---- 绑定族 ----

    @Test
    @DisplayName("bind/apply 缺 stationId 被边界拒回且不写申请")
    void bindApplyMissingStationIdRejected() {
        long staff = createStaff("配送员甲", "DELIVERY", null, 1);
        String token = staffToken(staff, "DELIVERY", null);
        Api res = post("/api/delivery/bind/apply", token, "{\"applyNote\":\"想加入\"}");
        assertTrue(!res.isSuccess(), "缺 stationId 应被拒: " + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_station_application"), "不应写申请");
    }

    @Test
    @DisplayName("bind/release 缺 staffId 被边界拒回")
    void releaseMissingStaffIdRejected() {
        long mgr = createStaff("站长甲", "STATION_MANAGER", null, 1);
        long station = createStation("测试站");
        String token = staffToken(mgr, "STATION_MANAGER", station);
        Api res = post("/api/manager/bind/release", token, "{\"reason\":\"走吧\"}");
        assertTrue(!res.isSuccess(), "缺 staffId 应被拒: " + res);
        // [F-31 2026-09-30] 原来只断言 !isSuccess —— 该站长尚未绑任何配送员，
        // 「找不到员工」同样会拒，摘掉 staffId 的 @NotNull 这条用例仍是绿的。现钉住文案。
        assertTrue(res.message() != null && res.message().contains("staffId 不能为空"),
                "拒绝必须来自 staffId 的 @NotNull，实际=" + res.message());
    }

    // ---- 反馈 ----

    @Test
    @DisplayName("feedback 空内容被边界拒回且不落库")
    void feedbackEmptyContentRejected() {
        long customer = createCustomer("反馈人", "fb-openid");
        String token = customerToken(customer);
        Api res = post("/api/feedback", token, "{\"category\":\"建议\",\"content\":\"   \"}");
        assertTrue(!res.isSuccess(), "空内容应被拒: " + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM feedback"), "不应落库");
    }

    @Test
    @DisplayName("feedback 合法体正常放行（契约保形）")
    void feedbackValidBodyAccepted() {
        long customer = createCustomer("反馈人", "fb-openid-2");
        String token = customerToken(customer);
        Api res = post("/api/feedback", token,
                "{\"category\":\"建议\",\"content\":\"希望增加夜间配送\",\"contact\":\"13800000000\"}");
        assertTrue(res.isSuccess(), "合法体应放行: " + res);
        assertEquals(1, intOf("SELECT COUNT(*) FROM feedback"), "应落库 1 条");
    }

    // ---- 商品管理 ----

    @Test
    @DisplayName("my-products 保存缺 name 被边界拒回且不落库")
    void productSaveMissingNameRejected() {
        long mgr = createStaff("站长乙", "STATION_MANAGER", null, 1);
        long station = createStation("商品站");
        String token = staffToken(mgr, "STATION_MANAGER", station);
        Api res = post("/api/manager/my-products", token, "{\"price\":12.5}");
        assertTrue(!res.isSuccess(), "缺 name 应被拒: " + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM product"), "不应落库");
    }

    @Test
    @DisplayName("my-products 保存缺 price 被边界拒回")
    void productSaveMissingPriceRejected() {
        long mgr = createStaff("站长丙", "STATION_MANAGER", null, 1);
        long station = createStation("商品站二");
        String token = staffToken(mgr, "STATION_MANAGER", station);
        Api res = post("/api/manager/my-products", token, "{\"name\":\"矿泉水\"}");
        assertTrue(!res.isSuccess(), "缺 price 应被拒: " + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM product"), "不应落库");
    }
}
