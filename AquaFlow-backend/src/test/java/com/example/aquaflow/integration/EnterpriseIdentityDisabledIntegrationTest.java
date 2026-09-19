package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业身份（v50）**开关默认关闭**时的行为 —— 「随时可开关」的另一半。
 *
 * <p>要求来自产品：「设置成随时可开关的模式，甲方不满意我能关了的模式」。关掉必须是**真的关**：</p>
 * <ol>
 *   <li>报价里**连字段都不下发**（前端因此没有任何入口）；</li>
 *   <li>申请端点**直接拒绝**（不是"藏起来但还能调"）；</li>
 *   <li>站长待审列表返回**空列表而非报错**（客户列表页不该冒一个"功能未开启"的红字）；</li>
 *   <li>审核端点同样拒绝。</li>
 * </ol>
 *
 * <p>本类不设任何属性覆盖，用的就是 {@code app.enterprise.enabled} 的默认值 false。</p>
 */
@DisplayName("企业身份（开关默认关）：不下发提示、端上一律拒绝、站长列表安静为空")
class EnterpriseIdentityDisabledIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long customer;
    private long address;
    private long priceyGoods;
    private String cus;
    private String mgrToken;

    private void seed() {
        station = createStation("关站");
        long mgr = createStaff("关站站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("关站客户", "ent-off-openid");
        address = createAddress(customer, "关站小区1号");
        priceyGoods = createProduct("关站大单商品", 2, "900.00", "0.00", 0, "0.00");
        createInventoryFull(station, priceyGoods, 100, 0, "0.00");
        createCustomerStationConfig(customer, station, 0);
        cus = customerToken(customer);
        mgrToken = staffToken(mgr, "STATION_MANAGER", station);
    }

    @Test
    @DisplayName("开关关着：大额报价不带提示、申请被拒、站长列表为空、审核被拒")
    void everythingIsOffWhenSwitchIsOff() {
        seed();
        // 900 元（远超阈值）也不提示 —— 因为开关关着
        JsonNode q = post("/api/payments/quote", cus, "{\"stationId\":" + station + ",\"paymentMethod\":2,"
                + "\"addressId\":" + address + ",\"items\":[{\"productId\":" + priceyGoods + ",\"quantity\":1}]}").data();
        assertTrue(q.path("enterpriseHint").isNull(), "开关关着时不该下发企业身份提示");

        Api applied = post("/api/enterprise/applications", cus, "{\"stationId\":" + station
                + ",\"companyName\":\"关了就不该能申请\"}");
        assertNotEquals(0, applied.code(), "开关关着时申请必须被拒（不能只是隐藏入口）");
        assertTrue(applied.message() != null && applied.message().contains("未开启"),
                "拒绝文案要说明功能未开启，实际=" + applied.message());
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_enterprise_apply WHERE customer_id=?", customer),
                "被拒的申请不得留痕");

        assertEquals(0, get("/api/enterprise/manager/applications", mgrToken).data().size(),
                "站长列表要安静地空着（不是报错）");
        assertNotEquals(0, put("/api/enterprise/manager/applications/1", mgrToken, "{\"approve\":true}").code(),
                "开关关着时审核也必须被拒");
        // 四个端点里最后一个（顾客端「我的申请」）：开关关着同样必须真拒绝 ——
        // 顾客端下单页拿它判断"已申请过"，若它漏关就会变成唯一可探测的入口
        assertNotEquals(0, get("/api/enterprise/applications/my?stationId=" + station, cus).code(),
                "开关关着时「我的申请」也必须被拒");

        // v51 的站级阈值两个端点：读**不报错**（回 enabled=false，前端据此把整块入口隐藏，
        // 客户列表页不该冒"功能未开启"的红字），写**必须真拒绝**（关掉的东西不允许被改）。
        Api cfg = get("/api/enterprise/manager/config", mgrToken);
        assertEquals(0, cfg.code(), "读配置不该报错: " + cfg);
        assertFalse(cfg.data().path("enabled").asBoolean(), "开关关着时配置接口要如实回 enabled=false");
        assertNotEquals(0, put("/api/enterprise/manager/config", mgrToken,
                "{\"barrelThreshold\":5,\"waterAmountThreshold\":null}").code(),
                "开关关着时改阈值必须被拒");
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_enterprise_config WHERE station_id=?", station),
                "被拒的配置不得留痕");
    }
}
