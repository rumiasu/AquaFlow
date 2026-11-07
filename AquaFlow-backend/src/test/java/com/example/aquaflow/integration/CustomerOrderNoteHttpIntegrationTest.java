package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 HTTP 序列化安全回归；测试基类会清库，须先确认独占且备份测试库。 */
class CustomerOrderNoteHttpIntegrationTest extends AbstractIntegrationTest {
    @Test void customerOrderAndNewTemplateOnlyReturnSnapshotButStaffRetainsInternalNotes() {
        long station = createStation("备注安全站"), manager = createStaff("备注站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("备注客户", "note-http-customer"), address = createAddress(customer, "备注地址");
        long product = createProduct("备注水", 1, "10.00", "0.00", 0, "0.00");
        // 员工详情遵守已付款/现金/终态可见性；此夹具用已付款微信单，不能靠未付款单绕过守卫。
        long order = createOrder(customer, address, station, product, 2, 2);
        jdbc.update("UPDATE orders SET payment_method=1, special_note=?, customer_note=?, barrel_discrepancy_note=? WHERE id=?",
                "请放门口\n[退回站长] 仅内部可见\n[配送异常] 地址找不到（上报人ID=9）", "请放门口", "内部差异", order);
        String token = customerToken(customer);
        for (Api response : new Api[] { get("/api/orders/" + order, token), get("/api/orders", token) }) {
            assertEquals(0, response.code());
            var view = response.data().isArray() ? response.data().get(0) : response.data();
            assertEquals("请放门口", view.path("specialNote").asText());
            assertEquals("STAFF", view.path("transferKind").asText());
            assertFalse(view.toString().contains("仅内部可见")); assertFalse(view.toString().contains("内部差异"));
            assertFalse(view.toString().contains("上报人ID"));
        }
        Api template = post("/api/order-templates/from-order?orderId=" + order + "&stationId=" + station, token, null);
        assertEquals(0, template.code()); assertEquals("请放门口", template.data().path("specialNote").asText());
        Api staff = get("/api/orders/" + order, staffToken(manager, "STATION_MANAGER", station));
        assertEquals(0, staff.code(), "员工详情应成功：" + staff);
        assertTrue(staff.data().path("specialNote").asText().contains("仅内部可见"));
        jdbc.update("UPDATE orders SET customer_note=NULL WHERE id=?", order);
        Api legacy = get("/api/orders/" + order, token);
        assertTrue(legacy.data().path("specialNote").isNull()); assertTrue(legacy.data().path("legacyNoteUnavailable").asBoolean());
        Api legacyTemplate = post("/api/order-templates/from-order?orderId=" + order + "&stationId=" + station, token, null);
        assertTrue(legacyTemplate.data().path("specialNote").isNull());
    }
}
