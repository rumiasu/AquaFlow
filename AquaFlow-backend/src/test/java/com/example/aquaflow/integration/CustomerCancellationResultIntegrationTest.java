package com.example.aquaflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=true"})
class CustomerCancellationResultIntegrationTest extends CustomerConfirmationFlowFixture {
    @Test void requestAndDecisionAreVisibleWithoutCustomerAcknowledgement() {
        seed();long id=paidOrder();assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());
        Api detail=get("/api/orders/"+id,cus);assertEquals("PENDING",detail.data().path("customerCancelRequest").path("status").asText());assertFalse(detail.data().path("canCancel").asBoolean());
        assertEquals(0,post("/api/delivery/orders/cancel-request/"+id+"/reject",mgr,"{}").code());
        detail=get("/api/orders/"+id,cus);assertEquals(2,detail.data().path("status").asInt());assertEquals("REJECTED",detail.data().path("customerCancelRequest").path("status").asText());assertTrue(detail.data().path("canCancel").asBoolean());
        assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());assertEquals(0,post("/api/delivery/orders/cancel-request/"+id+"/approve",mgr,"{}").code());
        detail=get("/api/orders/"+id,cus);assertEquals(5,detail.data().path("status").asInt());assertEquals("APPROVED",detail.data().path("customerCancelRequest").path("status").asText());
        assertEquals(1,post("/api/delivery/orders/"+id+"/complete",mgr,completion(id)).code());assertEquals(0,intOf("select count(*) from staff_earning where order_id=?",id));
        assertEquals(20,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));
    }
    @Test void pendingRequestDoesNotPauseDeliveryButAutomaticCloseIsVisible() {
        seed();long id=paidOrder();assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());deliver(id);
        Api detail=get("/api/orders/"+id,cus);assertEquals(4,detail.data().path("status").asInt());assertEquals("REJECTED",detail.data().path("customerCancelRequest").path("status").asText());
        assertTrue(detail.data().path("customerCancelRequest").path("resultNote").asText().contains("本次取消申请未生效"));assertEquals(1,intOf("select count(*) from order_cancel_result where automatic=1"));
        assertEquals(1,post("/api/delivery/orders/cancel-request/"+id+"/approve",mgr,"{}").code());
    }
    @Test void approvalAndDeliveryRaceHasOneCommittedOutcomeAndNoPartialShipment() throws Exception {
        seed();long id=paidOrder();assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());String body=completion(id);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> cancel=pool.submit(()->{start.await();return post("/api/delivery/orders/cancel-request/"+id+"/approve",mgr,"{}");});
            Future<Api> delivery=pool.submit(()->{start.await();return post("/api/delivery/orders/"+id+"/complete",mgr,body);});start.countDown();
            List<Api> outcomes=List.of(cancel.get(30,TimeUnit.SECONDS),delivery.get(30,TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter(r->r.code()==0).count(),outcomes.toString());assertTrue(outcomes.stream().allMatch(r->r.code()==0 || r.code()==1),outcomes.toString());
            int state=intOf("select status from orders where id=?",id);assertTrue(state==4 || state==5);
            assertEquals(state==5?20:18,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));
            if(state==5)assertEquals(0,intOf("select count(*) from staff_earning where order_id=?",id));
            assertEquals(1,intOf("select count(*) from order_cancel_result"));assertEquals(0,intOf("select count(*) from order_transfer where order_id=? and status='PENDING'",id));
        } finally {pool.shutdownNow();}
    }
}
