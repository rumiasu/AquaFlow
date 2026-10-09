package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/MySQL regressions for current binding, task ownership and cross-station read boundaries. */
class OrderTaskAccessIntegrationTest extends AbstractIntegrationTest {
    private long station, otherStation, manager, otherManager, driver, colleague, foreignDriver;
    private long customer, address, product;
    private String mgr, otherMgr, del, otherDel, foreignDel, cus;

    private void seed() {
        station = createStation("订单边界本站");
        otherStation = createStation("订单边界他站");
        manager = createStaff("本站站长", "STATION_MANAGER", station, 1);
        otherManager = createStaff("他站站长", "STATION_MANAGER", otherStation, 1);
        driver = createStaff("本站配送甲", "DELIVERY", station, 1);
        colleague = createStaff("本站配送乙", "DELIVERY", station, 1);
        foreignDriver = createStaff("他站配送员", "DELIVERY", otherStation, 1);
        customer = createCustomer("客户档案姓名", "order-task-access-customer");
        jdbc.update("update customer set phone='13800001111' where id=?", customer);
        address = createAddress(customer, "收件地址快照");
        product = createProduct("瓶装水", 2, "10.00", "0.00", 0, "0.00");
        createInventory(station, product, 100);
        createInventory(otherStation, product, 100);
        mgr = staffToken(manager, "STATION_MANAGER", station);
        otherMgr = staffToken(otherManager, "STATION_MANAGER", otherStation);
        del = staffToken(driver, "DELIVERY", station);
        otherDel = staffToken(colleague, "DELIVERY", station);
        foreignDel = staffToken(foreignDriver, "DELIVERY", otherStation);
        cus = customerToken(customer);
    }

    private long task(long owner, long fulfillment, Long assignee, int state, int payState, int method) {
        long id = createOrderCrossStation(customer, address, owner, fulfillment, product,
                state, payState, method, "10.00", "0.00", "10.00");
        jdbc.update("update orders set delivery_staff_id=?, receiver_name='履约收件人', "
                + "receiver_phone='13900002222', address_snapshot='收件地址快照' where id=?", assignee, id);
        return id;
    }

    private JsonNode row(JsonNode rows, long id) {
        if (rows != null && rows.isArray()) for (JsonNode r : rows) if (r.path("id").asLong() == id) return r;
        return null;
    }

    private void assertMasked(JsonNode order) {
        assertNotNull(order);
        assertTrue(order.path("customerName").isNull(), order.toString());
        assertTrue(order.path("customerPhone").isNull(), order.toString());
        assertEquals("履约收件人", order.path("receiverName").asText());
        assertEquals("13900002222", order.path("receiverPhone").asText());
        assertEquals("收件地址快照", order.path("addressSnapshot").asText());
    }

    private void assertDenied(Api response) { assertEquals(1, response.code(), response.toString()); }
    private int state(long id) { return intOf("select status from orders where id=?", id); }
    private long assignee(long id) { return longOf("select delivery_staff_id from orders where id=?", id); }

    private int customerCancelCount(String token) {
        Api summary = get("/api/manager/pending-summary", token);
        assertEquals(0, summary.code(), summary.toString());
        for (JsonNode item : summary.data().path("items")) {
            if ("customerCancel".equals(item.path("key").asText())) {
                assertTrue(item.path("available").asBoolean(), item.toString());
                return item.path("count").asInt(-1);
            }
        }
        fail("customer cancellation metric missing: " + summary);
        return -1;
    }

    @Test void genericDriverListCannotReturnOtherAssignedOrUnassignedTasks() {
        seed();
        long own = task(station, station, driver, 1, 1, 2);
        long theirs = task(station, station, colleague, 1, 1, 2);
        long unassigned = task(station, station, null, 1, 1, 2);
        Api list = get("/api/orders", del);
        assertEquals(0, list.code(), list.toString());
        assertNotNull(row(list.data(), own));
        assertNull(row(list.data(), theirs), "another driver's assigned task must not be returned");
        assertNull(row(list.data(), unassigned), "an unassigned task must not be returned to a driver");
        assertDenied(get("/api/orders/" + theirs, del));
        assertDenied(get("/api/delivery/orders/" + unassigned, del));
    }

    @Test void genericListsAndBothDetailsRespectCurrentAssignedTask() {
        seed();
        long own = task(station, station, driver, 1, 1, 2);
        long inbound = task(otherStation, station, driver, 1, 1, 2);
        long theirs = task(station, station, colleague, 1, 1, 2);
        long unassigned = task(station, station, null, 1, 1, 2);
        long outbound = task(station, otherStation, foreignDriver, 1, 1, 2);
        Api mine = get("/api/orders?stationId=" + otherStation + "&pageSize=1", del);
        assertEquals(0, mine.code(), mine.toString());
        assertEquals(1, mine.data().size(), "SQL pagination must happen after authorization filtering");
        Api allMine = get("/api/orders", del);
        assertEquals(0, allMine.code());
        assertNotNull(row(allMine.data(), own));
        assertMasked(row(allMine.data(), inbound));
        for (long id : List.of(theirs, unassigned, outbound)) {
            assertNull(row(allMine.data(), id));
            assertDenied(get("/api/orders/" + id, del));
            assertDenied(get("/api/delivery/orders/" + id, del));
        }
        assertEquals(0, get("/api/orders/" + own, del).code());
        assertMasked(get("/api/orders/" + inbound, del).data());
        assertMasked(get("/api/delivery/orders/" + inbound, del).data());
        assertMasked(get("/api/orders/" + inbound, mgr).data());
        assertEquals(0, get("/api/orders/" + unassigned, mgr).code());
        assertEquals(0, get("/api/orders/" + theirs, cus).code(), "customer can read their own order");
        assertDenied(get("/api/orders/" + own, foreignDel));
    }

    @Test void unpaidActiveDetailCannotBypassEmployeeVisibilityButCustomerAndHistoryRemainReadable() {
        seed();
        long unpaid = task(station, station, driver, 1, 0, 1);
        long cancelled = task(station, station, driver, 5, 4, 1);
        for (String employee : List.of(mgr, del)) {
            assertDenied(get("/api/orders/" + unpaid, employee));
            assertDenied(get("/api/delivery/orders/" + unpaid, employee));
            assertNull(row(get("/api/orders", employee).data(), unpaid));
            assertEquals(0, get("/api/orders/" + cancelled, employee).code());
        }
        assertNull(row(get("/api/delivery/orders/assigned-to-me", del).data(), unpaid));
        assertEquals(0, get("/api/orders/" + unpaid, cus).code());
    }

    @Test void deliveredUnpaidUsesCurrentStationAndDriversOwnTasksIncludingInboundSnapshot() {
        seed();
        long own = task(station, station, driver, 3, 1, 2);
        long inbound = task(otherStation, station, driver, 3, 1, 2);
        long theirs = task(station, station, colleague, 3, 1, 2);
        long outbound = task(station, otherStation, foreignDriver, 3, 1, 2);
        Api mine = get("/api/delivery/orders/delivered-unpaid", del);
        assertEquals(0, mine.code());
        assertNotNull(row(mine.data(), own));
        assertMasked(row(mine.data(), inbound));
        assertNull(row(mine.data(), theirs));
        assertNull(row(mine.data(), outbound));
        Api stationRows = get("/api/delivery/orders/delivered-unpaid", mgr);
        assertNotNull(row(stationRows.data(), theirs));
        assertMasked(row(stationRows.data(), inbound));
        assertNull(row(stationRows.data(), outbound));
    }

    @Test void claimRequiresAnActualTransferAndCannotSelfAssignAnUnassignedOrder() {
        seed();
        long id = task(station, station, null, 1, 1, 2);
        for (String employee : List.of(del, mgr)) {
            assertDenied(post("/api/delivery/orders/transfer/" + id + "/claim", employee, "{}"));
        }
        assertEquals(1, state(id));
        assertEquals(1, intOf("select count(*) from orders where id=? and delivery_staff_id is null", id));
        assertEquals(0, intOf("select count(*) from audit_log where module='ORDER' and action='CLAIM' and target=?", "order:" + id));
        assertEquals(0, post("/api/delivery/orders/assign/" + id, mgr, "{\"deliveryStaffId\":" + driver + "}").code());
        assertEquals(0, post("/api/delivery/orders/" + id + "/accept", del, "{}").code());
    }

    @Test void actualTargetedTransferStillWorksOnceWithoutChangingTheDeliveryState() {
        seed();
        long id = task(station, station, driver, 2, 1, 2);
        assertEquals(0, post("/api/delivery/orders/transfer/" + id, del,
                "{\"deliveryStaffId\":" + colleague + "}").code());
        assertDenied(post("/api/delivery/orders/transfer/" + id + "/claim", mgr, "{}"));
        assertDenied(post("/api/delivery/orders/transfer/" + id + "/claim", foreignDel, "{}"));
        assertEquals(driver, assignee(id));
        assertEquals(0, post("/api/delivery/orders/transfer/" + id + "/claim", otherDel, "{}").code());
        assertEquals(colleague, assignee(id));
        assertEquals(2, state(id));
        assertDenied(post("/api/delivery/orders/transfer/" + id + "/claim", otherDel, "{}"));
        assertEquals(1, intOf("select count(*) from order_transfer where order_id=? and status='APPROVED'", id));
    }

    @Test void pendingTasksOfAnotherDriverCannotBeRejectedResolvedOrDispatched() {
        seed();
        for (Long assigned : new Long[]{colleague, null}) {
            long id = task(station, station, assigned, 1, 1, 2);
            assertDenied(post("/api/delivery/orders/reject/" + id, del, "{\"reason\":\"无法配送\"}"));
            assertDenied(post("/api/delivery/orders/" + id + "/resolve", del, "{\"reason\":\"无法配送\"}"));
            assertDenied(post("/api/delivery/orders/" + id + "/dispatch", del,
                    "{\"targetStationId\":" + otherStation + "}"));
            assertDenied(post("/api/delivery/orders/transfer/" + id + "/outsource", del,
                    "{\"targetStationId\":" + otherStation + "}"));
            assertEquals(1, state(id));
            assertEquals(station, longOf("select delivery_station_id from orders where id=?", id));
            assertEquals(0, intOf("select count(*) from payment_record where order_id=?", id));
            assertEquals(0, intOf("select count(*) from audit_log where module='ORDER' and target=?", "order:" + id));
        }
        long own = task(station, station, driver, 1, 1, 2);
        assertEquals(0, post("/api/delivery/orders/reject/" + own, del, "{\"reason\":\"无法配送\"}").code());
        assertEquals(5, state(own));
        long dispatchable = task(station, station, driver, 1, 1, 2);
        assertEquals(0, post("/api/delivery/orders/" + dispatchable + "/dispatch", del,
                "{\"targetStationId\":" + otherStation + "}").code());
    }

    @Test void releasedDriverWithOldTokenCannotAcceptOrReadOldActiveTasksAndRebindingDoesNotRestoreThem() {
        seed();
        long pending = task(station, station, driver, 1, 1, 2);
        long delivering = task(station, station, driver, 2, 1, 2);
        assertEquals(0, post("/api/manager/bind/release", mgr, "{\"staffId\":" + driver + "}").code());
        assertDenied(post("/api/delivery/orders/" + pending + "/accept", del, "{}"));
        for (String path : List.of("/api/orders", "/api/delivery/orders/assigned-to-me", "/api/delivery/orders/delivering",
                "/api/delivery/orders/delivered-unpaid")) assertDenied(get(path, del));
        assertEquals(1, state(pending));
        assertEquals(2, state(delivering));
        // Fixture rebinding: the request still carries the old JWT station and must use refreshed DB identity.
        jdbc.update("update staff set station_id=? where id=?", otherStation, driver);
        long newTask = task(otherStation, otherStation, driver, 1, 1, 2);
        assertNull(row(get("/api/delivery/orders/assigned-to-me", del).data(), pending));
        assertNotNull(row(get("/api/delivery/orders/assigned-to-me", del).data(), newTask));
        assertNull(row(get("/api/delivery/orders/delivering", del).data(), delivering));
        assertDenied(get("/api/orders/" + pending, del));
        assertDenied(post("/api/delivery/orders/" + pending + "/accept", del, "{}"));
        assertEquals(0, post("/api/delivery/orders/" + newTask + "/accept", del, "{}").code());
    }

    @Test void riskyDirectAcceptRequiresExistingReceivingStationAssignmentConfirmation() {
        seed();
        long barrelProduct = createProduct("风险桶装水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, barrelProduct, 20);
        createInventory(otherStation, barrelProduct, 20);
        long id = createOrderFull(customer, address, station, barrelProduct,
                1, 1, 2, "10.00", "30.00", "40.00", true, 1);
        assertEquals(0, post("/api/delivery/orders/" + id + "/dispatch", mgr,
                "{\"targetStationId\":" + otherStation + ",\"riskAcknowledged\":true}").code());
        assertDenied(post("/api/delivery/orders/" + id + "/accept", otherMgr, "{}"));
        assertEquals(1, state(id));
        assertEquals(1, intOf("select count(*) from orders where id=? and delivery_staff_id is null", id));
        assertEquals(0, intOf("select count(*) from audit_log where module='ORDER' and action='ACCEPT' and target=?", "order:" + id));
        assertDenied(post("/api/delivery/orders/assign/" + id, otherMgr,
                "{\"deliveryStaffId\":" + otherManager + "}"));
        assertEquals(0, post("/api/delivery/orders/assign/" + id, otherMgr,
                "{\"deliveryStaffId\":" + otherManager + ",\"riskAcknowledged\":true}").code());
        assertEquals(0, post("/api/delivery/orders/" + id + "/accept", otherMgr, "{}").code());
        assertEquals(2, state(id));
        assertTrue(jdbc.queryForObject("select special_note from orders where id=?", String.class, id)
                .contains("接收站=" + otherStation));
    }

    @Test void plainUnassignedOrdersStillAllowManagerSelfAcceptanceIncludingCrossStation() {
        seed();
        long local = task(station, station, null, 1, 1, 2);
        assertEquals(0, post("/api/delivery/orders/" + local + "/accept", mgr, "{}").code());
        long cross = task(station, station, null, 1, 1, 2);
        assertEquals(0, post("/api/delivery/orders/" + cross + "/dispatch", mgr,
                "{\"targetStationId\":" + otherStation + "}").code());
        assertEquals(0, post("/api/delivery/orders/" + cross + "/accept", otherMgr, "{}").code());
        assertEquals(otherManager, assignee(cross));
        assertEquals(2, state(cross));
    }

    @Test void crossStationCustomerCancellationAppearsOnlyAtAuthorizedFulfillmentStationWithMaskedProfile() {
        seed();
        long id = task(station, otherStation, foreignDriver, 2, 1, 2);
        assertEquals(0, put("/api/orders/" + id + "/customer-cancel", cus, null).code());
        Api origin = get("/api/delivery/orders/pending-approvals", mgr);
        Api fulfillment = get("/api/delivery/orders/pending-approvals", otherMgr);
        assertEquals(0, origin.code());
        assertEquals(0, fulfillment.code());
        assertNull(row(origin.data().path("customer"), id));
        assertMasked(row(fulfillment.data().path("customer"), id));
        assertEquals(0, customerCancelCount(mgr));
        assertEquals(1, customerCancelCount(otherMgr));
        assertDenied(post("/api/delivery/orders/cancel-request/" + id + "/reject", mgr, "{}"));
        assertEquals(0, post("/api/delivery/orders/cancel-request/" + id + "/reject", otherMgr, "{}").code());
        assertEquals(2, state(id));
        assertNull(row(get("/api/delivery/orders/pending-approvals", otherMgr).data().path("customer"), id));
        assertEquals(0, customerCancelCount(otherMgr));
        assertEquals("REJECTED", get("/api/orders/" + id, cus).data().path("customerCancelRequest").path("status").asText());
        assertEquals(0, put("/api/orders/" + id + "/customer-cancel", cus, null).code());
        assertEquals(0, post("/api/delivery/orders/cancel-request/" + id + "/approve", otherMgr, "{}").code());
        assertEquals(5, state(id));
    }

    @Test void acceptingRequestBlockedBehindReassignmentMustValidateTheNewAssignee() throws Exception {
        seed();
        long id = task(station, station, driver, 1, 1, 2);
        var pool = Executors.newSingleThreadExecutor();
        try (Connection blocker = openRawTransaction()) {
            lockRowsRaw(blocker, "select id from orders where id=? for update", id);
            long transaction = rawTrxId(blocker);
            var staleAccept = pool.submit(() -> post("/api/delivery/orders/" + id + "/accept", del, "{}"));
            assertTrue(awaitBlockedBy(transaction, "orders", 1, 5000) >= 1,
                    "the exact HTTP request must be waiting on this order lock before reassignment");
            // Execute the existing assignment CAS predicate inside the competing transaction holding this row.
            assertEquals(1, executeRaw(blocker, "update orders set delivery_staff_id=? where id=? and status=1", colleague, id));
            blocker.commit();
            assertDenied(staleAccept.get(10, TimeUnit.SECONDS));
            assertEquals(1, state(id));
            assertEquals(colleague, assignee(id));
            assertEquals(0, intOf("select count(*) from audit_log where module='ORDER' and action='ACCEPT' and target=?", "order:" + id));
            assertEquals(0, post("/api/delivery/orders/" + id + "/accept", otherDel, "{}").code());
            assertDenied(post("/api/delivery/orders/assign/" + id, mgr, "{\"deliveryStaffId\":" + driver + "}"));
            assertEquals(colleague, assignee(id));
        } finally { pool.shutdownNow(); }
    }
}
