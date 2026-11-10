package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagerCrossStationCustomerProfileIntegrationTest extends AbstractIntegrationTest {
    private static final String REPORT = "/api/dashboard/report?range=7d";
    private static final String RECEIVABLES = "/api/manager/receivables";
    private static final String PENDING_PAYMENTS = "/api/payments/pending";
    private static final String PROFILE_NAME = "合成档案姓名";
    private static final String PROFILE_PHONE = "19900000002";
    private static final String CURRENT_ADDRESS = "合成现住地址";
    private static final String SNAPSHOT_ADDRESS = "合成下单地址";
    private static final String SNAPSHOT_RECEIVER = "合成订单收件人";
    private static final String SNAPSHOT_PHONE = "19900000001";

    private long receivingStation;
    private long customer;
    private long sameStationCustomer;
    private long crossStationOrder;
    private long sameStationOrder;
    private String receivingManager;
    private String unrelatedManager;

    @BeforeEach
    void seedOrders() {
        long ownerStation = createStation("合成 A 站");
        receivingStation = createStation("合成 B 站");
        long unrelatedStation = createStation("合成 C 站");
        receivingManager = staffToken(createStaff("B 站长", "STATION_MANAGER", receivingStation, 1),
                "STATION_MANAGER", receivingStation);
        unrelatedManager = staffToken(createStaff("C 站长", "STATION_MANAGER", unrelatedStation, 1),
                "STATION_MANAGER", unrelatedStation);

        customer = createCustomer(PROFILE_NAME, "boundary-cross-station");
        jdbc.update("update customer set phone=? where id=?", PROFILE_PHONE, customer);
        long address = createAddress(customer, CURRENT_ADDRESS);
        jdbc.update("update address set floor=17, has_elevator=0 where id=?", address);
        long product = createProduct("合成饮水机", 2, "48.00", "0.00", 0, "0.00");
        crossStationOrder = createOrderCrossStation(customer, address, ownerStation, receivingStation,
                product, 3, 1, 2, "48.00", "0.00", "48.00");
        jdbc.update("""
                update orders set settle_station_id=?, settlement_status=1, due_date=curdate()-interval 2 day,
                    receiver_name=?, receiver_phone=?, address_snapshot=?, address_snapshot_lat=23.1200000,
                    address_snapshot_lng=113.2600000
                where id=?
                """, receivingStation, SNAPSHOT_RECEIVER, SNAPSHOT_PHONE, SNAPSHOT_ADDRESS, crossStationOrder);

        sameStationCustomer = createCustomer("合成本站客户", "boundary-same-station");
        long sameStationAddress = createAddress(sameStationCustomer, "合成本站地址");
        jdbc.update("update address set floor=5, has_elevator=1 where id=?", sameStationAddress);
        sameStationOrder = createOrderFull(sameStationCustomer, sameStationAddress, receivingStation,
                product, 3, 1, 2, "25.00", "0.00", "25.00", false, 0);
    }

    @Test
    void aggregatesKeepAmountsAndOnlyNameCustomersWithTheirOwnStationRelationship() {
        createPaymentRecord(crossStationOrder, customer, receivingStation, "48.00", 2, 1);
        JsonNode rankings = success(get(REPORT, receivingManager)).path("topCustomers");
        JsonNode crossStationRanking = customerRow(rankings, customer);
        assertFalse(crossStationRanking.hasNonNull("customerName"), crossStationRanking.toString());
        assertEquals(1, crossStationRanking.path("orders").asInt());
        assertAmount("48.00", crossStationRanking, "amount");
        assertEquals("合成本站客户", customerRow(rankings, sameStationCustomer).path("customerName").asText());

        JsonNode overview = success(get(RECEIVABLES, receivingManager));
        assertAmount("73.00", overview, "outstandingAmount");
        assertAmount("48.00", overview, "overdueAmount");
        JsonNode crossStationReceivable = customerRow(overview.path("customers"), customer);
        assertFalse(crossStationReceivable.hasNonNull("customerName"), crossStationReceivable.toString());
        assertEquals(1, crossStationReceivable.path("orderCount").asInt());
        assertAmount("48.00", crossStationReceivable, "outstandingAmount");
        assertEquals("合成本站客户",
                customerRow(overview.path("customers"), sameStationCustomer).path("customerName").asText());

        JsonNode pending = customerRow(success(get(PENDING_PAYMENTS, receivingManager)), customer);
        assertAmount("48.00", pending, "amount");
        assertEquals(crossStationOrder, pending.path("orderId").asLong());
        assertFalse(pending.hasNonNull("customerName"), pending.toString());
        assertFalse(pending.hasNonNull("customerPhone"), pending.toString());
        assertEquals(0, success(get(REPORT, unrelatedManager)).path("topCustomers").size());
        assertEquals(0, success(get(RECEIVABLES, unrelatedManager)).path("customers").size());
        assertEquals(0, success(get(PENDING_PAYMENTS, unrelatedManager)).size());

        // 单独绑定也构成经营关系，即使该客户没有以 B 为归属站的订单。
        createCustomerStationConfig(customer, receivingStation, 1);
        assertEquals(PROFILE_NAME, customerRow(success(get(REPORT, receivingManager)).path("topCustomers"), customer)
                .path("customerName").asText());
        JsonNode boundOverview = success(get(RECEIVABLES, receivingManager));
        assertEquals(PROFILE_NAME, customerRow(boundOverview.path("customers"), customer).path("customerName").asText());
        assertAmount("73.00", boundOverview, "outstandingAmount");
        JsonNode boundPending = customerRow(success(get(PENDING_PAYMENTS, receivingManager)), customer);
        assertEquals(PROFILE_NAME, boundPending.path("customerName").asText());
        assertEquals(PROFILE_PHONE, boundPending.path("customerPhone").asText());
    }

    @Test
    void bothEmployeeDetailsKeepSnapshotsWithoutCurrentProfileOrAddress() {
        for (String route : List.of("/api/orders/", "/api/delivery/orders/")) {
            JsonNode order = success(get(route + crossStationOrder, receivingManager));
            assertEquals(crossStationOrder, order.path("id").asLong());
            assertEquals(SNAPSHOT_RECEIVER, order.path("receiverName").asText());
            assertEquals(SNAPSHOT_PHONE, order.path("receiverPhone").asText());
            assertEquals(SNAPSHOT_ADDRESS, order.path("addressSnapshot").asText());
            assertAmount("23.12", order, "addressSnapshotLat");
            assertAmount("113.26", order, "addressSnapshotLng");
            for (String field : List.of("customerName", "customerPhone", "addressId", "addressDetail",
                    "addressName", "addressPhone", "addressLat", "addressLng", "addressFloor", "addressHasElevator")) {
                assertFalse(order.hasNonNull(field), route + field);
            }

            Api denied = get(route + crossStationOrder, unrelatedManager);
            assertEquals(1, denied.code(), denied.toString());
            assertFalse(denied.body().hasNonNull("data"), denied.toString());

            JsonNode sameStation = success(get(route + sameStationOrder, receivingManager));
            assertEquals("合成本站客户", sameStation.path("customerName").asText());
            assertEquals("合成本站地址", sameStation.path("addressDetail").asText());
            if (route.equals("/api/delivery/orders/")) {
                assertEquals(5, sameStation.path("addressFloor").asInt());
                assertEquals(1, sameStation.path("addressHasElevator").asInt());
            }
        }

        // 无地址快照的旧单仍可打开，当前地址不可作为跨站回退。
        jdbc.update("update orders set address_snapshot=null where id=?", crossStationOrder);
        JsonNode legacy = success(get("/api/delivery/orders/" + crossStationOrder, receivingManager));
        assertFalse(legacy.hasNonNull("addressSnapshot"));
        assertFalse(legacy.hasNonNull("addressDetail"));
    }

    @Test
    void settlementStationCanStillRegisterCashAndSettle() {
        String orderList = RECEIVABLES + "/orders?customerId=" + customer;
        JsonNode rows = success(get(orderList, receivingManager));
        assertEquals(1, rows.size());
        JsonNode row = rows.get(0);
        assertEquals(crossStationOrder, row.path("orderId").asLong());
        assertTrue(row.path("canRegisterCash").asBoolean(), row.toString());
        assertTrue(row.path("canViewOrder").asBoolean(), row.toString());
        assertAmount("48.00", row, "totalAmount");
        assertEquals(0, success(get(orderList, unrelatedManager)).size());

        String body = "{\"customerId\":" + customer + ",\"orderIds\":[" + crossStationOrder + "]}";
        JsonNode settled = success(post(RECEIVABLES + "/settle", receivingManager, body));
        assertEquals(1, settled.path("collectedCount").asInt());
        assertEquals(1, settled.path("settledCount").asInt());
        assertAmount("48.00", settled, "settledAmount");
        assertEquals(0, success(get(orderList, receivingManager)).size());
        assertEquals(1, intOf("select count(*) from payment_record where order_id=? and station_id=? and status=2",
                crossStationOrder, receivingStation));
    }

    private static JsonNode success(Api response) {
        assertEquals(200, response.status(), response.toString());
        assertEquals(0, response.code(), response.toString());
        return response.data();
    }

    private static JsonNode customerRow(JsonNode rows, long id) {
        for (JsonNode row : rows) {
            if (row.path("customerId").asLong() == id) return row;
        }
        throw new AssertionError("No customer row " + id + " in " + rows);
    }

    private static void assertAmount(String expected, JsonNode row, String field) {
        assertTrue(row.path(field).isNumber(), row.toString());
        assertEquals(0, new BigDecimal(expected).compareTo(row.path(field).decimalValue()), row.toString());
    }
}
