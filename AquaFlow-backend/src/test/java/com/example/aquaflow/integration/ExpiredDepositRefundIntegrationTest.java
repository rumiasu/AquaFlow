package com.example.aquaflow.integration;

import com.example.aquaflow.support.*;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.service.*;
import com.example.aquaflow.payment.RefundRecoveryProtocol;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP/独立合成MySQL；原微信事实用隔离夹具和内存SDK签名，不执行微信交易。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=false"})
class ExpiredDepositRefundIntegrationTest extends AbstractIntegrationTest {
    @Autowired OriginalPaymentEvidenceService evidence;
    @Autowired PaymentRecordMapper payments;
    @Autowired ReconciliationService reconciliation;
    @Autowired com.example.aquaflow.config.OriginalPaymentEvidenceSchemaGuard schemaGuard;
    long station,customer,product,managerId,record;String manager,owner;
    void base() {
        station=createStation("C08合成资产站");managerId=createStaff("合成站长","STATION_MANAGER",station,1);manager=staffToken(managerId,"STATION_MANAGER",station);
        customer=createCustomer("合成原款客户","c08-source");owner=customerToken(customer);product=createProduct("合成桶装水",1,"20","50",0,"0");
        createInventoryFull(station,product,10,0,"0");createInventoryRecord(station,product,10,"STOCK_IN",0);createCustomerStationConfig(customer,station,0);
    }
    long source(int method) {
        long payment=createPaymentRecord(null,customer,station,"50",method,2);jdbc.update("update payment_record set water_amount=0,barrel_deposit=50,delivery_fee=0,floor_fee=0,note='合成原款事实，未执行支付' where id=?",payment);
        long lot=manualRight("SOURCE-"+payment);
        jdbc.update("update customer_barrel_lot set source_type=4 where id=?",lot);
        jdbc.update("insert into barrel_right_purchase(customer_id,station_id,product_id,quantity,unit_price,amount,payment_id,lot_id,idempotency_key,status) values(?,?,?,1,50,50,?,?,?,'PAID')",customer,station,product,payment,lot,"fixture-source-"+payment);
        return payment;
    }
    long manualRight(String name) {
        long lot=createBarrelLot(name,customer,station,product,"50",1,1);
        jdbc.update("insert into customer_barrel_asset(customer_id,station_id,product_id,quantity,right_amount) values(?,?,?,1,50) on duplicate key update quantity=quantity+1,right_amount=right_amount+50",customer,station,product);
        jdbc.update("insert into customer_deposit_account(customer_id,station_id,balance) values(?,?,50) on duplicate key update balance=balance+50",customer,station);
        jdbc.update("insert into customer_barrel_over(customer_id,station_id,product_id,over_qty) values(?,?,?,-1) on duplicate key update over_qty=over_qty-1",customer,station,product);
        jdbc.update("insert into deposit_record(customer_id,station_id,product_id,type,quantity,amount,operator_id,note) values(?,?,?,1,1,50,?,'合成期初事实')",customer,station,product,managerId);return lot;
    }
    SyntheticWechatSuccess.Proof capture(long id,Instant time) throws Exception {
        var proof=SyntheticWechatSuccess.proof(payments.getById(id),time);assertTrue(evidence.captureVerified(proof.identity(),proof.matched()));return proof;
    }
    void handover(int quantity) {
        Api apply=post("/api/barrels/return",owner,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":"+quantity+",\"pickupMode\":\"STORE\",\"idempotencyKey\":\"original-c08-return\"}");assertEquals(0,apply.code(),apply.toString());record=apply.data().path("recordId").asLong();
        assertFalse(eligibility().data().path("available").asBoolean());
        assertEquals(0,put("/api/barrels/records/"+record+"/approve",manager,"{\"pickupFee\":0}").code());
        assertEquals(0,put("/api/barrels/records/"+record+"/status",manager,"{\"status\":2}").code());
    }
    Api eligibility(){return get("/api/barrels/records/"+record+"/refund-eligibility?scopedRefunds=true",manager);}
    Api pay(String key,String amount){return put("/api/barrels/records/"+record+"/status",manager,"{\"status\":3,\"refundChannel\":\"CASH\",\"cashDeliveryConfirmed\":true,\"expectedRefundAmount\":"+amount+",\"payoutRequestKey\":\""+key+"\"}");}
    Map<String,Object> facts(){var result=new LinkedHashMap<String,Object>();for(String table:List.of("payment_record","deposit_record","customer_deposit_account","customer_barrel_lot","customer_barrel_asset","customer_barrel_over","barrel_return_detail","barrel_record","barrel_record_lot","barrel_right_reservation","barrel_return_payout","barrel_purchase_refund","order_barrel_refund","payment_original_success","payment_original_refund_hold"))result.put(table,jdbc.queryForList("select * from "+table));return result;}
    @AfterEach void restoreClock(){ReflectionTestUtils.setField(evidence,"clock",Clock.systemUTC());}

    @Test void expiredVerifiedOriginalUsesCashAfterHandoverAndOriginalKeyRecoversExactlyOnce() throws Exception {
        base();long original=source(1);var proof=capture(original,Instant.now().minus(Duration.ofDays(366)));handover(1);
        var quote=eligibility();assertEquals(0,quote.code());assertTrue(quote.data().path("available").asBoolean(),quote.toString());assertEquals("CASH",quote.data().path("channel").asText());assertTrue(quote.data().path("expiredOriginalCash").asBoolean());
        assertFalse(get("/api/barrels/records/"+record+"/refund-eligibility",manager).data().path("available").asBoolean(),"旧端不能被提示先交现金");
        assertEquals(0,pay("original-expired-cash","50").code());assertEquals(1,intOf("select payment_method from payment_record where id=?",original));assertEquals(2,intOf("select payment_method from payment_record where amount<0"));
        assertEquals(0,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).signum());
        assertEquals(0,reconciliation.runReconcileV2().get("E23_expiredWechatCash"));
        var paid=facts();assertEquals(0,pay("original-expired-cash","50").code());assertNotEquals(0,pay("original-expired-cash","49").code());assertEquals(paid,facts());
        assertTrue(evidence.captureVerified(proof.identity(),proof.matched()));assertEquals(paid,facts());
        long epoch=jdbc.queryForObject("select success_epoch_second from payment_original_success where payment_id=?",Long.class,original);
        jdbc.update("update payment_original_success set success_epoch_second=? where payment_id=?",Instant.now().minus(Duration.ofDays(364)).getEpochSecond(),original);
        try {assertEquals(1,reconciliation.runReconcileV2().get("E23_expiredWechatCash"),"年内现金误退仍必须被独立对账检出");}
        finally {jdbc.update("update payment_original_success set success_epoch_second=? where payment_id=?",epoch,original);}
    }
    @Test void missingOrYoungOriginalTimeCannotBeGuessedFromOrderOrAssetDates() throws Exception {
        base();long original=source(1);handover(1);var before=facts();
        assertFalse(eligibility().data().path("available").asBoolean());assertNotEquals(0,pay("missing-time","50").code());assertEquals(before,facts());
        var missing=SyntheticWechatSuccess.proof(payments.getById(original),null);assertFalse(evidence.captureVerified(missing.identity(),missing.matched()));assertEquals(0,intOf("select count(*) from payment_original_success"));
        var future=SyntheticWechatSuccess.proof(payments.getById(original),Instant.now().plus(Duration.ofDays(1)));assertFalse(evidence.captureVerified(future.identity(),future.matched()));assertEquals(0,intOf("select count(*) from payment_original_success"));
        var proof=capture(original,Instant.now().minus(Duration.ofDays(364)));jdbc.update("update payment_record set create_time='2020-01-01' where id=?",original);jdbc.update("update customer_barrel_lot set create_time='2020-01-01' where customer_id=?",customer);
        assertEquals("ONLINE",eligibility().data().path("channel").asText());assertFalse(eligibility().data().path("available").asBoolean());
        var changed=SyntheticWechatSuccess.proof(payments.getById(original),Instant.now().minus(Duration.ofDays(500)));assertThrows(Exception.class,()->evidence.captureVerified(changed.identity(),changed.matched()));
        assertEquals(proof.matched().successTime().getEpochSecond(),jdbc.queryForObject("select success_epoch_second from payment_original_success where payment_id=?",Long.class,original));
    }
    @Test void unresolvedOriginalRequestSurvivesExpiryAndQueriesSameKeyWithoutAnotherPayout() throws Exception {
        base();long original=source(1);Instant paid=Instant.now().minus(Duration.ofDays(366));ReflectionTestUtils.setField(evidence,"clock",Clock.fixed(paid.plus(Duration.ofDays(364)),ZoneOffset.UTC));var proof=capture(original,paid);
        var request=new RefundRecoveryProtocol.Request(original,proof.identity().channelBindingReference(),proof.matched().transactionId(),"original-unknown-refund",5000,5000,"CNY");assertEquals(request,evidence.reserveOriginalRefund(request));
        ReflectionTestUtils.setField(evidence,"clock",Clock.systemUTC());assertEquals(request,evidence.reserveOriginalRefund(request));assertEquals(request,evidence.queryOriginal(original,request.requestKey()).orElseThrow().originalRequest());
        assertThrows(Exception.class,()->evidence.reserveOriginalRefund(new RefundRecoveryProtocol.Request(original,request.channelBindingReference(),request.originalTransactionReference(),request.requestKey(),5000,1200,"CNY")));
        handover(1);var before=facts();assertFalse(eligibility().data().path("available").asBoolean());assertNotEquals(0,pay("must-not-switch-cash","50").code());assertEquals(before,facts());
        assertEquals(0,intOf("select count(*) from payment_record where amount<0"));assertEquals(50,intOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station));
    }
    @Test void expiredCashRollbackPreservesRemainingLiabilityAndSameKeyCanFinishLater() throws Exception {
        base();long original=source(1);capture(original,Instant.now().minus(Duration.ofDays(366)));handover(1);var before=facts();
        jdbc.execute("create trigger c08_fail_cash before insert on barrel_purchase_refund for each row signal sqlstate '45000' set message_text='synthetic payout failure'");
        try {assertNotEquals(0,pay("same-original-38","38").code());assertEquals(before,facts());}
        finally {jdbc.execute("drop trigger if exists c08_fail_cash");}
        assertEquals(0,pay("same-original-38","38").code());assertEquals(12,eligibility().data().path("refundAmount").asInt());assertEquals("RECEIVED",jdbc.queryForObject("select status from barrel_return_detail where record_id=?",String.class,record));
        assertEquals(0,pay("same-original-last12","12").code());assertEquals(2,intOf("select count(*) from barrel_return_payout"));assertEquals(0,reconciliation.runReconcileV2().get("E23_expiredWechatCash"));
    }
    @Test void mixedOldAndYoungOriginalsReturnOnlyEligibleCashAndRetainOtherFifty() throws Exception {
        base();long expired=source(1),young=source(1);capture(expired,Instant.now().minus(Duration.ofDays(366)));capture(young,Instant.now().minus(Duration.ofDays(364)));handover(2);
        var quote=eligibility();assertTrue(quote.data().path("available").asBoolean(),quote.toString());assertEquals(50,quote.data().path("refundAmount").asInt());assertEquals(50,quote.data().path("channelPendingRefundAmount").asInt());
        assertEquals(0,pay("first-expired-only","50").code());assertFalse(eligibility().data().path("available").asBoolean());assertEquals(50,intOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station));
        ReflectionTestUtils.setField(evidence,"clock",Clock.offset(Clock.systemUTC(),Duration.ofDays(3)));
        assertEquals(50,eligibility().data().path("refundAmount").asInt());assertEquals(0,pay("later-original-expiry","50").code());assertEquals("REFUNDED",jdbc.queryForObject("select status from barrel_return_detail where record_id=?",String.class,record));
    }
    @Test void unknownOnlineFirstDoesNotStealManualCashAllocationOrAllowItTwice() throws Exception {
        base();source(1);manualRight("MANUAL-AFTER-ONLINE");handover(2);assertEquals(50,eligibility().data().path("refundAmount").asInt());
        assertEquals(0,pay("manual-only-once","50").code());var before=facts();assertFalse(eligibility().data().path("available").asBoolean());assertNotEquals(0,pay("no-second-manual","50").code());assertEquals(before,facts());
        assertEquals(50,intOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station));assertEquals(0,intOf("select count(*) from barrel_purchase_refund"));
    }
    @Test void concurrentExpiredOriginalCashKeyRecordsOneActualRefund() throws Exception {
        base();long original=source(1);capture(original,Instant.now().minus(Duration.ofDays(366)));handover(1);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {var one=pool.submit(()->{start.await();return pay("same-expired-key","50");});var two=pool.submit(()->{start.await();return pay("same-expired-key","50");});start.countDown();assertEquals(0,one.get(40,TimeUnit.SECONDS).code());assertEquals(0,two.get(40,TimeUnit.SECONDS).code());assertEquals(1,intOf("select count(*) from barrel_return_payout"));assertEquals(1,intOf("select count(*) from payment_record where amount<0"));}
        finally {pool.shutdownNow();}
    }
    @Test void verifiedOriginalProofAndUnresolvedKeysAreAtomicAndCannotChangeSourceOrTime() throws Exception {
        base();long original=source(1);var proof=SyntheticWechatSuccess.proof(payments.getById(original),Instant.now().minus(Duration.ofDays(10)));
        jdbc.execute("create trigger c08_fail_time before insert on payment_original_success for each row signal sqlstate '45000' set message_text='synthetic time failure'");
        try {assertThrows(Exception.class,()->evidence.captureVerified(proof.identity(),proof.matched()));assertEquals(0,intOf("select count(*) from payment_original_success"));}
        finally {jdbc.execute("drop trigger if exists c08_fail_time");}
        assertTrue(evidence.captureVerified(proof.identity(),proof.matched()));var before=facts();assertTrue(evidence.captureVerified(proof.identity(),proof.matched()));assertEquals(before,facts());
        long second=source(1);var collision=SyntheticWechatSuccess.proof(payments.getById(second),proof.matched().successTime(),proof.matched().transactionId(),proof.identity().channelBindingReference(),5000);
        assertThrows(Exception.class,()->evidence.captureVerified(collision.identity(),collision.matched()));assertEquals(1,intOf("select count(*) from payment_original_success"));
        var request=new RefundRecoveryProtocol.Request(original,proof.identity().channelBindingReference(),proof.matched().transactionId(),"same-original-request",5000,1200,"CNY");evidence.reserveOriginalRefund(request);var stored=facts();assertEquals(request,evidence.reserveOriginalRefund(request));assertEquals(stored,facts());
        assertThrows(Exception.class,()->evidence.reserveOriginalRefund(new RefundRecoveryProtocol.Request(original,request.channelBindingReference(),request.originalTransactionReference(),request.requestKey(),5000,1300,"CNY")));assertEquals(stored,facts());
    }
    long combinedSource(int method,long collector) {
        long address=createAddress(customer,"合成原单地址");
        long order=createOrderFull(customer,address,station,product,4,2,method,"20","50","70",false,1);
        jdbc.update("update orders set settle_station_id=?,delivery_station_id=? where id=?",collector,collector,order);
        long payment=createPaymentRecord(order,customer,collector,"70",method,2);jdbc.update("update payment_record set water_amount=20,barrel_deposit=50,delivery_fee=0,floor_fee=0 where id=?",payment);
        long lot=manualRight("COMBINED-"+payment);
        jdbc.update("insert into order_barrel_purchase(order_id,customer_id,station_id,product_id,quantity,unit_price,amount,payment_id,lot_id,status) values(?,?,?,?,1,50,50,?,?,'PAID')",order,customer,station,product,payment,lot);return payment;
    }
    @Test void expiredCombinedOriginalSharesRefundCapWithoutRefundingConsumedWaterOrChangingOriginalMethod() throws Exception {
        base();long original=combinedSource(1,station);capture(original,Instant.now().minus(Duration.ofDays(366)));handover(1);
        assertEquals(0,pay("combined-expired-cash","50").code());assertEquals(50,intOf("select refunded_amount from order_barrel_purchase"));assertEquals(1,intOf("select refunded_qty from order_barrel_purchase"));
        assertEquals(2,intOf("select status from payment_record where id=?",original),"原水费20元不因退桶被自动退掉");
        assertEquals(1,intOf("select payment_method from payment_record where id=?",original));assertEquals(station,jdbc.queryForObject("select station_id from payment_record where amount<0",Long.class));
        var check=reconciliation.runReconcileV2();assertEquals(0,check.get("E21_orderBarrelReceipt"));assertEquals(0,check.get("E22_orderBarrelRefund"));assertEquals(0,check.get("E23_expiredWechatCash"));
    }
    @Test void combinedCashKeepsOriginalCollectorButActualCashOutBelongsToAssetStation() {
        base();long collector=createStation("原现金收取站");long original=combinedSource(2,collector);handover(1);
        assertEquals(0,pay("actual-asset-station-cash","50").code());assertEquals(collector,jdbc.queryForObject("select station_id from payment_record where id=?",Long.class,original));
        assertEquals(station,jdbc.queryForObject("select station_id from payment_record where amount<0",Long.class),"实际由资产站交现金，不把它伪记成原收款站出款");
    }
    @Test void missingOrCaseInsensitiveOriginalRequestIndexRefusesStartup() {
        schemaGuard.verifySchema();jdbc.execute("alter table payment_original_refund_hold drop index uk_original_refund_request");
        try {assertThrows(IllegalStateException.class,()->schemaGuard.verifySchema());}
        finally {jdbc.execute("alter table payment_original_refund_hold add unique key uk_original_refund_request(binding_digest,request_key)");}
        jdbc.execute("alter table payment_original_refund_hold modify request_key varchar(128) character set ascii collate ascii_general_ci not null");
        try {assertThrows(IllegalStateException.class,()->schemaGuard.verifySchema());}
        finally {jdbc.execute("alter table payment_original_refund_hold modify request_key varchar(128) character set ascii collate ascii_bin not null");}
        schemaGuard.verifySchema();
    }
}
