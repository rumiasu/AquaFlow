package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实 HTTP 和 SQL 验证回执权限与站长处置后的状态。测试基类会清库，须确认独占并备份测试库后才可运行。 */
class DeliveryProblemReceiptIntegrationTest extends AbstractIntegrationTest {
    @Test void onlyOwnReportInCurrentStationIsVisibleAndManagerDecisionRefreshes() {
        long a = createStation("回执站A"), b = createStation("回执站B");
        long reporter = createStaff("上报员", "DELIVERY", a, 1), other = createStaff("同站同事", "DELIVERY", a, 1);
        long manager = createStaff("站长", "STATION_MANAGER", a, 1);
        long customer = createCustomer("回执客户", "receipt-customer"), address = createAddress(customer, "回执地址");
        long product = createProduct("回执水", 1, "10.00", "0.00", 0, "0.00");
        long order = createOrder(customer, address, a, product, 2, 1);
        long outsideOrder = createOrder(customer, address, b, product, 2, 1);
        long own = row(order, customer, a, reporter, "DELIVERY_PROBLEM");
        row(order, customer, a, other, "DELIVERY_PROBLEM");
        row(outsideOrder, customer, b, reporter, "DELIVERY_PROBLEM");
        row(outsideOrder, customer, a, reporter, "DELIVERY_PROBLEM"); // 旧数据站点与当前履约站不一致
        row(order, customer, a, reporter, "RETURN_SHORT");
        String path = "/api/delivery/my-problem-receipts", token = staffToken(reporter, "DELIVERY", a);
        Api before = get(path, token);
        assertEquals(0, before.code()); assertEquals(1, before.data().size());
        assertEquals(own, before.data().get(0).path("id").asLong());
        assertEquals("待处理", before.data().get(0).path("statusText").asText());
        assertFalse(before.data().get(0).has("customerId")); assertFalse(before.data().get(0).has("managerNote"));
        assertFalse(get(path, customerToken(customer)).isSuccess());
        assertFalse(post("/api/manager/exceptions/" + own + "/handle", token, "{\"action\":\"IGNORE\"}").isSuccess());
        Api handled = post("/api/manager/exceptions/" + own + "/handle", staffToken(manager, "STATION_MANAGER", a),
                "{\"action\":\"IGNORE\",\"managerNote\":\"内部说明\"}");
        assertTrue(handled.isSuccess(), handled.toString());
        Api after = get(path, token); assertEquals("已忽略", after.data().get(0).path("statusText").asText());
        assertFalse(after.data().get(0).has("managerNote"));
        jdbc.update("UPDATE orders SET delivery_station_id=? WHERE id=?", b, order);
        assertEquals(0, get(path, token).data().size(), "转往他站后不再展示");
    }
    private long row(long order, long customer, long station, long staff, String type) {
        return insert("INSERT INTO order_barrel_exception(order_id,customer_id,station_id,delivery_staff_id,category,type,staff_note,status) VALUES (?,?,?,?,?,?,?,?)",
                order, customer, station, staff, "OTHER", type, "客户不接电话", "STAFF_RECORDED");
    }
}
