package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.aquaflow.service.StationRecoveryService;
import java.util.*;
import java.util.concurrent.*;

/** 合成HTTP/MySQL：已结20不因部分退12自动全追；责任按双方明确金额留痕。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","app.payment.mock-wechat-pay=false"})
class PartialStationAftercareIntegrationTest extends AbstractIntegrationTest {
    @Autowired PlatformTransactionManager transactions;
    @Autowired StationRecoveryService recoveries;
    @Autowired com.example.aquaflow.config.StationAftercareSchemaGuard schemaGuard;
    long a,b,order,payment,refund,am,bm;String at,bt;
    void seed() {
        a=createStation("归属站");b=createStation("履约站");am=createStaff("归属站长","STATION_MANAGER",a,1);bm=createStaff("履约站长","STATION_MANAGER",b,1);
        at=staffToken(am,"STATION_MANAGER",a);bt=staffToken(bm,"STATION_MANAGER",b);
        long c=createCustomer("合成售后客户","partial-aftercare"),address=createAddress(c,"合成地址"),product=createProduct("合成商品",2,"12","0",0,"0");
        order=createOrderCrossStation(c,address,a,b,product,4,2,2,"12","0","20");
        jdbc.update("update orders set settle_station_id=?,delivery_fee=8 where id=?",b,order);
        payment=createPaymentRecord(order,c,a,"20",2,2);jdbc.update("update payment_record set water_amount=12,barrel_deposit=0,delivery_fee=8,floor_fee=0 where id=?",payment);
        jdbc.update("insert into inter_station_settlement(order_id,from_station_id,to_station_id,amount,basis,status,snapshot_time,settled_time,settled_by) values(?,?,?,20,1,2,now(),now(),?)",order,a,b,am);
        Api water=put("/api/payments/"+payment+"/refund",bt,"{\"scope\":\"WATER\",\"expectedRefundAmount\":12}");assertEquals(0,water.code(),water.toString());
        refund=jdbc.queryForObject("select refund_payment_id from consumption_refund where order_id=?",Long.class,order);
    }
    @Test void settledPartialRefundHasExactResponsibilityEntryWithoutAutomaticallyRecoveringWholeReward() {
        seed();assertEquals(0,intOf("select count(*) from inter_station_recovery"));
        Api view=get("/api/manager/inter-station-aftercare/"+order,bt);assertEquals(0,view.code(),view.toString());
        assertEquals(1,view.data().path("items").size());assertEquals(12,view.data().path("items").get(0).path("refundAmount").asInt());
        assertTrue(view.data().path("items").get(0).path("refundVerified").asBoolean());
    }
    Api act(String token,String action,int version,String key,String amount,String reason) {
        return post("/api/manager/inter-station-aftercare/"+refund+"/"+action,token,
                "{\"expectedVersion\":"+version+",\"idempotencyKey\":\""+key+"\",\"amount\":"+amount+",\"reason\":\""+reason+"\"}");
    }
    Map<String,Object> moneyFacts() {
        var facts=new LinkedHashMap<String,Object>();
        for(String table:List.of("orders","payment_record","consumption_refund","inter_station_settlement","inter_station_recovery","deposit_record","customer_deposit_account","customer_barrel_lot","customer_barrel_asset"))facts.put(table,jdbc.queryForList("select * from "+table));
        return facts;
    }
    void agreeEight() {
        assertEquals(0,act(bt,"propose",0,"propose-eight","8","责任事实已核对").code());
        assertEquals(0,act(at,"agree",1,"agree-eight","null","").code());
    }
    @Test void onlyCounterpartyAgreesAndActualPayerRegistersExactAmountOnceWithoutTouchingCustomerMoney() {
        seed();var before=moneyFacts();assertEquals(0,act(bt,"propose",0,"propose-eight","8","责任事实已核对").code());
        assertNotEquals(0,act(bt,"agree",1,"self-agree","null","").code());
        long third=createStation("无关站");String outsider=staffToken(createStaff("无关站长","STATION_MANAGER",third,1),"STATION_MANAGER",third);
        assertNotEquals(0,act(outsider,"agree",1,"foreign-agree","null","").code());
        assertEquals(0,act(at,"agree",1,"agree-eight","null","").code());
        assertNotEquals(0,act(bt,"paid",2,"wrong-payer","8","").code());assertNotEquals(0,act(at,"paid",2,"wrong-amount","9","").code());
        assertEquals(0,act(at,"paid",2,"original-paid-eight","8","").code());
        var audit=jdbc.queryForList("select * from station_aftercare_action");
        assertEquals(0,act(at,"paid",2,"original-paid-eight","8","").code());assertEquals(audit,jdbc.queryForList("select * from station_aftercare_action"));
        assertNotEquals(0,act(at,"paid",2,"original-paid-eight","7","").code());
        assertEquals(before,moneyFacts());assertEquals("PAID",jdbc.queryForObject("select status from station_aftercare where refund_payment_id=?",String.class,refund));
        assertEquals(3,intOf("select count(*) from station_aftercare_action"));
    }
    @Test void counterproposalAndOldKeyReplayCannotRestoreAnEarlierResponsibilityAmount() {
        seed();assertEquals(0,act(bt,"propose",0,"old-eight","8","原方案").code());
        assertEquals(0,act(at,"propose",1,"counter-four","4","依据补充").code());
        assertEquals(0,act(bt,"propose",0,"old-eight","8","原方案").code());
        assertEquals(4,decimalOf("select proposed_amount from station_aftercare where refund_payment_id=?",refund).intValueExact());
        assertNotEquals(0,act(bt,"agree",1,"stale-agree","null","").code());
        assertEquals(0,act(bt,"agree",2,"agree-four","null","").code());
        assertNotEquals(0,act(at,"propose",3,"overwrite-agreed","8","不能覆盖").code());
        assertEquals(0,act(at,"paid",3,"paid-four","4","").code());
        assertEquals(4,intOf("select proposed_amount from station_aftercare"));
    }
    @Test void explicitZeroIsValidResponsibilityButMissingOrExcessiveMoneyCannotBeRegistered() {
        seed();var before=moneyFacts();
        for(String amount:List.of("null","-1","12.01","0.001"))assertNotEquals(0,act(bt,"propose",0,"invalid-amount",amount,"事实").code());
        assertEquals(0,act(bt,"propose",0,"explicit-zero","0","本笔由实际退款站承担").code());assertEquals(0,act(at,"agree",1,"agree-zero","null","").code());
        assertFalse(get("/api/manager/inter-station-aftercare/"+order,at).data().path("items").get(0).path("canPay").asBoolean());
        assertNotEquals(0,act(at,"paid",2,"not-a-real-payment","0","").code());assertEquals(before,moneyFacts());
    }
    @Test void concurrentSameOriginalPaymentKeyCreatesOnlyOneActualDeliveryRecord() throws Exception {
        seed();agreeEight();var before=moneyFacts();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return act(at,"paid",2,"same-original-delivery","8","");});
            var two=pool.submit(()->{start.await();return act(at,"paid",2,"same-original-delivery","8","");});start.countDown();
            assertEquals(0,one.get(40,TimeUnit.SECONDS).code());assertEquals(0,two.get(40,TimeUnit.SECONDS).code());
            assertEquals(1,intOf("select count(*) from station_aftercare_action where action='PAID'"));assertEquals(before,moneyFacts());
        } finally {pool.shutdownNow();}
    }
    @Test void failedAuditRollsBackProposalAndDeliveryAndOriginalKeysRemainRecoverable() {
        seed();var before=moneyFacts();
        jdbc.execute("create trigger c04_fail_audit before insert on station_aftercare_action for each row signal sqlstate '45000' set message_text='synthetic audit failure'");
        try {assertNotEquals(0,act(bt,"propose",0,"propose-eight","8","责任事实已核对").code());assertEquals(0,intOf("select count(*) from station_aftercare"));assertEquals(before,moneyFacts());}
        finally {jdbc.execute("drop trigger if exists c04_fail_audit");}
        agreeEight();var prior=jdbc.queryForList("select * from station_aftercare");
        jdbc.execute("create trigger c04_fail_audit before insert on station_aftercare_action for each row signal sqlstate '45000' set message_text='synthetic audit failure'");
        try {assertNotEquals(0,act(at,"paid",2,"original-delivery","8","").code());assertEquals(prior,jdbc.queryForList("select * from station_aftercare"));assertEquals(2,intOf("select count(*) from station_aftercare_action"));}
        finally {jdbc.execute("drop trigger if exists c04_fail_audit");}
        assertEquals(0,act(at,"paid",2,"original-delivery","8","").code());assertEquals(before,moneyFacts());
    }
    @Test void subsequentFullRefundRetainsAgreedPartialResponsibilityAndUnallocatedRemainderWithoutWholeRecovery() {
        seed();agreeEight();assertEquals(0,act(at,"paid",2,"paid-before-full","8","").code());
        Api last=put("/api/payments/"+payment+"/refund",bt,"{\"scope\":\"SERVICE\",\"expectedRefundAmount\":8}");assertEquals(0,last.code(),last.toString());
        assertEquals(0,intOf("select count(*) from inter_station_recovery"));assertEquals(20,intOf("select amount from inter_station_settlement"));
        var view=get("/api/manager/inter-station-aftercare/"+order,bt);assertEquals(2,view.data().path("items").size());
        assertEquals("PAID",view.data().path("items").get(0).path("status").asText());assertEquals("UNRESOLVED",view.data().path("items").get(1).path("status").asText());
        assertTrue(view.data().path("items").get(1).path("canPropose").asBoolean(),view.toString());
        var before=moneyFacts();assertEquals(0,act(at,"paid",2,"paid-before-full","8","").code());assertEquals(before,moneyFacts());
    }
    @Test void oldRepeatableReadSnapshotCannotCreateWholeRecoveryOverNewlyCommittedPartialCase() {
        seed();jdbc.update("update inter_station_settlement set amount=10 where order_id=?",order);
        var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{
            assertEquals(0,intOf("select count(*) from station_aftercare"));
            assertEquals(0,act(bt,"propose",0,"snapshot-new-case","8","责任事实").code());
            assertEquals(0,put("/api/payments/"+payment+"/refund",bt,"{\"scope\":\"SERVICE\",\"expectedRefundAmount\":8}").code());
            assertEquals(0,intOf("select count(*) from station_aftercare"),"外层确为旧RR快照");
            recoveries.preserveFullRefund(order);
            assertEquals(0,intOf("select count(*) from inter_station_recovery"),"不得借旧快照自动新增整单追收");
        });
        assertEquals(1,intOf("select count(*) from station_aftercare"));assertEquals(0,intOf("select count(*) from inter_station_recovery"));
    }
    @Test void missingOriginalKeyIndexOrCaseInsensitiveKeyRefusesStartup() {
        schemaGuard.verifySchema();jdbc.execute("alter table station_aftercare_action drop index uk_aftercare_action");
        try {assertThrows(IllegalStateException.class,()->schemaGuard.verifySchema());}
        finally {jdbc.execute("alter table station_aftercare_action add unique key uk_aftercare_action(refund_payment_id,actor_key,request_key)");}
        jdbc.execute("alter table station_aftercare_action modify request_key varchar(64) character set ascii collate ascii_general_ci not null");
        try {assertThrows(IllegalStateException.class,()->schemaGuard.verifySchema());}
        finally {jdbc.execute("alter table station_aftercare_action modify request_key varchar(64) character set ascii collate ascii_bin not null");}
        schemaGuard.verifySchema();
    }
    @Test void unknownOrConflictingActualRefundEvidenceNeverAuthorizesResponsibilityOrPaidRegistration() {
        seed();jdbc.update("update payment_record set status=4 where id=?",refund);var before=moneyFacts();
        var view=get("/api/manager/inter-station-aftercare/"+order,bt);assertFalse(view.data().path("items").get(0).path("canPropose").asBoolean());
        assertFalse(view.data().path("items").get(0).path("refundVerified").asBoolean());
        assertNotEquals(0,act(bt,"propose",0,"unknown-original","8","事实").code());assertEquals(before,moneyFacts());
        assertEquals(0,intOf("select count(*) from station_aftercare"));
    }
    @Test void remainingResponsibilityIsStillDiscoverableAfterOriginalSettlementWasReversed() {
        seed();agreeEight();assertEquals(0,act(at,"paid",2,"paid-before-reverse","8","").code());
        assertEquals(0,put("/api/payments/"+payment+"/refund",bt,"{\"scope\":\"SERVICE\",\"expectedRefundAmount\":8}").code());
        assertEquals(0,post("/api/manager/inter-station-settlements/"+order+"/reverse",bt,"{}").code());
        var ledger=get("/api/manager/inter-station-settlements",bt);assertEquals(0,ledger.code());
        assertTrue(ledger.data().path("aftercarePendingOrders").isArray(),ledger.toString());
        assertTrue(ledger.data().path("aftercarePendingOrders").toString().contains(String.valueOf(order)),"未分担的后续实退款不能随原结算冲销从办理入口消失");
    }

}
