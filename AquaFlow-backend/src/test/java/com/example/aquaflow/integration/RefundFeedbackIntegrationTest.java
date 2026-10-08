package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real author/station guards and DB uniqueness; correspondence must never mutate money or assets. */
class RefundFeedbackIntegrationTest extends AbstractIntegrationTest {
    long a,b,customer,otherCustomer,ma,mb,delivery,payment,order;
    String cus,other,mgrA,mgrB,dlv;
    void seed() {
        a=createStation("资产归属站");b=createStation("款项结算站");
        ma=createStaff("A站长","STATION_MANAGER",a,1);mb=createStaff("B站长","STATION_MANAGER",b,1);delivery=createStaff("配送员","DELIVERY",b,1);
        customer=createCustomer("本人","refund-note-customer");otherCustomer=createCustomer("他人","refund-note-other");
        createCustomerStationConfig(customer,a,1);createCustomerStationConfig(customer,b,1);
        long product=createProduct("水",1,"20.00","30.00",1,"8.00");long address=createAddress(customer,"地址");
        order=createOrderFull(customer,address,a,product,4,2,2,"20.00","0.00","20.00",false,1);
        jdbc.update("update orders set delivery_station_id=?,settle_station_id=? where id=?",b,b,order);
        payment=createPaymentRecord(order,customer,a,"20.00",2,2);
        jdbc.update("update payment_record set water_amount=20,barrel_deposit=0 where id=?",payment);
        cus=customerToken(customer);other=customerToken(otherCustomer);mgrA=staffToken(ma,"STATION_MANAGER",a);mgrB=staffToken(mb,"STATION_MANAGER",b);dlv=staffToken(delivery,"DELIVERY",b);
    }
    String body(String type,long id,String key,String content) {
        return "{\"refundType\":\""+type+"\",\"refundId\":"+id+",\"idempotencyKey\":\""+key+"\",\"content\":\""+content+"\"}";
    }
    Api append(String token,String key,String content) {return post("/api/feedback/refund-notes",token,body("ORDER_PAYMENT",payment,key,content));}
    Api thread(String token) {return get("/api/feedback/refund-notes?refundType=ORDER_PAYMENT&refundId="+payment,token);}
    Map<String,Object> facts() {
        Map<String,Object> f=new LinkedHashMap<>();
        for(String t:List.of("orders","payment_record","barrel_record","deposit_record","customer_deposit_account","ticket_lot","ticket_record","ticket_exit_refund","customer_barrel_lot","customer_barrel_asset","customer_barrel_over")) f.put(t,jdbc.queryForList("select * from "+t));
        return f;
    }
    @Test void onlyOwnerAndSettlementStationCanAppendAndRead() {
        seed();var before=facts();Api note=append(cus,"one","对退款有疑问");assertEquals(0,note.code(),note.toString());
        assertEquals(b,note.data().path("responsibleStationId").asLong(),"use order settlement, not payment snapshot station");
        assertEquals(1,append(other,"wrong-person","他人的退款").code());assertEquals(1,append(mgrA,"wrong-station","越站").code());assertEquals(1,append(dlv,"delivery","配送员").code());
        assertEquals(1,thread(other).code());assertEquals(1,thread(mgrA).code());assertEquals(1,thread(dlv).code());
        assertEquals(0,thread(cus).code());assertEquals(0,thread(mgrB).code());
        assertEquals(0,get("/api/feedback/customers",mgrA).data().size(),"bound customer is not refund authority");
        Api responsibleList=get("/api/feedback/customers",mgrB);assertEquals(1,responsibleList.data().size());
        assertTrue(responsibleList.data().get(0).path("customerName").isNull(),"refund correspondence must not expose origin customer profile to the settlement station");
        assertEquals(customer,responsibleList.data().get(0).path("customerId").asLong());assertEquals(1,intOf("select count(*) from feedback"));assertEquals(before,facts());
    }
    @Test void forgedIdentityAndStationFieldsCannotGrantAccess() {
        seed();String forged=body("ORDER_PAYMENT",payment,"forged","伪造").replace("}",",\"customerId\":"+customer+",\"stationId\":"+b+",\"responsibleStationId\":"+b+",\"staffId\":"+mb+"}");
        assertEquals(1,post("/api/feedback/refund-notes",other,forged).code());assertEquals(1,post("/api/feedback/refund-notes",mgrA,forged).code());assertEquals(0,intOf("select count(*) from feedback"));
    }
    @Test void optionalExplanationAndLaterEvidenceAreSeparateAppendOnlyRecords() {
        seed();var before=facts();Api first=append(cus,"blank","");assertEquals(0,first.code(),first.toString());long id=first.data().path("id").asLong();
        Api reply=append(mgrB,"reply","已联系客户核对");assertEquals(0,reply.code(),reply.toString());
        assertEquals("水站补充说明",reply.data().path("authorText").asText());
        assertEquals(0,append(cus,"later-evidence","后补说明：当面交款时间为下午").code());
        assertEquals("退款补充记录（暂无说明）",jdbc.queryForObject("select content from feedback where id=?",String.class,id));
        assertEquals(3,thread(cus).data().path("notes").size());assertEquals(3,get("/api/feedback/my",cus).data().size());assertEquals(before,facts());
    }
    @Test void sameKeyReplaysOriginalDifferentContentIsRejectedAndActorsAreSeparate() {
        seed();var before=facts();Api first=append(cus,"same","第一条");assertEquals(0,first.code(),first.toString());
        Api replay=append(cus,"same","第一条");assertEquals(0,replay.code(),replay.toString());assertEquals(first.data().path("id").asLong(),replay.data().path("id").asLong());
        assertEquals(1,append(cus,"same","修改原事实").code());assertEquals(0,append(mgrB,"same","站长自己的补充").code());assertEquals(2,intOf("select count(*) from feedback"));
        assertEquals("第一条",jdbc.queryForObject("select content from feedback where id=?",String.class,first.data().path("id").asLong()));assertEquals(before,facts());
        assertFalse(replay.data().has("actorKey"));assertFalse(replay.data().has("requestDigest"));assertFalse(replay.data().has("idempotencyKey"));
    }
    @Test void concurrentRetriesCreateOneRecordWithoutMoneyChanges() throws Exception {
        seed();var before=facts();ExecutorService pool=Executors.newFixedThreadPool(3);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Api>> futures=new ArrayList<>();for(int i=0;i<3;i++)futures.add(pool.submit(()->{start.await();return append(cus,"race","并发重复");}));start.countDown();
            Set<Long> ids=new HashSet<>();for(var f:futures){Api r=f.get(30,TimeUnit.SECONDS);assertEquals(0,r.code(),r.toString());ids.add(r.data().path("id").asLong());}
            assertEquals(1,ids.size());assertEquals(1,intOf("select count(*) from feedback"));assertEquals(before,facts());
        } finally {pool.shutdownNow();}
    }
    @Test void lateRetryAfterRealRefundAndClosureNeverRefundsAgain() {
        seed();Api first=append(cus,"late","退款异议");assertEquals(0,first.code(),first.toString());
        Api refund=put("/api/payments/"+payment+"/refund",mgrB,"{\"scope\":\"WATER\"}");assertEquals(0,refund.code(),refund.toString());jdbc.update("update station set status=2 where id=?",b);
        var afterRefund=facts();Api late=append(cus,"late","退款异议");assertEquals(0,late.code(),late.toString());assertEquals(first.data().path("id").asLong(),late.data().path("id").asLong());
        assertEquals(1,append(other,"late","退款异议").code());assertEquals(0,append(mgrB,"after","退款后补充说明").code());assertEquals(afterRefund,facts());
        assertEquals(1,intOf("select count(*) from payment_record where amount<0"));
    }
    @Test void barrelReturnUsesAssetStationAndWrongRecordTypeCannotBeLinked() {
        seed();
        // 2026-10-08：真实嵌套事务拒绝试算时，应返回不可办理而不是提交阶段500；只读不改任何资金/桶账。
        long unavailableReturn=insert("insert into barrel_record(customer_id,station_id,product_id,type,quantity,status,deposit_refund) values(?,?,1,2,1,2,30)",customer,a);
        var beforeEligibility=facts();int systemAlerts=intOf("select count(*) from alert_log where alert_type='SYSTEM'");
        String eligibilityPath="/api/barrels/records/"+unavailableReturn+"/refund-eligibility";
        Api eligibility=get(eligibilityPath,mgrA);assertEquals(0,eligibility.code(),eligibility.toString());
        assertFalse(eligibility.data().path("available").asBoolean());assertTrue(eligibility.data().path("legacy").asBoolean());
        assertTrue(eligibility.data().path("reason").asText().contains("超过拥有的桶权益数"),eligibility.toString());
        assertEquals(1,get(eligibilityPath,mgrB).code());assertEquals(1,get(eligibilityPath,cus).code());assertEquals(1,get(eligibilityPath,dlv).code());
        assertEquals(beforeEligibility,facts());assertEquals(systemAlerts,intOf("select count(*) from alert_log where alert_type='SYSTEM'"));
        long r=insert("insert into barrel_record(customer_id,station_id,product_id,type,quantity,status,note,handle_note) values(?,?,1,2,1,3,'原申请','原处理')",customer,a);
        var before=facts();String payload=body("BARREL_RETURN",r,"return","退押金说明");
        assertEquals(0,post("/api/feedback/refund-notes",cus,payload).code());assertEquals(0,post("/api/feedback/refund-notes",mgrA,payload).code());assertEquals(1,post("/api/feedback/refund-notes",mgrB,payload).code());
        assertEquals(before,facts());long nonReturn=insert("insert into barrel_record(customer_id,station_id,product_id,type,quantity) values(?,?,1,1,1)",customer,a);
        assertEquals(1,post("/api/feedback/refund-notes",cus,body("BARREL_RETURN",nonReturn,"bad","不是退还申请")).code());
    }
    @Test void ticketRefundAssociationUsesOriginalPaymentStationAndExcludesUnpaidOrOtherAssets() {
        seed();long ticket=createPaymentRecord(null,customer,a,"24.00",2,2);jdbc.update("update payment_record set ticket_qty=3,ticket_water_type_id=1 where id=?",ticket);
        var before=facts();String payload=body("TICKET_PAYMENT",ticket,"ticket","原款说明");
        assertEquals(0,post("/api/feedback/refund-notes",cus,payload).code());assertEquals(0,post("/api/feedback/refund-notes",mgrA,payload).code());assertEquals(1,post("/api/feedback/refund-notes",mgrB,payload).code());
        long unpaid=createPaymentRecord(null,customer,a,"24.00",2,1);jdbc.update("update payment_record set ticket_qty=3 where id=?",unpaid);
        assertEquals(1,post("/api/feedback/refund-notes",cus,body("TICKET_PAYMENT",unpaid,"unpaid","未收款")).code());
        assertEquals(1,post("/api/feedback/refund-notes",cus,body("TICKET_PAYMENT",payment,"wrong-kind","不能混用")).code());
        assertEquals(2,intOf("select count(*) from feedback"));
        before.put("payment_record",jdbc.queryForList("select * from payment_record"));assertEquals(before,facts());
    }
    @Test void missingKeyUnknownObjectAndMissingObjectAreBusinessRejections() {
        seed();assertEquals(1,append(cus,"","无键").code());
        assertEquals(1,post("/api/feedback/refund-notes",cus,body("UNKNOWN",payment,"bad","未知类型")).code());
        assertEquals(1,post("/api/feedback/refund-notes",cus,body("ORDER_PAYMENT",999999,"absent","不存在")).code());assertEquals(0,intOf("select count(*) from feedback"));
    }
    @Test void optionsAreServerScopedAndOrdinaryAnonymousFeedbackIsStillAvailable() {
        seed();Api options=get("/api/feedback/refund-options?customerId="+otherCustomer,cus);assertEquals(0,options.code(),options.toString());assertEquals(1,options.data().path("options").size());assertEquals(payment,options.data().path("options").get(0).path("refundId").asLong());
        assertEquals(0,get("/api/feedback/refund-options",other).data().path("options").size());assertEquals(1,get("/api/feedback/refund-options",mgrB).code());
        assertEquals(0,post("/api/feedback",cus,"{\"content\":\"普通匿名反馈\",\"anonymous\":true}").code());
        Api list=get("/api/feedback/customers",mgrA);assertEquals(0,list.code(),list.toString());assertEquals(1,list.data().size());assertTrue(list.data().get(0).path("customerId").isNull());assertTrue(list.data().get(0).path("customerName").isNull());
    }
    @Test void earlierRefundOptionsAreReachableThroughServerScopedPagination() {
        seed();
        for(int i=0;i<201;i++) insert("insert into payment_record(customer_id,station_id,amount,payment_method,status,ticket_qty) values(?,?,8,2,2,1)",customer,a);
        Api first=get("/api/feedback/refund-options?page=1",cus),second=get("/api/feedback/refund-options?page=2",cus);
        assertEquals(0,first.code(),first.toString());assertEquals(0,second.code(),second.toString());
        assertEquals(200,first.data().path("options").size());assertTrue(first.data().path("hasMore").asBoolean());assertEquals(2,second.data().path("options").size());assertFalse(second.data().path("hasMore").asBoolean());
        Set<Long> ids=new HashSet<>();first.data().path("options").forEach(n->ids.add(n.path("refundId").asLong()));second.data().path("options").forEach(n->assertTrue(ids.add(n.path("refundId").asLong())));assertEquals(202,ids.size());
        assertEquals(0,get("/api/feedback/refund-options?page=2&customerId="+customer,other).data().path("options").size());assertEquals(1,get("/api/feedback/refund-options?page=0",cus).code());
    }
    @Test void concurrentChangedContentCannotOverwriteTheWinningOriginal() throws Exception {
        seed();var before=facts();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> a=pool.submit(()->{start.await();return append(cus,"conflict","版本甲");});
            Future<Api> b=pool.submit(()->{start.await();return append(cus,"conflict","版本乙");});start.countDown();
            List<Api> results=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(r->r.code()==0).count(),results.toString());assertEquals(1,results.stream().filter(r->r.code()==1).count(),results.toString());
            assertEquals(1,intOf("select count(*) from feedback"));String original=jdbc.queryForObject("select content from feedback",String.class);
            assertEquals(results.stream().filter(r->r.code()==0).findFirst().orElseThrow().data().path("content").asText(),original);assertEquals(before,facts());
        } finally {pool.shutdownNow();}
    }
}
