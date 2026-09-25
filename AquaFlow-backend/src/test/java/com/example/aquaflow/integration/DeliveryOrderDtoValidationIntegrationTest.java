package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase D-C2 契约测试：配送端订单状态变更写路径的强类型边界校验。
 *
 * <p>断言：非法的写体被 {@code @Valid} 在边界拒回（code != 0），且订单状态/配送员等不被错误改写。</p>
 */
class DeliveryOrderDtoValidationIntegrationTest extends AbstractIntegrationTest {

    private static final class Ctx {
        long order;
        String mgrToken;
    }

    /** 建一个水站 + 站长 + 顾客 + 订单（订单归属该站，便于越权/归属校验通过） */
    private Ctx setup() {
        long station = createStation("测试水站-DC2");
        long mgr = createStaff("站长", "STATION_MANAGER", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);
        long customer = createCustomer("顾客", "openid-dc2");
        long addr = createAddress(customer, "地址-DC2");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 0, "0.00");
        long order = createOrder(customer, addr, station, product, 1, 1);
        Ctx c = new Ctx();
        c.order = order;
        c.mgrToken = token;
        return c;
    }

    // ---- dispatch：targetStationId 必填 ----
    @Test
    void dispatchMissingTargetStationRejected() {
        Ctx c = setup();
        Api res = post("/api/delivery/orders/" + c.order + "/dispatch", c.mgrToken, "{\"reason\":\"外派配送\"}");
        assertFalse(res.isSuccess(), "缺 targetStationId 应被边界拒回，实际=" + res);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", c.order),
                "被拒后订单状态不应改变（仍为待配送 1）");
    }

    // ---- resolve：reason 必填 ----
    @Test
    void resolveMissingReasonRejected() {
        Ctx c = setup();
        Api res = post("/api/delivery/orders/" + c.order + "/resolve", c.mgrToken, "{}");
        assertFalse(res.isSuccess(), "缺 reason 应被边界拒回，实际=" + res);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", c.order), "被拒后订单状态不应改变");
    }

    // ---- assign：deliveryStaffId 必填 ----
    @Test
    void assignMissingStaffRejected() {
        Ctx c = setup();
        Api res = post("/api/delivery/orders/" + c.order + "/assign", c.mgrToken, "{}");
        assertFalse(res.isSuccess(), "缺 deliveryStaffId 应被边界拒回，实际=" + res);
        assertEquals(0L, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", c.order),
                "被拒后不应写入配送员");
    }

    // ---- claim-pool：deliveryStaffId 必填 ----
    @Test
    void claimPoolMissingStaffRejected() {
        Ctx c = setup();
        Api res = post("/api/delivery/orders/" + c.order + "/claim-pool", c.mgrToken, "{}");
        assertFalse(res.isSuccess(), "缺 deliveryStaffId 应被边界拒回，实际=" + res);
        assertEquals(0L, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", c.order),
                "被拒后不应写入配送员");
    }

    // ---- transfer：deliveryStaffId 必填 ----
    @Test
    void transferMissingStaffRejected() {
        Ctx c = setup();
        Api res = post("/api/delivery/orders/" + c.order + "/transfer", c.mgrToken, "{}");
        assertFalse(res.isSuccess(), "缺 deliveryStaffId 应被边界拒回，实际=" + res);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", c.order), "被拒后订单状态不应改变");
    }

    // ---- complete：itemReturns 中 orderItemId 必填 ----
    @Test
    void completeItemReturnMissingOrderItemIdRejected() {
        Ctx c = setup();
        Api res = post("/api/delivery/orders/" + c.order + "/complete", c.mgrToken,
                "{\"itemReturns\":[{\"actual\":1}]}");
        assertFalse(res.isSuccess(), "回桶明细缺 orderItemId 应被边界拒回，实际=" + res);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", c.order), "被拒后订单状态不应改变");
    }

    // ---- 正常放行：complete 合法空体（首桶订单免回桶核对）应通过 @Valid 边界到达 service ----
    @Test
    void completeValidEmptyBodySucceeds() {
        long station = createStation("测试水站-DC2c");
        long mgr = createStaff("站长", "STATION_MANAGER", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);
        long customer = createCustomer("顾客", "openid-dc2c");
        long addr = createAddress(customer, "地址-DC2c");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 0, "0.00");
        long order = createOrderFull(customer, addr, station, product,
                2 /* 配送中 */, 0, 2 /* 现金 */, "20.00", "30.00", "50.00", true, 1);
        Api res = post("/api/delivery/orders/" + order + "/complete", token, "{}");
        assertTrue(res.isSuccess(), "complete 合法空体应通过 @Valid 边界并落库，实际=" + res);
    }
}
