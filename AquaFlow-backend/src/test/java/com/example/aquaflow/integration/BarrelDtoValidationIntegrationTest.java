package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D-B 契约测试：桶/押金写路径改为强类型 DTO + Bean Validation 后，
 * 非法入参必须在边界被拒（code != 0）且不落库。覆盖：
 *  - POST /api/barrels/return（退桶申请）
 *  - POST /api/barrels/return-empty（纯还桶）
 *  - PUT /api/customers/{id}/offline-payment（线下支付授权）
 */
class BarrelDtoValidationIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("D-B · POST /api/barrels/return 缺 productId 被 Bean Validation 拒回，且不落桶记录")
    void returnMissingProductIdRejected() {
        long customerId = createCustomer("C-B1", "openid-cb1");
        String token = customerToken(customerId);

        Api api = post("/api/barrels/return", token, "{\"quantity\":2}");

        assertFalse(api.isSuccess(), "缺 productId 应被拒: " + api);
        assertEquals(0, intOf("SELECT COUNT(*) FROM barrel_record"), "非法退桶申请不应落桶记录");
    }

    @Test
    @DisplayName("D-B · POST /api/barrels/return quantity=0 被 @Min(1) 拒回")
    void returnZeroQuantityRejected() {
        long customerId = createCustomer("C-B2", "openid-cb2");
        String token = customerToken(customerId);

        Api api = post("/api/barrels/return", token, "{\"productId\":1,\"quantity\":0}");

        assertFalse(api.isSuccess(), "quantity<1 应被拒: " + api);
        assertEquals(0, intOf("SELECT COUNT(*) FROM barrel_record"), "非法退桶申请不应落桶记录");
    }

    @Test
    @DisplayName("D-B · POST /api/barrels/return-empty 缺 customerId 被拒，且不落纯还桶流水")
    void returnEmptyMissingCustomerRejected() {
        long station = createStation("ST-B3");
        long mgr = createStaff("M-B3", "STATION_MANAGER", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);

        Api api = post("/api/barrels/return-empty", token,
                "{\"clientToken\":\"tok-x\",\"items\":[{\"productId\":1,\"qty\":1}]}");

        assertFalse(api.isSuccess(), "缺 customerId 应被拒: " + api);
        assertEquals(0, intOf("SELECT COUNT(*) FROM barrel_record WHERE type=7"),
                "非法纯还桶不应落 type=7 流水");
    }

    @Test
    @DisplayName("D-B · POST /api/barrels/return-empty 缺 clientToken 被拒")
    void returnEmptyMissingTokenRejected() {
        long station = createStation("ST-B4");
        long mgr = createStaff("M-B4", "STATION_MANAGER", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);

        Api api = post("/api/barrels/return-empty", token,
                "{\"customerId\":1,\"items\":[{\"productId\":1,\"qty\":1}]}");

        assertFalse(api.isSuccess(), "缺 clientToken 应被拒: " + api);
        // [F-31 2026-09-30] 原来只断言 !isSuccess —— customerId=1 在空库里不存在，
        // 「客户不存在」同样会拒，把 clientToken 的 @NotNull 摘掉这条用例照样绿。现钉住文案。
        assertTrue(api.message() != null && api.message().contains("缺少幂等 token"),
                "拒绝必须点名缺的是 clientToken，实际=" + api.message());
    }

    @Test
    @DisplayName("D-B · POST /api/barrels/return-empty 空 items 被 @NotEmpty 拒回")
    void returnEmptyEmptyItemsRejected() {
        long station = createStation("ST-B5");
        long mgr = createStaff("M-B5", "STATION_MANAGER", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);

        Api api = post("/api/barrels/return-empty", token,
                "{\"customerId\":1,\"clientToken\":\"tok-y\",\"items\":[]}");

        assertFalse(api.isSuccess(), "空 items 应被拒: " + api);
        // [F-31 2026-09-30] 同上：customerId=1 不存在会先被业务层挡下，光看"不成功"分不清
        // 是 @NotEmpty 生效还是走了别的分支。现钉住字段级文案。
        assertTrue(api.message() != null && api.message().contains("请填写还桶明细"),
                "拒绝必须来自 items 的 @NotEmpty，实际=" + api.message());
    }

    @Test
    @DisplayName("D-B · PUT /api/customers/{id}/offline-payment 缺 offlinePaymentEnabled 被拒，且不改授权")
    void offlinePaymentMissingFlagRejected() {
        long station = createStation("ST-B6");
        long mgr = createStaff("M-B6", "STATION_MANAGER", station, 1);
        long customer = createCustomer("C-B6", "openid-cb6");
        createCustomerStationConfig(customer, station, 0);
        String token = staffToken(mgr, "STATION_MANAGER", station);

        Api api = put("/api/customers/" + customer + "/offline-payment", token, "{}");

        assertFalse(api.isSuccess(), "缺 offlinePaymentEnabled 应被拒: " + api);
        assertEquals(0, intOf("SELECT offline_payment_enabled FROM customer_station_config "
                        + "WHERE customer_id=? AND station_id=?", customer, station),
                "被拒请求不得改写线下支付授权");
    }
}
