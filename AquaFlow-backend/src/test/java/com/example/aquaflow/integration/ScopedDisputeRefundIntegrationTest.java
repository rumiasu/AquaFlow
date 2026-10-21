package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import com.example.aquaflow.config.ScopedRefusalRefundSchemaGuard;
import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP/合成MySQL：50元原押金中只冻12，38实退与12待退不混为终态。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=false"})
class ScopedDisputeRefundIntegrationTest extends AbstractIntegrationTest {
    @Autowired ReconciliationService reconciliation;
    @Autowired ScopedRefusalRefundSchemaGuard schemaGuard;
    long station,debtStation,customer,product,order,record,managerId;
    String manager,owner,debtorManager,delivery,stranger;
    void seed() {
        station=createStation("资产归属站");debtStation=createStation("债权原站");
        managerId=createStaff("资产站长","STATION_MANAGER",station,1);manager=staffToken(managerId,"STATION_MANAGER",station);
        debtorManager=staffToken(createStaff("债权站长","STATION_MANAGER",debtStation,1),"STATION_MANAGER",debtStation);
        delivery=staffToken(createStaff("配送","DELIVERY",station,1),"DELIVERY",station);
        long third=createStation("无关站");stranger=staffToken(createStaff("无关站长","STATION_MANAGER",third,1),"STATION_MANAGER",third);
        customer=createCustomer("合成客户","scoped-refund-customer");owner=customerToken(customer);
        product=createProduct("已核实桶型",1,"20.00","50.00",0,"0.00");
        createInventoryFull(station,product,10,0,"0.00");createInventoryRecord(station,product,10,"STOCK_IN",0);
        Api buy=post("/api/barrel-rights/purchase",owner,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"paymentMethod\":2,\"idempotencyKey\":\"opening-right\"}");
        assertEquals(0,buy.code(),buy.toString());assertEquals(0,put("/api/payments/"+buy.data().path("paymentId").asLong()+"/confirm",manager,null).code());
        long address=createAddress(customer,"合成地址");
        order=createOrderCrossStation(customer,address,station,debtStation,product,3,1,2,"20.00","0.00","20.00");
        jdbc.update("update orders set settle_station_id=? where id=?",debtStation,order);
        jdbc.update("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,note,create_time) values(?,1,?,?,?,0,?,'原单20元拒付',now())",order,customer,station,debtStation,managerId);
    }
    Api scope(String token,String key,int version,String amount) {
        return put("/api/manager/refusal-cases/"+order+"/confirm-freeze",token,
                "{\"expectedVersion\":"+version+",\"idempotencyKey\":\""+key+"\",\"items\":[{\"productId\":"+product+",\"amount\":"+amount+"}]}");
    }
    void requestAndReceive() {
        Api apply=post("/api/barrels/return",owner,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"pickupMode\":\"STORE\",\"idempotencyKey\":\"original-return\"}");
        assertEquals(0,apply.code(),apply.toString());record=apply.data().path("recordId").asLong();assertTrue(record>0);
        assertEquals(0,put("/api/barrels/records/"+record+"/approve",manager,"{\"pickupFee\":0}").code());
        assertEquals(0,put("/api/barrels/records/"+record+"/status",manager,"{\"status\":2}").code());
    }
    Api eligibility() {return get("/api/barrels/records/"+record+"/refund-eligibility?scopedRefunds=true",manager);}
    Api pay(String token,String key,String amount) {
        return put("/api/barrels/records/"+record+"/status",token,"{\"status\":3,\"refundChannel\":\"CASH\",\"cashDeliveryConfirmed\":true,\"expectedRefundAmount\":"+amount+",\"payoutRequestKey\":\""+key+"\"}");
    }
    Map<String,Object> facts() {
        var result=new LinkedHashMap<String,Object>();
        for(String table:List.of("orders","payment_record","deposit_record","customer_deposit_account","customer_barrel_lot","customer_barrel_asset","customer_barrel_over","barrel_return_detail","barrel_return_payout","barrel_purchase_refund","barrel_record_lot","barrel_right_reservation","barrel_record","order_barrel_purchase","order_barrel_refund","customer_station_config"))result.put(table,jdbc.queryForList("select * from "+table));
        return result;
    }
    void release() {assertEquals(0,post("/api/manager/refusal-cases/"+order+"/release-freeze",manager,"{\"expectedVersion\":1,\"idempotencyKey\":\"release-one\",\"reason\":\"本站核清后解除本案\"}").code());}

    @Test void partialActualRefundRetainsClaimAndOriginalRequestRecoversThenCompletes() {
        seed();assertEquals(0,scope(manager,"scope-12",0,"12.00").code());requestAndReceive();
        var before=reconciliation.runReconcile();
        var quote=eligibility();assertEquals(0,quote.code(),quote.toString());assertTrue(quote.data().path("available").asBoolean(),quote.toString());
        assertEquals(38,quote.data().path("refundAmount").asInt());assertEquals(12,quote.data().path("frozenRefundAmount").asInt());
        assertFalse(get("/api/barrels/records/"+record+"/refund-eligibility",manager).data().path("available").asBoolean(),"旧端不能先交部分现金再被旧写入口拒绝");
        Api first=pay(manager,"cash-first","38.00");assertEquals(0,first.code(),first.toString());
        assertEquals(12,first.data().path("progress").path("remaining").asInt());
        assertEquals("RECEIVED",jdbc.queryForObject("select status from barrel_return_detail where record_id=?",String.class,record));
        assertEquals(0,new BigDecimal("12.00").compareTo(decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station)));
        assertEquals(1,intOf("select remain_qty from customer_barrel_lot where customer_id=? and product_id=?",customer,product),"未退齐仍保留原占用，不能再领桶");
        var paid=facts();assertEquals(0,pay(manager,"cash-first","38.00").code());assertEquals(paid,facts());
        assertNotEquals(0,pay(manager,"cash-first","39.00").code());assertNotEquals(0,pay(manager,"other-key","12.00").code());assertEquals(paid,facts());
        assertEquals(before,reconciliation.runReconcile(),"分次实退不能制造账差");
        release();assertEquals(paid,facts(),"解冻仅变案件及审计，不能顺便出钱或改资产");
        Api last=pay(manager,"cash-last","12.00");assertEquals(0,last.code(),last.toString());
        assertEquals(0,last.data().path("progress").path("remaining").asInt());assertEquals(50,last.data().path("progress").path("paid").asInt());
        assertEquals("REFUNDED",jdbc.queryForObject("select status from barrel_return_detail where record_id=?",String.class,record));
        assertEquals(0,intOf("select remain_qty from customer_barrel_lot where customer_id=? and product_id=?",customer,product));
        assertEquals(0,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).signum());
        var terminal=facts();assertEquals(0,pay(manager,"cash-first","38.00").code());assertEquals(0,pay(manager,"cash-last","12.00").code());assertEquals(terminal,facts());
        assertEquals(before,reconciliation.runReconcile());
    }
    @Test void scopePermissionAmountBoundAndOriginalKeyAreEnforcedWithoutMoneyWrites() {
        seed();var original=facts();
        for(String token:List.of(owner,debtorManager,delivery,stranger))assertNotEquals(0,scope(token,"scope-denied",0,"12.00").code());
        for(String amount:List.of("0","-1","20.01","0.001"))assertNotEquals(0,scope(manager,"bad-scope",0,amount).code());
        assertEquals(original,facts());assertEquals(0,scope(manager,"scope-original",0,"12.00").code());
        var first=jdbc.queryForList("select * from customer_refusal_action");assertEquals(0,scope(manager,"scope-original",0,"12.00").code());assertEquals(first,jdbc.queryForList("select * from customer_refusal_action"));
        assertNotEquals(0,scope(manager,"scope-original",0,"13.00").code());assertEquals(original,facts());
        assertTrue(String.valueOf(first.get(0).get("scope_json")).contains("12.00"));
    }
    @Test void concurrentOriginalCashRequestRecordsOnlyOneActualRefund() throws Exception {
        seed();assertEquals(0,scope(manager,"scope-concurrent",0,"12").code());requestAndReceive();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return pay(manager,"same-cash-request","38.00");});
            var two=pool.submit(()->{start.await();return pay(manager,"same-cash-request","38.00");});start.countDown();
            assertEquals(0,one.get(40,TimeUnit.SECONDS).code());assertEquals(0,two.get(40,TimeUnit.SECONDS).code());
            assertEquals(1,intOf("select count(*) from barrel_return_payout"));assertEquals(1,intOf("select count(*) from barrel_purchase_refund where payout_id is not null"));
            assertEquals(0,new BigDecimal("12.00").compareTo(decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station)));
        } finally {pool.shutdownNow();}
    }

    @Test void concurrentReleaseDoesNotLoseAlreadyHandedOriginalPartialCashOrRefundItTwice() throws Exception {
        seed();assertEquals(0,scope(manager,"scope-before-release",0,"12").code());requestAndReceive();
        assertEquals(38,eligibility().data().path("refundAmount").asInt());
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var cash=pool.submit(()->{start.await();return pay(manager,"original-handed-38","38");});
            var unfreeze=pool.submit(()->{start.await();return post("/api/manager/refusal-cases/"+order+"/release-freeze",manager,"{\"expectedVersion\":1,\"idempotencyKey\":\"concurrent-release\",\"reason\":\"资产站核清本案\"}");});start.countDown();
            assertEquals(0,cash.get(40,TimeUnit.SECONDS).code());assertEquals(0,unfreeze.get(40,TimeUnit.SECONDS).code());
            assertEquals(12,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).intValueExact());
            assertEquals(12,eligibility().data().path("refundAmount").asInt());
            var recorded=facts();assertEquals(0,pay(manager,"original-handed-38","38").code());assertEquals(recorded,facts());
            assertEquals(0,pay(manager,"remaining-12","12").code());
            assertEquals(2,intOf("select count(*) from barrel_return_payout"));
        } finally {pool.shutdownNow();}
    }

    @Test void scopeAmendmentKeepsOriginalAuditAndReplayingOldRequestDoesNotRestoreOldScope() throws Exception {
        seed();assertEquals(0,scope(manager,"scope-original-12",0,"12").code());
        assertEquals(0,scope(manager,"scope-narrow-5",1,"5").code());
        assertEquals(0,scope(manager,"scope-original-12",0,"12").code());
        assertEquals(0,new BigDecimal("5.00").compareTo(decimalOf("select amount from customer_refusal_freeze_scope where order_id=?",order)));
        assertEquals(2,intOf("select count(*) from customer_refusal_action where order_id=?",order));
        var events=jdbc.queryForList("select scope_json from customer_refusal_action where order_id=? order by id",String.class,order);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(12,json.readTree(events.get(0)).get(0).path("amount").asInt());assertEquals(5,json.readTree(events.get(1)).get(0).path("amount").asInt());
        requestAndReceive();assertEquals(45,eligibility().data().path("refundAmount").asInt());
    }

    @Test void releasingOneCaseLeavesOtherCaseAmountAndRemainderIntact() {
        seed();long firstOrder=order;assertEquals(0,scope(manager,"case-one-12",0,"12").code());
        long address=createAddress(customer,"第二原单地址");
        order=createOrderCrossStation(customer,address,station,debtStation,product,3,1,2,"20.00","0.00","20.00");
        jdbc.update("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,note,create_time) values(?,2,?,?,?,0,?,'第二原单拒付',now())",order,customer,station,debtStation,managerId);
        long secondOrder=order;assertEquals(0,scope(manager,"case-two-8",0,"8").code());requestAndReceive();
        assertEquals(30,eligibility().data().path("refundAmount").asInt());assertEquals(0,pay(manager,"cash-30","30").code());
        order=firstOrder;release();assertEquals(12,eligibility().data().path("refundAmount").asInt());assertEquals(0,pay(manager,"cash-12","12").code());
        assertEquals(8,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).intValueExact());
        assertFalse(eligibility().data().path("available").asBoolean());
        assertEquals(0,intOf("select asset_freeze_released from customer_refusal_resolution where order_id=?",secondOrder));
        order=secondOrder;release();assertEquals(0,pay(manager,"cash-8","8").code());
        assertEquals(3,intOf("select count(*) from barrel_return_payout"));assertEquals(0,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).signum());
    }

    @Test void unrelatedProductInTheSameOriginalOrderCanExitWithoutExpandingDeclaredScope() {
        seed();long disputed=product;
        long other=createProduct("已知无争议商品",1,"10.00","50.00",0,"0.00");
        createInventoryFull(station,other,10,0,"0.00");createInventoryRecord(station,other,10,"STOCK_IN",0);
        createBarrelLot("OTHER-HISTORICAL-RIGHT",customer,station,other,"50.00",1,1);createBarrelAsset(customer,station,other,1,"50.00");
        jdbc.update("update customer_deposit_account set balance=balance+50 where customer_id=? and station_id=?",customer,station);
        jdbc.update("insert into deposit_record(customer_id,station_id,product_id,type,amount,quantity,operator_id,note) values(?,?,?,1,50,1,?,'独立合成历史开账')",customer,station,other,managerId);
        createOrderItem(order,disputed,"原单商品甲",1,"10.00","0.00",1);createOrderItem(order,other,"原单商品乙",1,"10.00","0.00",1);
        assertEquals(0,scope(manager,"only-disputed-product",0,"12").code());
        product=other;requestAndReceive();assertEquals(50,eligibility().data().path("refundAmount").asInt());
        assertEquals(0,pay(manager,"unrelated-cash-50","50").code());
        assertEquals(50,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).intValueExact());
        assertEquals(1,intOf("select remain_qty from customer_barrel_lot where customer_id=? and product_id=?",customer,disputed));
        assertEquals(12,decimalOf("select amount from customer_refusal_freeze_scope where order_id=?",order).intValueExact());
    }

    @Test void missingOriginalEvidenceDoesNotDisappearOrPermitAnUnverifiedPayment() {
        seed();assertEquals(0,scope(manager,"known-scope",0,"12").code());requestAndReceive();
        jdbc.update("delete from orders where id=?",order);
        var before=facts();assertFalse(eligibility().data().path("available").asBoolean());
        assertNotEquals(0,pay(manager,"must-not-pay","38").code());assertEquals(before,facts());
        // 列表原入口必须保留缺单案件，不将缺证据误判为已还清。
        var list=get("/api/manager/refusal-cases",manager);assertEquals(0,list.code());
        assertTrue(list.data().toString().contains("原单证据"));
    }

    @Test void failedPayoutProofRollsBackEveryMoneyAndAssetFactThenSameKeyCanRetry() {
        seed();assertEquals(0,scope(manager,"rollback-scope",0,"12").code());requestAndReceive();
        var before=facts();
        jdbc.execute("create trigger c03_fail_proof before insert on barrel_purchase_refund for each row signal sqlstate '45000' set message_text='synthetic proof failure'");
        try {assertNotEquals(0,pay(manager,"recover-original-cash","38").code());assertEquals(before,facts());}
        finally {jdbc.execute("drop trigger if exists c03_fail_proof");}
        assertEquals(0,pay(manager,"recover-original-cash","38").code());assertEquals(1,intOf("select count(*) from barrel_return_payout"));
    }

    @Test void finalClosureFailurePreservesCommittedPartialAndAllRemainingLiability() {
        seed();assertEquals(0,scope(manager,"final-scope",0,"12").code());requestAndReceive();
        assertEquals(0,pay(manager,"committed-38","38").code());release();var before=facts();
        jdbc.execute("create trigger c03_fail_close before update on barrel_record for each row begin if new.status=3 then signal sqlstate '45000' set message_text='synthetic closure failure'; end if; end");
        try {assertNotEquals(0,pay(manager,"remaining-original-12","12").code());assertEquals(before,facts());}
        finally {jdbc.execute("drop trigger if exists c03_fail_close");}
        assertEquals(0,pay(manager,"remaining-original-12","12").code());assertEquals(2,intOf("select count(*) from barrel_return_payout"));
        assertEquals(0,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).signum());
    }

    @Test void scopeAuditFailureRollsBackScopeAndVersionAndOriginalKeyRemainsRetryable() {
        seed();assertEquals(0,scope(manager,"scope-before-failure",0,"12").code());
        var scopeBefore=jdbc.queryForList("select * from customer_refusal_freeze_scope");
        var caseBefore=jdbc.queryForList("select * from customer_refusal_resolution");
        var auditBefore=jdbc.queryForList("select * from customer_refusal_action");var moneyBefore=facts();
        jdbc.execute("create trigger c03_fail_scope_audit before insert on customer_refusal_action for each row signal sqlstate '45000' set message_text='synthetic audit failure'");
        try {
            assertNotEquals(0,scope(manager,"scope-retry-same-key",1,"5").code());
            assertEquals(scopeBefore,jdbc.queryForList("select * from customer_refusal_freeze_scope"));
            assertEquals(caseBefore,jdbc.queryForList("select * from customer_refusal_resolution"));
            assertEquals(auditBefore,jdbc.queryForList("select * from customer_refusal_action"));assertEquals(moneyBefore,facts());
        } finally {jdbc.execute("drop trigger if exists c03_fail_scope_audit");}
        assertEquals(0,scope(manager,"scope-retry-same-key",1,"5").code());
        assertEquals(5,decimalOf("select amount from customer_refusal_freeze_scope where order_id=?",order).intValueExact());
    }

    @Test void wrongOrMissingProofIndexesAndCaseInsensitiveKeysRefuseStartup() {
        schemaGuard.verifySchema();
        jdbc.execute("alter table barrel_return_payout drop index uk_return_payout_key");
        try {assertThrows(IllegalStateException.class,()->schemaGuard.verifySchema());}
        finally {jdbc.execute("alter table barrel_return_payout add unique key uk_return_payout_key(record_id,request_key)");}
        jdbc.execute("alter table barrel_return_payout modify request_key varchar(64) character set ascii collate ascii_general_ci not null");
        try {assertThrows(IllegalStateException.class,()->schemaGuard.verifySchema());}
        finally {jdbc.execute("alter table barrel_return_payout modify request_key varchar(64) character set ascii collate ascii_bin not null");}
        jdbc.execute("alter table barrel_purchase_refund drop index uk_purchase_return");
        try {
            jdbc.execute("alter table barrel_purchase_refund add unique key uk_purchase_return(purchase_id,legacy_return_record_id,amount)");
            assertThrows(IllegalStateException.class,()->schemaGuard.verifySchema());
        } finally {
            if(intOf("select count(*) from information_schema.statistics where table_schema=database() and table_name='barrel_purchase_refund' and index_name='uk_purchase_return'")>0)jdbc.execute("alter table barrel_purchase_refund drop index uk_purchase_return");
            jdbc.execute("alter table barrel_purchase_refund add unique key uk_purchase_return(purchase_id,legacy_return_record_id)");
        }
        schemaGuard.verifySchema();
    }
}
