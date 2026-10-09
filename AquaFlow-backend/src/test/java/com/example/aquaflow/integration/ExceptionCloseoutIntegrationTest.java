package com.example.aquaflow.integration;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.service.ConfirmedRefusalService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP/MySQL异常结案；逐表比较资金、资产、票、库存及原判断，不能以结案冒充收退款。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true"})
class ExceptionCloseoutIntegrationTest extends AbstractIntegrationTest {
    @Autowired ConfirmedRefusalService refusalService;
    long a,b,c,customer,product,address,payment,paidOrder;
    String owner,other,ma,mb,mc,delivery;
    void seed() {
        a=createStation("资产站");b=createStation("债权站");c=createStation("无关站");
        ma=staffToken(createStaff("甲站长","STATION_MANAGER",a,1),"STATION_MANAGER",a);
        mb=staffToken(createStaff("乙站长","STATION_MANAGER",b,1),"STATION_MANAGER",b);
        mc=staffToken(createStaff("丙站长","STATION_MANAGER",c,1),"STATION_MANAGER",c);
        delivery=staffToken(createStaff("配送员","DELIVERY",b,1),"DELIVERY",b);
        customer=createCustomer("本人","exception-closeout-owner");owner=customerToken(customer);
        other=customerToken(createCustomer("他人","exception-closeout-other"));
        product=createProduct("水",1,"20.00","30.00",1,"8.00");address=createAddress(customer,"地址");
        createCustomerStationConfig(customer,a,0);createCustomerStationConfig(customer,b,0);
        createDepositBalance(customer,a,"60.00");createBarrelLot("closeout-existing",customer,a,product,"30.00",2,2);
        createBarrelAsset(customer,a,product,2,"60.00");createBarrelOver(customer,a,product,-1);createTicketAccount(customer,a,product,5);
        paidOrder=createOrderCrossStation(customer,address,a,b,product,4,2,2,"20.00","0.00","20.00");
        jdbc.update("update orders set settle_station_id=? where id=?",b,paidOrder);
        payment=createPaymentRecord(paidOrder,customer,a,"20.00",2,2);
    }
    long refusal(long debtStation,boolean confirmed) {
        long order=createOrderCrossStation(customer,address,a,debtStation,product,3,1,2,"20.00","0.00","20.00");
        jdbc.update("update orders set settle_station_id=? where id=?",debtStation,order);
        jdbc.update("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,note,create_time) values(?,1,?,?,?,?,1,'原拒付判断',now())",order,customer,a,debtStation,confirmed?1:0);
        return order;
    }
    String actionBody(String key,long version,String reason) {return "{\"idempotencyKey\":\""+key+"\",\"expectedVersion\":"+version+",\"reason\":\""+reason+"\"}";}
    Api resolve(long order,String action,String token,String key,long version,String reason) {
        return post("/api/manager/refusal-cases/"+order+"/"+action,token,actionBody(key,version,reason));
    }
    Api dispute(String token,boolean close,String key,long version,String reason) {
        String body=actionBody(key,version,reason).replace("}",",\"refundType\":\"ORDER_PAYMENT\",\"refundId\":"+payment+"}");
        return post("/api/feedback/refund-disputes/"+(close?"close":"open"),token,body);
    }
    Api thread(String token) {return get("/api/feedback/refund-notes?refundType=ORDER_PAYMENT&refundId="+payment,token);}
    Map<String,Object> facts() {
        Map<String,Object> facts=new LinkedHashMap<>();
        for(String table:List.of("orders","payment_record","customer_refusal_case","feedback","deposit_record","customer_deposit_account",
                "customer_barrel_lot","customer_barrel_asset","customer_barrel_over","barrel_record","barrel_right_reservation",
                "ticket_lot","ticket_account","ticket_record","inventory","inventory_record","inventory_reservation","customer_station_config"))facts.put(table,jdbc.queryForList("select * from "+table));
        return facts;
    }
    @Test void sameStationRevocationAtomicallyReleasesOnlyItsCaseAndPreservesDebtAndCredit() {
        seed();long first=refusal(a,true),second=refusal(a,true);var before=facts();
        assertTrue(refusalService.frozen(customer,a));
        Api result=resolve(first,"revoke",ma,"correct-one",0,"现场记错，核原款仍未收款");assertEquals(0,result.code(),result.toString());
        assertEquals(1,intOf("select judgment_revoked from customer_refusal_resolution where order_id=?",first));
        assertEquals(1,intOf("select asset_freeze_released from customer_refusal_resolution where order_id=?",first));
        assertTrue(refusalService.frozen(customer,a),"other valid case must keep its freeze");
        Orders next=new Orders();next.setId(-1L);next.setCustomerId(customer);next.setStationId(a);
        assertThrows(BusinessException.class,()->refusalService.requireOldDebtPaid(next));
        assertEquals(0,resolve(second,"revoke",ma,"correct-two",0,"另单也记错").code());
        assertFalse(refusalService.frozen(customer,a));assertDoesNotThrow(()->refusalService.requireOldDebtPaid(next));
        assertEquals(before,facts());
        assertEquals(1,resolve(first,"revoke",ma,"extra",1,"不能再次撤销").code());
        assertEquals(2,intOf("select count(*) from customer_refusal_action"));
    }
    @Test void debtStationRevocationKeepsCrossStationFreezeUntilAssetStationReviewsIt() {
        seed();long order=refusal(b,true);var before=facts();
        assertEquals(1,resolve(order,"revoke",ma,"unauthorized",0,"资产站不能推翻债权站").code());
        assertEquals(0,resolve(order,"revoke",mb,"correction",0,"债权站撤销误判").code());
        assertTrue(refusalService.frozen(customer,a));
        assertEquals(1,resolve(order,"release-freeze",mb,"wrong-release",1,"债权站无权解资产冻结").code());
        assertEquals(1,resolve(order,"release-freeze",mc,"unrelated",1,"无关站").code());
        assertEquals(0,resolve(order,"release-freeze",ma,"review",1,"资产站核实并解除本案限制").code());
        assertFalse(refusalService.frozen(customer,a));assertEquals(before,facts());
        assertEquals(2,get("/api/manager/refusal-cases/"+order+"/history",ma).data().size());
        assertEquals(1,get("/api/manager/refusal-cases/"+order+"/history",mc).code());
    }
    @Test void revokedUnconfirmedCaseCannotBeFrozenLaterAndUnsupportedRolesCannotResolve() {
        seed();long order=refusal(b,false);var before=facts();
        for(String token:List.of(owner,other,delivery,mc))assertEquals(1,resolve(order,"revoke",token,"wrong",0,"无权限").code());
        assertEquals(0,resolve(order,"revoke",mb,"correct",0,"撤销误判").code());
        assertEquals(1,put("/api/manager/refusal-cases/"+order+"/confirm-freeze",ma,"{}").code());
        assertEquals(1,resolve(order,"release-freeze",ma,"nothing",1,"未确认不能制造解除事实").code());
        assertEquals(before,facts());assertEquals(1,intOf("select count(*) from customer_refusal_action"));
    }
    @Test void refusalSameKeyConcurrentRetriesCreateOneReceiptAndChangedPayloadIsRejected() throws Exception {
        seed();long order=refusal(b,true);var before=facts();
        List<Api> results=race(()->resolve(order,"revoke",mb,"same",0,"核实为误判"),()->resolve(order,"revoke",mb,"same",0,"核实为误判"));
        for(Api result:results)assertEquals(0,result.code(),result.toString());assertEquals(results.get(0).data().path("id"),results.get(1).data().path("id"));
        assertEquals(1,resolve(order,"revoke",mb,"same",0,"篡改理由").code());
        assertEquals(1,resolve(order,"revoke",mb,"same",1,"核实为误判").code());
        assertEquals(1,intOf("select count(*) from customer_refusal_action"));assertEquals(before,facts());
    }
    @Test void managerClosesWithoutCustomerConfirmationAndCustomerReopensWithHistoryIntact() {
        seed();var before=facts();
        Api open=dispute(owner,false,"open",0,"原退款事实有疑问");assertEquals(0,open.code(),open.toString());
        assertEquals(0,dispute(mb,true,"close",1,"核对原款并告知处理安排").code());
        Api closed=thread(owner);assertEquals("CLOSED",closed.data().path("dispute").path("status").asText());
        assertEquals(0,dispute(owner,false,"again",2,"对处理结果仍有异议").code());
        assertEquals("OPEN",thread(owner).data().path("dispute").path("status").asText());
        assertEquals(0,dispute(mb,true,"close",1,"核对原款并告知处理安排").code(),"late original receipt must not close the new round");
        assertEquals("OPEN",thread(owner).data().path("dispute").path("status").asText());
        assertEquals(0,dispute(mb,true,"close-again",3,"第二轮结果另存").code());
        var history=thread(owner).data().path("dispute").path("actions");assertEquals(4,history.size());
        assertEquals("核对原款并告知处理安排",history.get(1).path("reason").asText());assertEquals("REOPEN",history.get(2).path("action").asText());
        assertEquals(before,facts());
    }
    @Test void refundAuthorityRemainsOwnerAndResponsibleStationAndBlankResultNeverCloses() {
        seed();var before=facts();
        assertEquals(1,dispute(other,false,"wrong-owner",0,"别人原款").code());
        assertEquals(1,dispute(mb,false,"staff-open",0,"不能代客提异议").code());
        assertEquals(0,dispute(owner,false,"open",0,"本人异议").code());
        for(String token:List.of(owner,other,ma,mc,delivery))assertEquals(1,dispute(token,true,"wrong-close",1,"越权结果").code());
        assertEquals(1,dispute(mb,true,"blank",1," ").code());
        assertEquals("OPEN",thread(owner).data().path("dispute").path("status").asText());
        assertEquals(1,get("/api/feedback/refund-disputes",mb).data().size());
        assertEquals(0,get("/api/feedback/refund-disputes",ma).data().size());assertEquals(0,get("/api/feedback/refund-disputes",other).data().size());
        jdbc.update("update station set status=2 where id=?",b);
        assertEquals(0,dispute(mb,true,"stopped",1,"停业仍处理原责任").code());assertEquals(before,facts());
    }
    @Test void refundSameKeyRaceAndCompetingCloseHaveOneEffectiveTransition() throws Exception {
        seed();var before=facts();
        for(var result:race(()->dispute(owner,false,"race",0,"相同异议"),()->dispute(owner,false,"race",0,"相同异议")))assertEquals(0,result.code(),result.toString());
        assertEquals(1,dispute(owner,false,"race",0,"换说明").code());
        var results=race(()->dispute(mb,true,"close-a",1,"处理结果甲"),()->dispute(mb,true,"close-b",1,"处理结果乙"));
        assertEquals(1,results.stream().filter(r->r.code()==0).count(),results.toString());assertEquals(1,results.stream().filter(r->r.code()==1).count(),results.toString());
        assertEquals(2,intOf("select count(*) from refund_dispute_action"));
        assertEquals(1,dispute(owner,false,"stale",1,"旧版本重提").code());
        assertEquals(0,dispute(owner,false,"fresh",2,"刷新后重新提出").code());assertEquals(before,facts());
    }
    @Test void inconsistentResponsibilityNeverBecomesAnEmptyListOrAnAuthorizedClosure() {
        seed();assertEquals(0,dispute(owner,false,"open",0,"本人异议").code());
        // 注入不一致快照；不是业务改站动作。原款仍归B，不能借客户与A的绑定放宽权限。
        jdbc.update("update refund_dispute set responsible_station_id=? where refund_type='ORDER_PAYMENT' and refund_id=?",a,payment);
        var before=facts();
        assertEquals(1,get("/api/feedback/refund-disputes",ma).code(),"invalid scope must be shown as failure, not an empty list");
        assertEquals(1,get("/api/feedback/refund-disputes",owner).code());assertEquals(1,thread(owner).code());
        assertEquals(1,dispute(ma,true,"wrong-source",1,"不能按错误快照结案").code());
        assertEquals(1,dispute(mb,true,"wrong-snapshot",1,"须先核对责任记录").code());
        assertEquals("OPEN",jdbc.queryForObject("select status from refund_dispute where refund_type='ORDER_PAYMENT' and refund_id=?",String.class,payment));
        assertEquals(1,intOf("select count(*) from refund_dispute_action"));assertEquals(before,facts());
    }
    @Test void barrelAndTicketDisputesUseTheirOwnOriginalStationAndNeverPerformRefund() {
        seed();long barrel=insert("insert into barrel_record(customer_id,station_id,product_id,type,quantity,status) values(?,?,?,2,1,3)",customer,a,product);
        long ticket=createPaymentRecord(null,customer,a,"24.00",2,2);jdbc.update("update payment_record set ticket_qty=3 where id=?",ticket);
        var before=facts();
        for(var ref:Map.of("BARREL_RETURN",barrel,"TICKET_PAYMENT",ticket).entrySet()) {
            String open=actionBody("open-"+ref.getKey(),0,"核对原退款").replace("}",",\"refundType\":\""+ref.getKey()+"\",\"refundId\":"+ref.getValue()+"}");
            String close=actionBody("close-"+ref.getKey(),1,"按原事实登记结果").replace("}",",\"refundType\":\""+ref.getKey()+"\",\"refundId\":"+ref.getValue()+"}");
            assertEquals(0,post("/api/feedback/refund-disputes/open",owner,open).code());
            assertEquals(1,post("/api/feedback/refund-disputes/close",mb,close).code());assertEquals(0,post("/api/feedback/refund-disputes/close",ma,close).code());
        }
        assertEquals(before,facts());
    }
    private List<Api> race(Supplier<Api> first,Supplier<Api> second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return first.get();});var b=pool.submit(()->{start.await();return second.get();});start.countDown();
            return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
}
