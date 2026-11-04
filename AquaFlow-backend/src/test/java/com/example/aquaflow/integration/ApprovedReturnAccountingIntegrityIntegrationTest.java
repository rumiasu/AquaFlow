package com.example.aquaflow.integration;

import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 申请快照不能替代实际交接时的桶账；随单收桶不能把同一实物记进两个入口。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=true"})
class ApprovedReturnAccountingIntegrityIntegrationTest extends CustomerConfirmationFlowFixture {
    @Autowired BarrelLedgerService ledger;
    @Autowired PlatformTransactionManager transactions;

    @Test void debtAddedAfterRequestMustBeReturnedBeforeDepositHandover() {
        seed(); deliver(paidOrder());
        long request=request("STORE"); approve(request);
        deliverOneWithoutReturn("new-debt");
        assertEquals(1,ledger.overQty(customer,station,product));
        Api blocked=status(request,2);
        assertEquals(1,blocked.code(),blocked.toString());
        assertEquals("APPROVED",detailStatus(request));
        assertEquals(3,ledger.occupiedQty(customer,station,product));
        assertEquals(0,intOf("select count(*) from barrel_record where client_token=?","approved-return-"+request));
        returnOne("clear-new-debt");
        assertEquals(0,status(request,2).code());
        assertEquals(0,status(request,3).code());
        assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        assertEquals(0,ledger.overQty(customer,station,product));
    }

    @Test void debtAddedAfterHandoverBlocksPreviewAndRefundUntilPhysicalBalanceIsRestored() {
        long request=receiveThenBorrowAgain();
        Api view=get("/api/barrels/records/"+request+"/refund-eligibility?scopedRefunds=true",mgr);
        assertEquals(0,view.code(),view.toString());
        assertFalse(view.data().path("available").asBoolean(),view.toString());
        Api blocked=status(request,3);
        assertEquals(1,blocked.code(),blocked.toString());
        assertRefundUnchanged(request);
        returnOne("restore-refund-capacity");
        assertEquals(0,status(request,3).code());
        assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(1,ledger.occupiedQty(customer,station,product));
    }

    @Test void partialCashPayoutCannotProceedWhenRefundWouldLeaveUnsecuredPhysicalBuckets() {
        long request=receiveThenBorrowAgain();
        Api payout=put("/api/barrels/records/"+request+"/status",mgr,
                "{\"status\":3,\"refundChannel\":\"CASH\",\"cashDeliveryConfirmed\":true,\"expectedRefundAmount\":10,\"payoutRequestKey\":\"no-premature-cash\"}");
        assertEquals(1,payout.code(),payout.toString());
        assertRefundUnchanged(request);
        assertEquals(0,intOf("select count(*) from barrel_return_payout where record_id=?",request));
    }

    @Test void sameProductCombinedApprovalStillRequiresExplicitDeliveryReceiptForHandover() {
        seed(); deliver(paidOrder());
        long companion=newOrder("same-product",product);
        long request=combinedRequest(companion);
        Api approval=put("/api/barrels/records/"+request+"/approve",mgr,"{\"pickupFee\":0}");
        assertEquals(0,approval.code(),approval.toString());
        assertEquals(1,status(request,2).code(),"批准不代表核对过关联配送实收，旧请求不能自动引用");
        assertEquals("APPROVED",detailStatus(request));
        assertEquals(2,ledger.occupiedQty(customer,station,product));
    }

    @Test void alreadyApprovedSameProductCombinedArrangementCannotSubtractBucketsAgain() {
        seed(); deliver(paidOrder());
        long companion=newOrder("existing-approved",product);
        long request=combinedRequest(companion);
        // 模拟修复前已批准的存量申请，交接入口也必须守住，不能只检查新审批。
        jdbc.update("update barrel_return_detail set status='APPROVED',approved_time=now() where record_id=?",request);
        payAndAccept(companion);
        complete(companion,1);
        Api received=status(request,2);
        assertEquals(1,received.code(),received.toString());
        assertEquals("APPROVED",detailStatus(request));
        assertEquals(2,ledger.occupiedQty(customer,station,product));
        assertEquals(0,intOf("select count(*) from barrel_record where client_token=?","approved-return-"+request));
    }

    @Test void unrelatedProductCompanionStillAllowsExplicitReturnHandover() {
        seed(); deliver(paidOrder());
        long accessory=createProduct("配件",2,"10.00","0.00",1,"3.00");
        createInventoryFull(station,accessory,10,1,"3.00");
        long request=combinedRequest(newOrder("accessory-companion",accessory));
        approve(request);
        assertEquals(0,status(request,2).code());
        assertEquals(0,status(request,3).code());
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        assertEquals(1,ledger.rightQty(customer,station,product));
    }

    @Test void unusedSameProductCombinedReturnDoesNotInventAPhysicalHandover() {
        seed();
        long companion=newOrder("unused-companion",product);
        long request=combinedRequest(companion);
        assertEquals(0,intOf("select required_barrels from barrel_return_detail where record_id=?",request));
        approve(request);
        assertEquals(0,status(request,2).code());
        assertEquals(0,status(request,3).code());
        assertEquals(0,ledger.occupiedQty(customer,station,product));
        assertEquals(1,ledger.rightQty(customer,station,product));
        payAndAccept(companion); complete(companion,0);
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        assertEquals(0,ledger.overQty(customer,station,product));
    }

    @Test void receivedReturnKeepsItsVacantCapacityReservedUntilRefund() {
        seed(); deliver(paidOrder());
        long request=request("STORE"); approve(request);
        assertEquals(0,status(request,2).code());
        assertEquals(1,intOf("select pickup_qty from barrel_right_reservation where owner_type='RETURN' and owner_id=?",request));
        long order=newOrder("do-not-reuse-return-capacity",product);
        assertEquals(0,intOf("select pickup_qty from barrel_right_reservation where owner_type='ORDER' and owner_id=?",order));
        assertEquals(2,ledger.rightQty(customer,station,product));
        assertEquals(1,ledger.occupiedQty(customer,station,product));
    }

    @Test void previouslyReceivedReturnUsesReceiptForCapacityWithoutRewritingItsReservation() {
        seed(); deliver(paidOrder());
        long request=request("STORE"); approve(request);
        assertEquals(0,status(request,2).code());
        // 修复前已收到桶的真实形状：实物已减，旧分配仍保留申请时pickup=0。
        jdbc.update("update barrel_right_reservation set pickup_qty=0 where owner_type='RETURN' and owner_id=?",request);
        assertEquals(0,ledger.availablePickup(customer,station,product));
        long order=newOrder("existing-received-capacity",product);
        assertEquals(0,intOf("select pickup_qty from barrel_right_reservation where owner_type='ORDER' and owner_id=?",order));
        assertEquals(0,intOf("select pickup_qty from barrel_right_reservation where owner_type='RETURN' and owner_id=?",request),"只根据已收事实读取有效预留，不改存量分配");
        payAndAccept(order); complete(order,1);
        assertEquals(0,status(request,3).code());
        assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(1,ledger.occupiedQty(customer,station,product));
    }

    @Test void combinedRequestCanChangeOriginalArrangementBeforeAnyHandover() {
        seed(); deliver(paidOrder());
        long companion=newOrder("separate-after-conflict",product);
        long request=combinedRequest(companion);
        assertEquals(0,put("/api/barrels/records/"+request+"/approve",mgr,"{\"pickupFee\":0}").code());
        Api change=put("/api/barrels/records/"+request+"/arrangement",cus,change("STORE",null,1,"separate-physical-handover"));
        assertEquals(0,change.code(),change.toString());
        assertEquals(request,change.data().path("recordId").asLong());
        Api approved=put("/api/barrels/records/"+request+"/approve",mgr,"{\"pickupFee\":0,\"expectedVersion\":2}");
        assertEquals(0,approved.code(),approved.toString());
        assertEquals(0,status(request,2).code());
        assertEquals(0,status(request,3).code());
        assertEquals(1,intOf("select count(*) from barrel_return_detail"));
        assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        payAndAccept(companion); complete(companion,1);
        assertEquals(1,ledger.occupiedQty(customer,station,product));
    }

    @Test void refundUsesCurrentPhysicalBalanceAfterEarlierRepeatableReadSnapshot() throws Exception {
        seed(); deliver(paidOrder());
        long request=request("STORE"); approve(request);
        assertEquals(0,status(request,2).code());
        CountDownLatch snapshotRead=new CountDownLatch(1),continueRefund=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> result=pool.submit(()->{
                TransactionTemplate tx=new TransactionTemplate(transactions);
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                try {
                    tx.execute(status->{
                        try {
                            assertEquals(-1,ledger.overQty(customer,station,product));
                            snapshotRead.countDown();
                            assertTrue(continueRefund.await(30,TimeUnit.SECONDS));
                            ledger.refundHeldRights(customer,station,product,request);
                            return null;
                        } catch(InterruptedException e) {
                            Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                        } finally { status.setRollbackOnly(); }
                    });
                    return null;
                } catch(Throwable error) { return error; }
            });
            assertTrue(snapshotRead.await(30,TimeUnit.SECONDS));
            deliverOneWithoutReturn("concurrent-new-buckets");
            continueRefund.countDown();
            Throwable failure=result.get(30,TimeUnit.SECONDS);
            assertInstanceOf(BusinessException.class,failure,"最终核销须拒绝旧快照看不见的新增实物占用");
            assertRefundUnchanged(request);
        } finally {continueRefund.countDown();pool.shutdownNow();}
    }

    private long receiveThenBorrowAgain() {
        seed(); deliver(paidOrder());
        long request=request("STORE"); approve(request);
        assertEquals(0,status(request,2).code());
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        deliverOneWithoutReturn("after-receipt");
        assertEquals(2,ledger.occupiedQty(customer,station,product));
        return request;
    }

    private void assertRefundUnchanged(long request) {
        assertEquals("RECEIVED",detailStatus(request));
        assertEquals(2,ledger.rightQty(customer,station,product));
        assertEquals(2,ledger.occupiedQty(customer,station,product));
        assertEquals(new BigDecimal("60.00"),decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station));
        assertEquals(0,intOf("select count(*) from payment_record where amount<0"));
    }

    private String detailStatus(long request) {
        return jdbc.queryForObject("select status from barrel_return_detail where record_id=?",String.class,request);
    }

    private void approve(long request) {
        Api r=put("/api/barrels/records/"+request+"/approve",mgr,"{\"pickupFee\":0}");
        assertEquals(0,r.code(),r.toString());
    }

    private long combinedRequest(long companion) {
        Api r=post("/api/barrels/return",cus,requestBody("COMBINED").replace("\"quantity\":1,","\"quantity\":1,\"companionOrderId\":"+companion+","));
        assertEquals(0,r.code(),r.toString());
        return r.data().path("recordId").asLong();
    }

    private long newOrder(String key,long itemProduct) {
        Api created=post("/api/orders/create",cus,"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"idempotencyKey\":\""+key+"\",\"items\":[{\"productId\":"+itemProduct+",\"quantity\":1}]}");
        assertEquals(0,created.code(),created.toString());
        return created.data().path("orderId").asLong();
    }

    private void payAndAccept(long order) {
        Api payment=post("/api/payments",cus,"{\"orderId\":"+order+",\"paymentMethod\":1}");
        assertEquals(0,payment.code(),payment.toString());
        Api accept=post("/api/delivery/orders/"+order+"/accept",mgr,"{}");
        assertEquals(0,accept.code(),accept.toString());
    }

    private void deliverOneWithoutReturn(String key) {
        long order=newOrder(key,product); payAndAccept(order); complete(order,0);
    }

    private void complete(long order,int actual) {
        long item=jdbc.queryForObject("select id from order_item where order_id=?",Long.class,order);
        int expected=1-intOf("select pickup_qty from barrel_right_reservation where owner_type='ORDER' and owner_id=?",order);
        String reasons=expected>actual?"[{\"key\":\"customer_kept\",\"qty\":"+(expected-actual)+"}]":"[]";
        Api done=post("/api/delivery/orders/"+order+"/complete",mgr,"{\"itemReturns\":[{\"orderItemId\":"+item+",\"expected\":"+expected+",\"actual\":"+actual+",\"reasons\":"+reasons+"}]}");
        assertEquals(0,done.code(),done.toString());
    }

    private void returnOne(String key) {
        Api r=post("/api/barrels/return-empty",mgr,"{\"customerId\":"+customer+",\"clientToken\":\""+key+"\",\"items\":[{\"productId\":"+product+",\"qty\":1}]}");
        assertEquals(0,r.code(),r.toString());
    }
}
