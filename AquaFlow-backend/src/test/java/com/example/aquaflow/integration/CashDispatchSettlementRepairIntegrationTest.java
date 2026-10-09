package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定整单报酬与接单站客户原款分别记：差额两站闭合，退款不能把客户原款重复抵扣追收。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true", "aquaflow.barrel.maintenance-enabled=false"})
class CashDispatchSettlementRepairIntegrationTest extends AbstractIntegrationTest {
    static final String LEDGER="/api/manager/inter-station-settlements";
    long a,b,am,bm,customer,address,product;
    String at,bt;
    void seed() {
        a=createStation("报价甲站");b=createStation("履约乙站");am=createStaff("甲长","STATION_MANAGER",a,1);bm=createStaff("乙长","STATION_MANAGER",b,1);
        customer=createCustomer("客户","cash-dispatch-repair");address=createAddress(customer,"一楼");
        product=createProduct("水",1,"15.00","30.00",0,"0.00");
        at=staffToken(am,"STATION_MANAGER",a);bt=staffToken(bm,"STATION_MANAGER",b);
    }
    long paidOrder(long holder,String agreed) {
        long id=createOrderCrossStation(customer,address,a,b,product,4,2,2,"15.00","0.00","20.00");
        jdbc.update("update orders set settle_station_id=?,delivery_fee=4,floor_fee=1 where id=?",b,id);
        insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount,barrel_deposit,delivery_fee,floor_fee) values(?,?,?,2,2,20,15,0,4,1)",id,customer,holder);
        jdbc.update("insert into dispatch_agreement(order_id,source_station_id,target_station_id,service_amount,net_barrels,barrel_mode,barrel_amount,barrel_items,status,accepted_by,accepted_time,create_time) values(?,?,?, ?,0,'RETURN_EMPTY',0,'','ACCEPTED',?,now(),now())",id,a,b,new BigDecimal(agreed),bm);
        return id;
    }
    JsonNode item(String token,long id) {
        Api r=get(LEDGER,token);assertEquals(0,r.code(),r.toString());
        for(JsonNode row:r.data().path("items"))if(row.path("orderId").asLong()==id)return row;
        return null;
    }
    void money(String expected,JsonNode amount) { assertEquals(0,new BigDecimal(expected).compareTo(amount.decimalValue())); }
    void settle(long id,String payer) {
        Api r=post(LEDGER+"/"+id+"/settle",payer,"{\"note\":\"双方核实并已实际交款\"}");assertEquals(0,r.code(),r.toString());
    }
    void refund(long id,String scope) {
        long p=longOf("select id from payment_record where order_id=? and amount>0",id);
        Api r=put("/api/payments/"+p+"/refund",bt,"{\"scope\":\""+scope+"\",\"note\":\"已实际退给客户\"}");assertEquals(0,r.code(),r.toString());
    }

    @Test void receiverCollectedTwentyAndAcceptedTwentyFiveRequiresFiveFromSource() {
        seed();long id=paidOrder(b,"25.00");JsonNode ai=item(at,id),bi=item(bt,id);
        assertNotNull(ai);assertNotNull(bi);money("5.00",ai.path("amount"));money("5.00",bi.path("amount"));
        assertEquals(a,ai.path("fromStationId").asLong());assertEquals(b,ai.path("toStationId").asLong());
        assertEquals("PAY",ai.path("direction").asText());assertEquals("RECEIVE",bi.path("direction").asText());
        assertEquals(1,post(LEDGER+"/"+id+"/settle",bt,"{}").code());settle(id,at);settle(id,at);
        assertEquals(1,intOf("select count(*) from inter_station_settlement where order_id=?",id));
        assertEquals(0,new BigDecimal("5.00").compareTo(decimalOf("select amount from inter_station_settlement where order_id=?",id)));
        assertEquals(0,new BigDecimal("25.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?",id)));
    }

    @Test void lowerFixedRewardReversesPayerAndEqualRewardHasNoCashDifference() {
        seed();long lower=paidOrder(b,"15.00"),equal=paidOrder(b,"20.00");JsonNode row=item(bt,lower);
        assertNotNull(row);money("5.00",row.path("amount"));assertEquals(b,row.path("fromStationId").asLong());assertEquals(a,row.path("toStationId").asLong());
        assertEquals(1,post(LEDGER+"/"+lower+"/settle",at,"{}").code());settle(lower,bt);
        assertNull(item(at,equal));assertNull(item(bt,equal));
    }

    @Test void sourceHeldCustomerCashStillPaysWholeFixedRewardToReceiver() {
        seed();long id=paidOrder(a,"25.00");JsonNode row=item(at,id);assertNotNull(row);
        money("25.00",row.path("amount"));assertEquals(a,row.path("fromStationId").asLong());assertEquals(b,row.path("toStationId").asLong());
    }

    @Test void partialCustomerRefundDoesNotSilentlyRewriteAcceptedRewardOrRepeatCustomerCashTransfer() {
        seed();long id=paidOrder(b,"25.00");refund(id,"WATER");
        assertEquals(2,intOf("select payment_status from orders where id=?",id));money("5.00",item(at,id).path("amount"));
        assertEquals(0,new BigDecimal("15.00").compareTo(decimalOf("select -sum(amount) from payment_record where order_id=? and amount<0",id)));
        settle(id,at);assertEquals(0,new BigDecimal("25.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?",id)));
    }

    @Test void fullRefundOfReceiverHeldCashReturnsEntireSupplementRatherThanDeductingCustomerOriginalCashAgain() {
        seed();long id=paidOrder(b,"25.00");settle(id,at);refund(id,"ALL_CONSUMPTION");
        assertEquals(0,post(LEDGER+"/"+id+"/reverse",bt,"{}").code());
        assertEquals(0,new BigDecimal("5.00").compareTo(decimalOf("select amount from inter_station_recovery where order_id=?",id)));
        assertEquals(b,longOf("select from_station_id from inter_station_recovery where order_id=?",id));assertEquals(a,longOf("select to_station_id from inter_station_recovery where order_id=?",id));
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+id+"/sent",bt,"{\"note\":\"已交回补差5元\"}").code());
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+id+"/received",at,"{}").code());
        assertEquals("RECEIVED",jdbc.queryForObject("select status from inter_station_recovery where order_id=?",String.class,id));
        assertEquals(0,post(LEDGER+"/"+id+"/reverse",bt,"{}").code());assertEquals(1,intOf("select count(*) from inter_station_recovery where order_id=?",id));
    }

    @Test void fullRefundAfterSourcePaidWholeRewardRecoversOnlyUnspentFive() {
        seed();long id=paidOrder(a,"25.00");settle(id,at);refund(id,"ALL_CONSUMPTION");
        assertEquals(0,post(LEDGER+"/"+id+"/reverse",at,"{}").code());
        assertEquals(0,new BigDecimal("5.00").compareTo(decimalOf("select amount from inter_station_recovery where order_id=?",id)));
    }

    @Test void receiverAdvancedFullCustomerRefundBeforeStationSettlementPreservesSourceDebtTwenty() {
        seed();long id=paidOrder(a,"25.00");refund(id,"ALL_CONSUMPTION");
        assertEquals(0,intOf("select count(*) from inter_station_settlement where order_id=?",id));
        assertEquals(a,longOf("select from_station_id from inter_station_recovery where order_id=?",id));
        assertEquals(b,longOf("select to_station_id from inter_station_recovery where order_id=?",id));
        assertEquals(0,new BigDecimal("20.00").compareTo(decimalOf("select amount from inter_station_recovery where order_id=?",id)));
        assertNull(item(at,id));assertNull(item(bt,id));
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+id+"/sent",at,"{\"note\":\"交回乙站实际垫退的20元\"}").code());
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+id+"/received",bt,"{}").code());
        assertEquals(1,post("/api/manager/inter-station-recoveries/"+id+"/received",bt,"{}").code());
    }

    @Test void reversePriceDifferenceAfterFullRefundReturnsFiveToReceiver() {
        seed();long id=paidOrder(b,"15.00");settle(id,bt);refund(id,"ALL_CONSUMPTION");
        assertEquals(a,longOf("select from_station_id from inter_station_recovery where order_id=?",id));
        assertEquals(b,longOf("select to_station_id from inter_station_recovery where order_id=?",id));
        assertEquals(0,new BigDecimal("5.00").compareTo(decimalOf("select amount from inter_station_recovery where order_id=?",id)));
        assertEquals(0,post(LEDGER+"/"+id+"/reverse",bt,"{}").code());
        assertEquals(1,intOf("select count(*) from inter_station_recovery where order_id=?",id));
    }

    @Test void failedRecoveryEvidenceRollsBackEntireCustomerRefund() {
        seed();long id=paidOrder(a,"25.00");
        jdbc.update("insert into inter_station_recovery(order_id,from_station_id,to_station_id,amount,status,note,create_time) values(?,?,?,1,'PENDING','故障注入：不一致凭据',now())",id,b,a);
        long p=longOf("select id from payment_record where order_id=? and amount>0",id);
        Api r=put("/api/payments/"+p+"/refund",bt,"{\"scope\":\"ALL_CONSUMPTION\"}");assertEquals(1,r.code(),r.toString());
        assertEquals(2,intOf("select payment_status from orders where id=?",id));assertEquals(2,intOf("select status from payment_record where id=?",p));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and amount<0",id));
        assertEquals(0,intOf("select count(*) from consumption_refund where order_id=?",id));
        assertEquals(0,new BigDecimal("1.00").compareTo(decimalOf("select amount from inter_station_recovery where order_id=?",id)));
    }

    @Test void concurrentWaterAndServiceRefundsUseCurrentCashFactsForReverseDifference() throws Exception {
        seed();long id=paidOrder(b,"15.00");settle(id,bt);
        long p=longOf("select id from payment_record where order_id=? and amount>0",id);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> water=pool.submit(()->{start.await();return put("/api/payments/"+p+"/refund",bt,"{\"scope\":\"WATER\"}");});
            Future<Api> service=pool.submit(()->{start.await();return put("/api/payments/"+p+"/refund",bt,"{\"scope\":\"SERVICE\"}");});start.countDown();
            for(Api r:List.of(water.get(30,TimeUnit.SECONDS),service.get(30,TimeUnit.SECONDS)))assertEquals(0,r.code(),r.toString());
            assertEquals(a,longOf("select from_station_id from inter_station_recovery where order_id=?",id));
            assertEquals(b,longOf("select to_station_id from inter_station_recovery where order_id=?",id));
            assertEquals(0,new BigDecimal("5.00").compareTo(decimalOf("select amount from inter_station_recovery where order_id=?",id)));
            assertEquals(0,new BigDecimal("20.00").compareTo(decimalOf("select -sum(amount) from payment_record where order_id=? and amount<0",id)));
        } finally {pool.shutdownNow();}
    }
}
