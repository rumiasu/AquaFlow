package com.example.aquaflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP/MySQL专项；运行前由统一验证者安装v78并合并测试基线。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=true"})
class BarrelReturnArrangementIntegrationTest extends CustomerConfirmationFlowFixture {
    @org.springframework.beans.factory.annotation.Autowired
    com.example.aquaflow.mapper.BarrelReturnDetailMapper returnDetails;

    @Test void confirmationMapperCountsBothRowsAndRejectsStaleVersion() {
        seed();deliver(paidOrder());long id=request("STORE");
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":0}").code());
        assertEquals(0,returnDetails.confirmCustomer(id,2),"旧版本不能写入确认时间或版本");
        assertNull(jdbc.queryForObject("select customer_confirmed_time from barrel_return_detail where record_id=?",Timestamp.class,id));
        assertNull(jdbc.queryForObject("select confirmed_version from barrel_return_arrangement where record_id=?",Integer.class,id));
        assertEquals(2,returnDetails.confirmCustomer(id,1),"MySQL真实联表更新将两张表各计一行");
        Timestamp first=jdbc.queryForObject("select customer_confirmed_time from barrel_return_detail where record_id=?",Timestamp.class,id);
        assertNotNull(first);assertEquals(1,intOf("select confirmed_version from barrel_return_arrangement where record_id=?",id));
        assertEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{\"expectedVersion\":1}").code());
        assertEquals(first,jdbc.queryForObject("select customer_confirmed_time from barrel_return_detail where record_id=?",Timestamp.class,id),"本人HTTP重试保留首次时间");
    }

    @Test void customerChangeKeepsOriginalIntentLotsReservationAndRefundAmount() {
        seed();deliver(paidOrder());long id=request("STORE");
        String held=jdbc.queryForList("select * from barrel_return_lot_hold where record_id=?",id).toString();
        String reserved=jdbc.queryForList("select * from barrel_right_reservation where owner_type='RETURN' and owner_id=?",id).toString();
        Api change=put("/api/barrels/records/"+id+"/arrangement",cus,change("PICKUP",null,1,"customer-change"));assertEquals(0,change.code(),change.toString());
        assertEquals(id,change.data().path("recordId").asLong());assertEquals(2,change.data().path("returnDetail").path("arrangementVersion").asInt());
        assertFalse(change.data().path("returnDetail").path("customerConfirmationRequired").asBoolean());
        Api replay=post("/api/barrels/return",cus,requestBody("STORE"));assertEquals(0,replay.code(),replay.toString());assertEquals(id,replay.data().path("recordId").asLong());
        assertEquals(1,post("/api/barrels/return",cus,requestBody("PICKUP")).code(),"原幂等键只认原提交内容");
        assertEquals(held,jdbc.queryForList("select * from barrel_return_lot_hold where record_id=?",id).toString());
        assertEquals(reserved,jdbc.queryForList("select * from barrel_right_reservation where owner_type='RETURN' and owner_id=?",id).toString());
        assertEquals(1,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":0}").code(),"旧审批不能覆盖新安排");
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":0,\"expectedVersion\":2}").code());
        assertEquals(1,status(id,3).code());assertEquals(0,status(id,2).code());
        assertNull(jdbc.queryForObject("select customer_confirmed_time from barrel_return_detail where record_id=?",Timestamp.class,id));
        assertEquals(0,status(id,3).code());assertEquals(1,intOf("select count(*) from barrel_return_arrangement_change where record_id=?",id));
    }
    @Test void managerNewArrangementRequiresCurrentCustomerAuthorizationEvenWhenFree() {
        seed();deliver(paidOrder());long id=request("STORE");
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":0}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/manager-arrangement",mgr,change("PICKUP",null,1,"station-change")).code());
        assertNull(jdbc.queryForObject("select customer_confirmed_time from barrel_return_detail where record_id=?",Timestamp.class,id));
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":0,\"expectedVersion\":2}").code());
        assertEquals(1,status(id,2).code());assertEquals(1,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{}").code());
        assertEquals(1,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{\"expectedVersion\":1}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{\"expectedVersion\":2}").code());assertEquals(0,status(id,2).code());
        assertEquals(1,put("/api/barrels/records/"+id+"/arrangement",cus,change("STORE",null,2,"too-late")).code());
        assertEquals(1,intOf("select count(*) from barrel_return_arrangement_change where record_id=?",id));
    }
    @Test void paidOldFeeMustUseExistingActualRefundBeforeArrangementCanChange() {
        seed();deliver(paidOrder());long id=request("PICKUP");
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":5}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{}").code());
        long fee=jdbc.queryForObject("select fee_payment_id from barrel_return_detail where record_id=?",Long.class,id);
        assertEquals(0,put("/api/payments/"+fee+"/confirm",mgr,null).code());
        String body=change("STORE",null,1,"fee-change");assertEquals(1,put("/api/barrels/records/"+id+"/arrangement",cus,body).code());
        assertEquals(0,intOf("select count(*) from barrel_return_arrangement_change where record_id=?",id));
        assertEquals(0,put("/api/payments/"+fee+"/refund",mgr,"{\"scope\":\"SERVICE\",\"note\":\"实际退还旧服务费\"}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/arrangement",cus,body).code());
        assertEquals(3,intOf("select status from payment_record where id=?",fee));assertEquals(1,intOf("select count(*) from barrel_return_fee_refund where original_payment_id=?",fee));
        assertEquals(0,put("/api/barrels/records/"+id+"/arrangement",cus,body).code(),"同操作原凭据重试不再改变版本");
        assertEquals(2,intOf("select version from barrel_return_arrangement where record_id=?",id));
    }
    @Test void arrangementChangesUseVersionCasAndNeverGuessCompanionOrders() throws Exception {
        seed();deliver(paidOrder());long id=request("STORE");
        assertEquals(1,put("/api/barrels/records/"+id+"/arrangement",cus,change("COMBINED",null,1,"missing-order")).code());
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> a=pool.submit(()->{start.await();return put("/api/barrels/records/"+id+"/arrangement",cus,change("PICKUP",null,1,"a"));});
            Future<Api> b=pool.submit(()->{start.await();return put("/api/barrels/records/"+id+"/arrangement",cus,change("PICKUP",null,1,"b"));});start.countDown();
            List<Api> outcomes=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter(r->r.code()==0).count(),outcomes.toString());assertTrue(outcomes.stream().allMatch(r->r.code()==0 || r.code()==1),outcomes.toString());
            assertEquals(2,intOf("select version from barrel_return_arrangement where record_id=?",id));assertEquals(1,intOf("select count(*) from barrel_return_arrangement_change where record_id=?",id));
        } finally {pool.shutdownNow();}
    }

    @Test void cancelledCompanionCanBeExplicitlyReplacedWithoutRecreatingReturn() {
        seed();deliver(paidOrder());
        long accessory=createProduct("非桶配件",2,"10.00","0.00",1,"3.00");
        createInventoryFull(station,accessory,5,1,"3.00");
        String template="{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"idempotencyKey\":\"%s\",\"items\":[{\"productId\":"+accessory+",\"quantity\":1}]}";
        Api old=post("/api/orders/create",cus,String.format(template,"companion-old"));assertEquals(0,old.code(),old.toString());
        Api next=post("/api/orders/create",cus,String.format(template,"companion-new"));assertEquals(0,next.code(),next.toString());
        long oldId=old.data().path("orderId").asLong(),nextId=next.data().path("orderId").asLong();
        String original=requestBody("COMBINED").replace("\"quantity\":1,","\"quantity\":1,\"companionOrderId\":"+oldId+",");
        Api applied=post("/api/barrels/return",cus,original);assertEquals(0,applied.code(),applied.toString());long id=applied.data().path("recordId").asLong();
        assertEquals(0,put("/api/orders/"+oldId+"/customer-cancel",cus,null).code());
        assertEquals(oldId,jdbc.queryForObject("select companion_order_id from barrel_return_detail where record_id=?",Long.class,id));
        assertEquals(1,put("/api/barrels/records/"+id+"/arrangement",cus,change("COMBINED",oldId,1,"cancelled-choice")).code());
        Api changed=put("/api/barrels/records/"+id+"/arrangement",cus,change("COMBINED",nextId,1,"chosen-next"));assertEquals(0,changed.code(),changed.toString());
        assertEquals(id,changed.data().path("recordId").asLong());assertEquals(nextId,changed.data().path("returnDetail").path("companionOrderId").asLong());
        assertEquals(1,intOf("select count(*) from barrel_return_detail"));assertEquals(1,intOf("select count(*) from barrel_right_reservation where owner_type='RETURN' and owner_id=?",id));
        assertEquals(0,post("/api/barrels/return",cus,original).code(),"原请求重试只认原快照，不采用最新订单");
    }
}
