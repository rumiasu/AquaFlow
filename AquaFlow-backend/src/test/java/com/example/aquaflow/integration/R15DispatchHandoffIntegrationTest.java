package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Real RANDOM_PORT HTTP and MySQL; run only with the existing exact-target reset guards. */
@TestPropertySource(properties = "aquaflow.barrel.independent-rights-enabled=true")
class R15DispatchHandoffIntegrationTest extends AbstractIntegrationTest {
    private long a, b, managerA, managerB, driverA, driverB, product, customer, address;
    private void seed() {
        a = createStation("R15 A"); b = createStation("R15 B");
        product = createProduct("R15 瓶装水", 2, "20.00", "0.00", 0, "20.00");
        createInventoryFull(a, product, 20, 0, "20.00"); createInventoryFull(b, product, 20, 0, "20.00");
        customer = createCustomer("R15 合成客户", "r15-synthetic-openid"); address = createAddress(customer, "R15 测试地址");
        managerA = createStaff("R15 MA", "STATION_MANAGER", a, 1); managerB = createStaff("R15 MB", "STATION_MANAGER", b, 1);
        driverA = createStaff("R15 DA", "DELIVERY", a, 1); driverB = createStaff("R15 DB", "DELIVERY", b, 1);
    }
    private long order() { return createOrderFull(customer, address, a, product, 1, 1, 2, "40.00", "0.00", "40.00", false, 0); }
    private String ma() { return staffToken(managerA, "STATION_MANAGER", a); }
    private String mb() { return staffToken(managerB, "STATION_MANAGER", b); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryPoolOfferExistsAndClaimFreezesItWithoutMovingCustomerAssets(boolean rejectEntrance) {
        seed(); long id = order();
        String route = rejectEntrance ? "/api/delivery/orders/" + id + "/station-reject" : "/api/delivery/orders/transfer/" + id + "/outsource";
        var put = post(route, ma(), rejectEntrance ? "{\"tryDispatch\":true,\"reason\":\"R15 本站无法配送\"}" : "{}");
        assertTrue(put.isSuccess(), "入池应成功: " + put);
        assertEquals(1, longOf("select count(*) from dispatch_agreement where order_id=? and status='OFFERED' and target_station_id is null", id));
        assertEquals("40.00", jdbc.queryForObject("select service_amount from dispatch_agreement where order_id=?", java.math.BigDecimal.class, id).toPlainString());
        assertTrue(post("/api/delivery/orders/" + id + "/claim-pool", mb(), "{\"deliveryStaffId\":" + driverB + "}").isSuccess());
        assertEquals(a, longOf("select station_id from orders where id=?", id));
        assertEquals(b, longOf("select settle_station_id from orders where id=?", id));
        assertEquals(1, longOf("select count(*) from dispatch_agreement where order_id=? and status='ACCEPTED' and target_station_id=?", id, b));
    }

    @Test void poolToDirectedAndDirectedToPoolUseTheLatestRouteAndPreserveExplicitQuote() {
        seed(); long id = order();
        assertTrue(post("/api/delivery/orders/transfer/" + id + "/outsource", ma(), "{\"targetStationId\":" + b + "}").isSuccess());
        assertTrue(put("/api/manager/dispatch-agreements/" + id, ma(), "{\"serviceAmount\":37.50,\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0,\"note\":\"R15 明确报价\"}").isSuccess());
        assertTrue(contains(get("/api/delivery/orders/directed-incoming", mb()), id));
        assertTrue(post("/api/delivery/orders/transfer/" + id + "/outsource", ma(), "{}").isSuccess());
        assertTrue(post("/api/delivery/orders/" + id + "/claim-pool", mb(), "{\"deliveryStaffId\":" + driverB + "}").isSuccess());
        assertFalse(contains(get("/api/delivery/orders/directed-incoming", mb()), id));
        assertEquals("37.50", jdbc.queryForObject("select service_amount from dispatch_agreement where order_id=?", java.math.BigDecimal.class, id).toPlainString());
        long another = order();
        assertTrue(post("/api/delivery/orders/transfer/" + another + "/outsource", ma(), "{}").isSuccess());
        assertTrue(post("/api/delivery/orders/transfer/" + another + "/outsource", ma(), "{\"targetStationId\":" + b + "}").isSuccess());
        assertTrue(contains(get("/api/delivery/orders/directed-incoming", mb()), another));
    }

    @Test void twelveRepeatedHandoffsCannotWriteMoreNotesOrAuditRows() {
        seed(); long id = order();
        jdbc.update("update orders set status=2,delivery_staff_id=? where id=?", driverA, id);
        String from = staffToken(driverA, "DELIVERY", a);
        String target = staffToken(createStaff("R15 DA2", "DELIVERY", a, 1), "DELIVERY", a);
        long targetId = longOf("select id from staff where name='R15 DA2'");
        assertTrue(post("/api/delivery/orders/transfer/" + id, from, "{\"deliveryStaffId\":" + targetId + ",\"reason\":\"R15 请同事接手\"}").isSuccess());
        assertTrue(post("/api/delivery/orders/transfer/" + id + "/claim", target, "{}").isSuccess());
        String note = jdbc.queryForObject("select special_note from orders where id=?", String.class, id);
        long audits = longOf("select count(*) from audit_log");
        for (int i = 0; i < 12; i++) assertFalse(post("/api/delivery/orders/transfer/" + id + "/claim", target, "{}").isSuccess());
        assertEquals(note, jdbc.queryForObject("select special_note from orders where id=?", String.class, id));
        assertEquals(audits, longOf("select count(*) from audit_log"));
        assertEquals(targetId, longOf("select delivery_staff_id from orders where id=?", id));
        assertEquals(2, longOf("select status from orders where id=?", id));
    }
    private boolean contains(Api response, long id) {
        assertTrue(response.isSuccess());
        for (var row : response.data()) if (row.path("id").asLong() == id) return true;
        return false;
    }
}
