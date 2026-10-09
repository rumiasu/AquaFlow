package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Directed return preserves actual acceptance facts; only approval moves the order back for reassignment. */
class DirectedReturnStateIntegrationTest extends AbstractIntegrationTest {
    private long a, b, c, ma, mb, driver, colleague, customer, address, product;
    private String origin, receiver, delivery, cus;

    private void seed() {
        a=createStation("退回原站"); b=createStation("退回履约站"); c=createStation("第三站");
        ma=createStaff("原站站长", "STATION_MANAGER", a, 1);
        mb=createStaff("履约站站长", "STATION_MANAGER", b, 1);
        driver=createStaff("履约配送员", "DELIVERY", b, 1);
        colleague=createStaff("履约同事", "DELIVERY", b, 1);
        customer=createCustomer("退回客户", "directed-return-state-customer");
        address=createAddress(customer, "退回收件地址");
        product=createProduct("瓶装水", 2, "10.00", "0.00", 0, "0.00");
        createInventory(a,product,50); createInventory(b,product,50); createInventory(c,product,50);
        origin=staffToken(ma,"STATION_MANAGER",a); receiver=staffToken(mb,"STATION_MANAGER",b);
        delivery=staffToken(driver,"DELIVERY",b); cus=customerToken(customer);
    }

    private long incoming(String shape) {
        long id=createOrderFull(customer,address,a,product,1,1,2,"10.00","0.00","10.00",false,0);
        assertEquals(0,post("/api/delivery/orders/"+id+"/dispatch",origin,"{\"targetStationId\":"+b+"}").code());
        if (!shape.equals("UNASSIGNED")) assertEquals(0,post("/api/delivery/orders/assign/"+id,receiver,
                "{\"deliveryStaffId\":"+driver+"}").code());
        if (shape.equals("ACCEPTED")) assertEquals(0,post("/api/delivery/orders/"+id+"/accept",delivery,"{}").code());
        return id;
    }
    private int state(long id) { return intOf("select status from orders where id=?",id); }
    private int pending(long id) { return intOf("select count(*) from order_transfer where order_id=? and kind='DIRECTED' and status='PENDING'",id); }
    private String hint(long id) {
        Api detail=get("/api/orders/"+id,cus); assertEquals(0,detail.code(),detail.toString());
        return detail.data().path("deliveryArrangementHint").asText("");
    }
    private void denied(Api api) { assertEquals(1,api.code(),api.toString()); }
    private JsonNode row(JsonNode rows,long id) { for(JsonNode row:rows) if(row.path("id").asLong()==id)return row; return null; }
    private void request(long id) { assertEquals(0,post("/api/delivery/orders/"+id+"/directed-return",receiver,"{}").code()); }

    private long requestId(long id) {
        return longOf("select id from order_transfer where order_id=? and kind='DIRECTED' and sub_kind='DIRECTED_RETURN' and status='PENDING'", id);
    }

    private String lastDecisionBody(long id) {
        return decisionBody(longOf("select coalesce(max(id),999999) from order_transfer where order_id=? and kind='DIRECTED' and sub_kind='DIRECTED_RETURN'", id));
    }

    private String decisionBody(long requestId) { return "{\"requestId\":" + requestId + "}"; }

    private List<?> decisionSnapshot(long id) {
        return List.of(
                jdbc.queryForList("select * from orders where id=?", id),
                jdbc.queryForList("select * from order_transfer where order_id=? order by id", id),
                jdbc.queryForList("select * from payment_record where order_id=? order by id", id),
                jdbc.queryForList("select * from inventory_reservation where order_id=? order by id", id),
                jdbc.queryForList("select * from inventory order by id"),
                jdbc.queryForList("select * from order_item where order_id=? order by id", id),
                jdbc.queryForList("select * from audit_log order by id"));
    }

    private long assignedWithPendingMoneyAndReservation() {
        long id = incoming("ASSIGNED");
        createReservedItem(id, product, "瓶装水", 1, "10.00", "0.00");
        createPaymentRecord(id, customer, b, "10.00", 2, 1);
        return id;
    }

    @ParameterizedTest @ValueSource(strings={"approve","reject"})
    void staleDecisionCannotResolveANewerRoundWithTheSameAssignment(String action) {
        seed(); long id = assignedWithPendingMoneyAndReservation(); request(id);
        long first = requestId(id); // Device A keeps the first-round modal open.
        assertEquals(0, post("/api/delivery/orders/"+id+"/directed-return/reject", origin, decisionBody(first)).code());
        request(id); long second = requestId(id); assertNotEquals(first, second);
        List<?> before = decisionSnapshot(id);
        Api stale = post("/api/delivery/orders/"+id+"/directed-return/"+action, origin, decisionBody(first));
        List<?> after = decisionSnapshot(id);
        System.out.println("DIRECTED_RETURN_STALE action="+action+" first="+first+" second="+second+" response="+stale.body()
                +" order="+jdbc.queryForList("select status,delivery_staff_id,delivery_station_id,settle_station_id from orders where id=?", id)
                +" money="+jdbc.queryForList("select station_id,status from payment_record where order_id=?", id)
                +" reservation="+jdbc.queryForList("select station_id,status,reserved_qty from inventory_reservation where order_id=? order by id", id));
        assertAll("an old modal must leave the new round and every side effect unchanged",
                () -> assertEquals(1, stale.code(), stale.toString()),
                () -> assertEquals(before, after, "delivery, settlement, pending money, reservations and audit must not change"));

        Api current = post("/api/delivery/orders/"+id+"/directed-return/"+action, origin, decisionBody(second));
        assertEquals(0, current.code(), current.toString());
        assertEquals("REJECTED", jdbc.queryForObject("select status from order_transfer where id=?", String.class, first));
        assertEquals(action.equals("approve") ? "APPROVED" : "REJECTED",
                jdbc.queryForObject("select status from order_transfer where id=?", String.class, second));
        long expectedStation = action.equals("approve") ? a : b;
        assertEquals(1, state(id));
        assertEquals(expectedStation, longOf("select delivery_station_id from orders where id=?", id));
        assertEquals(expectedStation, longOf("select settle_station_id from orders where id=?", id));
        assertEquals(expectedStation, longOf("select station_id from payment_record where order_id=? and status=1", id));
        assertEquals(expectedStation, longOf("select station_id from inventory_reservation where order_id=? and status=1", id));
        assertEquals(1, intOf("select reserved_qty from inventory_reservation where order_id=? and status=1", id));
        if (action.equals("approve")) assertNull(jdbc.queryForObject("select delivery_staff_id from orders where id=?", Object.class, id));
        else assertEquals(driver, longOf("select delivery_staff_id from orders where id=?", id));
    }

    @ParameterizedTest @ValueSource(strings={"approve","reject"})
    void oldClientsWithoutARequestIdMustRefreshInsteadOfChoosingTheLatestRound(String action) {
        seed(); long id = assignedWithPendingMoneyAndReservation(); request(id);
        List<?> before = decisionSnapshot(id);
        for (String body : new String[]{null, "{}", "{\"requestId\":null}", "{\"requestId\":0}", "{\"requestId\":-1}"}) {
            Api missing = post("/api/delivery/orders/"+id+"/directed-return/"+action, origin, body);
            assertAll(() -> assertEquals(1, missing.code(), missing.toString()),
                    () -> assertTrue(missing.body().path("message").asText().contains("刷新"), missing.toString()),
                    () -> assertEquals(before, decisionSnapshot(id)));
        }
    }

    @Test void approvalListsExposeTheExactPendingRoundAndNeverAnotherOrdersRequest() {
        seed(); long id = incoming("ASSIGNED"); request(id); long first = requestId(id);
        for (String path : new String[]{"station-pending", "directed-returns"}) {
            assertEquals(first, row(get("/api/delivery/orders/"+path, origin).data(), id).path("transferPendingRequestId").asLong());
        }
        assertEquals(0, post("/api/delivery/orders/"+id+"/directed-return/reject", origin, decisionBody(first)).code());
        request(id); long second = requestId(id);
        for (String path : new String[]{"station-pending", "directed-returns"}) {
            assertEquals(second, row(get("/api/delivery/orders/"+path, origin).data(), id).path("transferPendingRequestId").asLong());
        }
        long other = incoming("ASSIGNED"); request(other); long othersRequest = requestId(other);
        List<?> before = decisionSnapshot(id);
        for (String action : new String[]{"approve", "reject"}) {
            denied(post("/api/delivery/orders/"+id+"/directed-return/"+action, origin, decisionBody(othersRequest)));
            assertEquals(before, decisionSnapshot(id));
        }
        assertEquals(1, pending(id)); assertEquals(1, pending(other));
    }

    @Test void rejectingAnUnacceptedReturnCannotInventStartedDelivery() {
        seed();
        long id = incoming("UNASSIGNED");
        request(id);
        assertEquals(0, post("/api/delivery/orders/" + id + "/directed-return/reject", origin, lastDecisionBody(id)).code());
        assertEquals(1, state(id), "rejecting a return must not invent delivery acceptance");
        assertNull(jdbc.queryForObject("select delivery_staff_id from orders where id=?", Object.class, id));
        assertEquals(b, longOf("select delivery_station_id from orders where id=?", id));
    }

    @ParameterizedTest @ValueSource(strings={"UNASSIGNED","ASSIGNED","ACCEPTED"})
    void requestAndRejectionKeepTheActualPrimaryStateAndOriginalAssignee(String shape) {
        seed(); long id=incoming(shape); int before=state(id);
        Object assigned=jdbc.queryForObject("select delivery_staff_id from orders where id=?",Object.class,id);
        request(id);
        assertEquals(before,state(id),"a request must not erase whether delivery has actually started");
        assertEquals(1,pending(id));
        assertNotNull(row(get("/api/delivery/orders/directed-returns",origin).data(),id));
        assertNotNull(row(get("/api/delivery/orders/station-pending",origin).data(),id),
                "the real coordination page must still receive accepted return requests");
        assertFalse(hint(id).isBlank());
        assertFalse(hint(id).contains("已同意延期"));
        denied(post("/api/delivery/orders/"+id+"/directed-return",receiver,"{}"));
        assertEquals(0,post("/api/delivery/orders/"+id+"/directed-return/reject",origin,lastDecisionBody(id)).code());
        assertEquals(before,state(id));
        assertEquals(b,longOf("select delivery_station_id from orders where id=?",id));
        assertEquals(assigned,jdbc.queryForObject("select delivery_staff_id from orders where id=?",Object.class,id));
        assertEquals(0,pending(id));
        assertEquals(1,intOf("select count(*) from order_transfer where order_id=? and status='REJECTED'",id));
        if(before==1)assertFalse(hint(id).isBlank()); else assertTrue(hint(id).isBlank());
        denied(post("/api/delivery/orders/"+id+"/directed-return/reject",origin,lastDecisionBody(id)));
        denied(post("/api/delivery/orders/"+id+"/directed-return/approve",origin,lastDecisionBody(id)));
        assertEquals(before,state(id));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=?",id));
        if(before==1) {
            assertEquals(0,post("/api/delivery/orders/assign/"+id,receiver,"{\"deliveryStaffId\":"+driver+"}").code());
            assertTrue(hint(id).isBlank());
            assertEquals(0,post("/api/delivery/orders/"+id+"/accept",delivery,"{}").code());
            assertEquals(2,state(id));
        }
    }

    @ParameterizedTest @ValueSource(strings={"UNASSIGNED","ASSIGNED","ACCEPTED"})
    void approvalKeepsTheExistingReturnAndReassignmentExitWithoutInventingDelivery(String shape) {
        seed(); long id=incoming(shape); request(id);
        assertEquals(0,post("/api/delivery/orders/"+id+"/directed-return/approve",origin,lastDecisionBody(id)).code());
        assertEquals(1,state(id)); assertEquals(a,longOf("select delivery_station_id from orders where id=?",id));
        assertNull(jdbc.queryForObject("select delivery_staff_id from orders where id=?",Object.class,id));
        assertEquals(0,pending(id)); assertFalse(hint(id).isBlank());
        denied(post("/api/delivery/orders/"+id+"/directed-return/approve",origin,lastDecisionBody(id)));
        assertEquals(0,post("/api/delivery/orders/assign/"+id,origin,"{\"deliveryStaffId\":"+ma+"}").code());
        assertTrue(hint(id).isBlank(),"a fresh assignment must clear an old rearrangement hint");
        assertEquals(0,post("/api/delivery/orders/"+id+"/accept",origin,"{}").code());
        assertEquals(2,state(id)); assertTrue(hint(id).isBlank());
    }

    @Test void markersWithoutARequestAndOtherStationsCannotApproveOrReject() {
        seed(); long id=incoming("ASSIGNED");
        jdbc.update("update orders set special_note=concat(coalesce(special_note,''),' [指定退回待确认]') where id=?",id);
        denied(post("/api/delivery/orders/"+id+"/directed-return/reject",origin,lastDecisionBody(id)));
        denied(post("/api/delivery/orders/"+id+"/directed-return/approve",origin,lastDecisionBody(id)));
        assertTrue(hint(id).isBlank(),"a text marker alone is not an actual delivery problem");
        request(id);
        denied(post("/api/delivery/orders/"+id+"/directed-return/reject",receiver,lastDecisionBody(id)));
        denied(post("/api/delivery/orders/"+id+"/directed-return/approve",receiver,lastDecisionBody(id)));
        assertEquals(1,state(id)); assertEquals(1,pending(id));
    }

    @ParameterizedTest @ValueSource(strings={"ASSIGNED","ACCEPTED"})
    void pendingDirectedReturnRetainsTheExistingHoldOnAssignmentAcceptanceAndDelivery(String shape) {
        seed(); long id=incoming(shape); int before=state(id); request(id);
        denied(post("/api/delivery/orders/"+id+"/accept",delivery,"{}"));
        denied(post("/api/delivery/orders/assign/"+id,receiver,"{\"deliveryStaffId\":"+colleague+"}"));
        denied(post("/api/delivery/orders/"+id+"/dispatch",receiver,"{\"targetStationId\":"+c+"}"));
        denied(post("/api/delivery/orders/transfer/"+id+"/outsource",receiver,"{\"targetStationId\":"+c+"}"));
        denied(post("/api/delivery/orders/"+id+"/confirm-offline-pay",delivery,"{}"));
        denied(post("/api/delivery/orders/"+id+"/complete",delivery,"{}"));
        denied(post("/api/delivery/orders/transfer/"+id,delivery,"{\"deliveryStaffId\":"+colleague+"}"));
        denied(post("/api/delivery/orders/return/"+id,delivery,"{}"));
        denied(post("/api/delivery/orders/"+id+"/cancel-request",delivery,"{}"));
        if(before==2)denied(post("/api/delivery/orders/"+id+"/cancel-dispatch",origin,"{}"));
        assertEquals(before,state(id)); assertEquals(driver,longOf("select delivery_staff_id from orders where id=?",id));
        assertEquals(1,pending(id));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=?",id));
        assertEquals(0,intOf("select count(*) from staff_earning where order_id=?",id));
    }

    @Test void unacceptedRecallClosesTheExactReturnRequestAndAllowsReassignment() {
        seed(); long id=incoming("UNASSIGNED"); request(id);
        assertEquals(0,post("/api/delivery/orders/"+id+"/cancel-dispatch",origin,"{}").code());
        assertEquals(1,state(id)); assertEquals(a,longOf("select delivery_station_id from orders where id=?",id));
        assertEquals(0,pending(id));
        assertEquals(1,intOf("select count(*) from order_transfer where order_id=? and status='CANCELLED'",id));
        assertFalse(hint(id).isBlank());
        denied(post("/api/delivery/orders/"+id+"/cancel-dispatch",origin,"{}"));
        denied(post("/api/delivery/orders/"+id+"/directed-return/reject",origin,lastDecisionBody(id)));
        assertEquals(0,post("/api/delivery/orders/assign/"+id,origin,"{\"deliveryStaffId\":"+ma+"}").code());
        assertTrue(hint(id).isBlank());
    }

    @Test void terminalStateOrReplacedAssigneeCannotBeOverwrittenByALateDecision() {
        seed(); long id=incoming("ACCEPTED"); request(id);
        jdbc.update("update orders set status=3 where id=?",id);
        denied(post("/api/delivery/orders/"+id+"/directed-return/reject",origin,lastDecisionBody(id)));
        denied(post("/api/delivery/orders/"+id+"/directed-return/approve",origin,lastDecisionBody(id)));
        assertEquals(3,state(id)); assertEquals(b,longOf("select delivery_station_id from orders where id=?",id));
        long another=incoming("ASSIGNED"); request(another);
        jdbc.update("update orders set delivery_staff_id=? where id=?",colleague,another);
        denied(post("/api/delivery/orders/"+another+"/directed-return/reject",origin,lastDecisionBody(another)));
        assertEquals(1,state(another)); assertEquals(colleague,longOf("select delivery_staff_id from orders where id=?",another));
    }

    private void legacyRequest(long id) {
        insert("insert into order_transfer(order_id,kind,sub_kind,from_staff_id,from_station_id,status,reason,create_time,update_time) "
                + "values(?,'DIRECTED','DIRECTED_RETURN',?,?,'PENDING','旧版指定退回',now(),now())",id,driver,b);
        jdbc.update("update orders set status=1,special_note=concat(coalesce(special_note,''),' [指定退回待确认]') where id=?",id);
        jdbc.update("insert into audit_log(module,action,target,detail,create_time) values('ORDER','DIRECTED_RETURN',?,?,now())",
                "order:"+id,"{fromStationId="+b+", toStationId="+a+"}");
    }

    @ParameterizedTest @ValueSource(strings={"ASSIGNED","ACCEPTED"})
    void legacyCollapsedRequestRestoresOnlyTheStateProvedByEarlierAssignmentOrAcceptance(String shape) {
        seed(); long id=incoming(shape); int original=state(id); legacyRequest(id);
        if(original==2)denied(post("/api/delivery/orders/"+id+"/cancel-dispatch",origin,"{}"));
        assertEquals(0,post("/api/delivery/orders/"+id+"/directed-return/reject",origin,lastDecisionBody(id)).code());
        assertEquals(original,state(id)); assertEquals(driver,longOf("select delivery_staff_id from orders where id=?",id));
    }

    @Test void unknownLegacyOriginalStateUsesTheExistingApprovalExitRatherThanGuessingFromAnAssignee() {
        seed(); long id=createOrderCrossStation(customer,address,a,b,product,1,1,2,"10.00","0.00","10.00");
        jdbc.update("update orders set delivery_staff_id=? where id=?",driver,id); legacyRequest(id);
        denied(post("/api/delivery/orders/"+id+"/directed-return/reject",origin,lastDecisionBody(id)));
        assertEquals(1,state(id)); assertEquals(1,pending(id));
        assertEquals(0,post("/api/delivery/orders/"+id+"/directed-return/approve",origin,lastDecisionBody(id)).code());
        assertEquals(1,state(id)); assertEquals(a,longOf("select delivery_station_id from orders where id=?",id));
    }

    @ParameterizedTest @ValueSource(strings={"ASSIGNED","ACCEPTED"})
    void concurrentOppositeDecisionsCommitExactlyOneOutcome(String shape) throws Exception {
        seed(); long id=incoming(shape); int original=state(id); request(id);
        String observed = decisionBody(requestId(id));
        var pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            var approve=pool.submit(()->{start.await();return post("/api/delivery/orders/"+id+"/directed-return/approve",origin,observed);});
            var reject=pool.submit(()->{start.await();return post("/api/delivery/orders/"+id+"/directed-return/reject",origin,observed);});
            start.countDown(); var outcomes=List.of(approve.get(10,TimeUnit.SECONDS),reject.get(10,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter(r->r.code()==0).count(),outcomes.toString());
            assertTrue(outcomes.stream().allMatch(r->r.code()==0||r.code()==1)); assertEquals(0,pending(id));
            String result=jdbc.queryForObject("select status from order_transfer where order_id=? and kind='DIRECTED'",String.class,id);
            assertEquals(result.equals("APPROVED")?1:original,state(id));
            assertEquals(result.equals("APPROVED")?a:b,longOf("select delivery_station_id from orders where id=?",id));
            if(result.equals("APPROVED"))assertNull(jdbc.queryForObject("select delivery_staff_id from orders where id=?",Object.class,id));
            else assertEquals(driver,longOf("select delivery_staff_id from orders where id=?",id));
            assertEquals(0,intOf("select count(*) from payment_record where order_id=?",id));
        } finally {pool.shutdownNow();}
    }

    @Test void requestAndAcceptanceUseTheSameOrderLockAndPreserveWhicheverActualStateWins() throws Exception {
        seed(); long id=incoming("ASSIGNED"); var pool=Executors.newFixedThreadPool(2);
        try(Connection blocker=openRawTransaction()) {
            lockRowsRaw(blocker,"select id from orders where id=? for update",id); long trx=rawTrxId(blocker);
            var request=pool.submit(()->post("/api/delivery/orders/"+id+"/directed-return",receiver,"{}"));
            var accept=pool.submit(()->post("/api/delivery/orders/"+id+"/accept",delivery,"{}"));
            assertTrue(awaitBlockedBy(trx,"orders",2,5000)>=2); blocker.commit();
            assertEquals(0,request.get(10,TimeUnit.SECONDS).code()); Api accepted=accept.get(10,TimeUnit.SECONDS);
            assertTrue(accepted.code()==0||accepted.code()==1); assertEquals(accepted.code()==0?2:1,state(id));
            assertEquals(1,pending(id)); assertEquals(driver,longOf("select delivery_staff_id from orders where id=?",id));
        } finally {pool.shutdownNow();}
    }
}
