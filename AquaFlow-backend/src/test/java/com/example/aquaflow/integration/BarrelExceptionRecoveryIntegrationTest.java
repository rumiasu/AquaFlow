package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.mapper.OrderBarrelExceptionMapper;
import com.example.aquaflow.service.BarrelExceptionRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真 HTTP / MySQL：只由统一测试目标保护器批准的独立会话库执行。 */
class BarrelExceptionRecoveryIntegrationTest extends AbstractIntegrationTest {
    @Autowired PlatformTransactionManager transactions;
    @Autowired BarrelExceptionRecoveryService recovery;
    @Autowired OrderBarrelExceptionMapper exceptions;
    long station,customer,address,product,manager;String token;
    void seed() {
        station=createStation("回桶核实站");customer=createCustomer("归还测试客户","return-evidence-user");
        address=createAddress(customer,"合成测试地址");product=createProduct("桶装水",1,"20","30",1,"10");
        createInventoryFull(station,product,100,1,"10");manager=createStaff("归还站长","STATION_MANAGER",station,1);
        createCustomerStationConfig(customer,station,1);token=staffToken(manager,"STATION_MANAGER",station);
    }
    long deliver(int qty) {
        long order=createOrderFull(customer,address,station,product,2,2,2,"20","0","20",false,qty);
        createReservedItem(order,product,"桶装水",qty,"20","0");
        var result=post("/api/delivery/orders/"+order+"/complete",token,"{\"returnBucketQty\":0}");
        assertEquals(0,result.code(),result.toString());
        return longOf("select id from order_barrel_exception where order_id=?",order);
    }
    Api giveBack(int qty,String key) {
        return post("/api/barrels/return-empty",token,"{\"customerId\":"+customer+",\"clientToken\":\""+key+"\",\"items\":[{\"productId\":"+product+",\"qty\":"+qty+"}]}");
    }
    String status(long id) {return jdbc.queryForObject("select status from order_barrel_exception where id=?",String.class,id);}
    String resolveBody(String key) {return "{\"expectedVersion\":0,\"idempotencyKey\":\""+key+"\",\"reason\":\"已核对原配送和实际归还凭据\"}";}
    long historicReceipt(int qty) {
        int before=intOf("select over_qty from customer_barrel_over where customer_id=? and station_id=? and product_id=?",customer,station,product);
        jdbc.update("update customer_barrel_over set over_qty=over_qty-? where customer_id=? and station_id=? and product_id=?",qty,customer,station,product);
        return insert("insert into barrel_record(customer_id,station_id,product_id,type,quantity,status,deposit_refund,over_before,over_after,operator_id) values(?,?,?,7,?,3,0,?,?,?)",
                customer,station,product,qty,before,before-qty,manager);
    }
    @Test void actualReturnSynchronizesBothListsAndOneReceiptWithoutChangingOriginalFacts() {
        seed();long id=deliver(2);
        assertTrue(get("/api/manager/exceptions/"+id,token).data().path("canReturnEmpty").asBoolean());
        assertEquals(0,giveBack(2,"returned-once").code());assertEquals("RETURNED",status(id));
        assertEquals(2,intOf("select discrepancy from order_barrel_exception where id=?",id));
        assertEquals(0,intOf("select over_qty from customer_barrel_over where customer_id=?",customer));
        assertEquals(0,get("/api/manager/exceptions?status=STAFF_RECORDED",token).data().path("records").size());
        assertEquals("欠桶已归还",get("/api/manager/exceptions/"+id,token).data().path("statusText").asText());
        assertTrue(get("/api/manager/exceptions/"+id+"/resolution",token).data().path("actions").get(0).path("reason").asText().contains("实际归还凭据"));
        assertEquals(0,giveBack(2,"returned-once").code());
        assertEquals(1,intOf("select count(*) from barrel_record where type=7"));
        assertEquals(1,intOf("select count(*) from case_handling_action where kind='EXCEPTION' and case_id=?",id));
        assertEquals(0,intOf("select count(*) from deposit_record"));
    }
    @Test void partialReturnDoesNotAllocateAcrossOrdersAndAllClearedClosesBoth() {
        seed();long first=deliver(1),second=deliver(2);
        assertEquals(0,giveBack(1,"partial-return").code());assertEquals("STAFF_RECORDED",status(first));assertEquals("STAFF_RECORDED",status(second));
        assertEquals(0,giveBack(2,"remaining-return").code());assertEquals("RETURNED",status(first));assertEquals("RETURNED",status(second));
    }
    @Test void historicalReturnCanBeVerifiedOnceEvenAfterNewDebtWithoutChangingAccounts() {
        seed();long old=deliver(2);historicReceipt(2);long next=deliver(1);
        var view=get("/api/manager/exceptions/"+old+"/resolution",token);assertTrue(view.data().path("canResolve").asBoolean(),view.toString());
        assertEquals("STAFF_RECORDED",status(old),"读不写状态");
        assertFalse(get("/api/manager/exceptions/"+next+"/resolution",token).data().path("canResolve").asBoolean());
        String body=resolveBody("historic-verified");
        assertEquals(0,post("/api/manager/exceptions/"+old+"/resolve",token,body).code());
        assertEquals(0,post("/api/manager/exceptions/"+old+"/resolve",token,body).code());
        assertEquals("RETURNED",status(old));assertEquals("STAFF_RECORDED",status(next));
        assertEquals(1,intOf("select over_qty from customer_barrel_over where customer_id=?",customer));
        assertEquals(1,intOf("select count(*) from case_handling_action"));
    }
    @Test void missingProvenanceCannotBeBackfilledByNetZero() {
        seed();long id=deliver(2);jdbc.update("delete from barrel_delivery_receipt");
        assertEquals(0,giveBack(2,"unknown-history").code());assertEquals("STAFF_RECORDED",status(id));
        var view=get("/api/manager/exceptions/"+id+"/resolution",token);assertFalse(view.data().path("canResolve").asBoolean());
        assertEquals(1,post("/api/manager/exceptions/"+id+"/resolve",token,resolveBody("missing-source")).code());
    }
    @Test void staleVersionOtherStationAndDifferentReasonCannotCloseHistoricalCase() {
        seed();long id=deliver(1);historicReceipt(1);
        long other=createStation("另一站"),staff=createStaff("另一站长","STATION_MANAGER",other,1);
        String otherToken=staffToken(staff,"STATION_MANAGER",other);
        assertEquals(1,post("/api/manager/exceptions/"+id+"/resolve",otherToken,resolveBody("wrong-station")).code());
        assertEquals(1,post("/api/manager/exceptions/"+id+"/resolve",token,resolveBody("stale").replace(":0,",":1,")).code());
        String body=resolveBody("exact-intent");assertEquals(0,post("/api/manager/exceptions/"+id+"/resolve",token,body).code());
        assertEquals(1,post("/api/manager/exceptions/"+id+"/resolve",token,body.replace("已核对","尚未核对")).code());
    }
    @Test void concurrentDuplicateReturnNeverWritesTwoReceipts() throws Exception {
        seed();long id=deliver(2);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> a=pool.submit(()->{start.await();return giveBack(2,"same-return");});
            Future<Api> b=pool.submit(()->{start.await();return giveBack(2,"same-return");});start.countDown();
            assertEquals(0,a.get(30,TimeUnit.SECONDS).code());assertEquals(0,b.get(30,TimeUnit.SECONDS).code());
            assertEquals("RETURNED",status(id));assertEquals(1,intOf("select count(*) from barrel_record where type=7"));
            assertEquals(1,intOf("select count(*) from case_handling_action"));
        } finally {pool.shutdownNow();}
    }
    @Test void sourceMismatchAndOtherPendingDutiesDoNotBlockReturnOrCloseCase() {
        seed();long id=deliver(2);jdbc.update("update order_barrel_exception set water_owed=1 where id=?",id);
        assertEquals(0,giveBack(2,"owed-water").code());assertEquals("STAFF_RECORDED",status(id));
        jdbc.update("update order_barrel_exception set water_owed=0,delivery_qty=3 where id=?",id);
        assertFalse(get("/api/manager/exceptions/"+id+"/resolution",token).data().path("canResolve").asBoolean());
    }
    @Test void separateProductsNeedTheirOwnReceiptsAndNoCrossProductOffset() {
        seed();long second=createProduct("另一种桶",1,"20","30",1,"10");createInventoryFull(station,second,100,1,"10");
        long order=createOrderFull(customer,address,station,product,2,2,2,"60","0","60",false,3);
        long a=createReservedItem(order,product,"桶装水",1,"20","0"),b=createReservedItem(order,second,"另一种桶",2,"20","0");
        String body="{\"itemReturns\":[{\"orderItemId\":"+a+",\"actual\":0},{\"orderItemId\":"+b+",\"actual\":0}]}";
        var delivered=post("/api/delivery/orders/"+order+"/complete",token,body);assertEquals(0,delivered.code(),delivered.toString());
        long id=longOf("select id from order_barrel_exception where order_id=?",order);
        assertEquals(0,giveBack(1,"product-a-return").code());assertEquals("STAFF_RECORDED",status(id));
        product=second;assertEquals(0,giveBack(2,"product-b-return").code());assertEquals("RETURNED",status(id));
        assertTrue(jdbc.queryForObject("select reason from case_handling_action where case_id=?",String.class,id).contains("实际归还凭据"));
    }
    @Test void malformedOwnerAndDuplicateExceptionRemainUnresolved() {
        seed();long id=deliver(1);historicReceipt(1);
        long order=longOf("select order_id from order_barrel_exception where id=?",id);
        jdbc.update("update orders set station_id=? where id=?",createStation("错站"),order);
        assertFalse(get("/api/manager/exceptions/"+id+"/resolution",token).data().path("canResolve").asBoolean());
        jdbc.update("update orders set station_id=? where id=?",station,order);
        jdbc.update("insert into order_barrel_exception(order_id,customer_id,station_id,category,type,delivery_qty,return_qty,discrepancy,manager_action) values(?,?,?,'RETURN_SHORT','SHORT_RETURN',1,0,1,null)",order,customer,station);
        assertFalse(get("/api/manager/exceptions/"+id+"/resolution",token).data().path("canResolve").asBoolean());
    }
    @Test void failedRecoveryReceiptRollsBackThePhysicalReturnAndStatus() {
        seed();long id=deliver(1);
        jdbc.execute("create trigger reject_recovery_action before insert on case_handling_action for each row signal sqlstate '45000' set message_text='synthetic recovery failure'");
        try {
            assertFalse(giveBack(1,"receipt-write-fails").isSuccess());
            assertEquals("STAFF_RECORDED",status(id));
            assertEquals(1,intOf("select over_qty from customer_barrel_over where customer_id=?",customer));
            assertEquals(0,intOf("select count(*) from barrel_record where type=7"));
            assertEquals(0,intOf("select count(*) from case_handling_action"));
        } finally {jdbc.execute("drop trigger reject_recovery_action");}
        assertEquals(0,giveBack(1,"receipt-write-fails").code());assertEquals("RETURNED",status(id));
    }
    @Test void candidateScanWindowKeepsLaterCaseForExplicitHistoricalVerification() {
        seed();TransactionTemplate tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(s->{
            var before=recovery.lockCandidates(customer,station);assertTrue(before.isEmpty());
            // 模拟首扫之后完成配送的新异常：其提交不被 RC 空范围间隙锁阻塞。
            long id=CompletableFuture.supplyAsync(()->deliver(1)).orTimeout(30,TimeUnit.SECONDS).join();
            long receipt=historicReceipt(1);
            recovery.completeReturned(before,manager,Set.of(receipt));assertEquals("STAFF_RECORDED",status(id));
            assertTrue(recovery.evidence(exceptions.getById(id)).returned());
        });
    }
}
