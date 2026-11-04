package com.example.aquaflow.integration;

import com.example.aquaflow.service.BarrelLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 顺路退桶引用同次配送的明确收桶凭据，原权益批次不变且不再次减少实物。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=true"})
class CombinedReturnDeliveryEvidenceIntegrationTest extends CustomerConfirmationFlowFixture {
    @Autowired BarrelLedgerService ledger;

    @Test void sameProductCombinedApprovalIsAllowedButReceiptIsRequiredForHandover() {
        seed(); deliver(paidOrder());
        long companion=companion("pending-companion");
        long request=combined(companion);
        Api approval=put("/api/barrels/records/"+request+"/approve",mgr,"{\"pickupFee\":0}");
        assertEquals(0,approval.code(),approval.toString());
        Api view=get("/api/barrels/records/"+request+"/handover-evidence",mgr);
        assertEquals(0,view.code(),view.toString());
        assertTrue(view.data().path("requiresLinkedReceipt").asBoolean());
        assertFalse(view.data().path("eligible").asBoolean());
        assertEquals("ORDER_NOT_DELIVERED",view.data().path("reasonCode").asText());
        assertEquals(1,status(request,2).code());
        assertEquals(2,ledger.occupiedQty(customer,station,product));
    }

    @Test void originalReturnBindsDeliveryReceiptAndRefundsWithoutCountingPhysicalBucketsTwice() {
        seed(); deliver(paidOrder());
        long companion=companion("actual-companion");
        long request=combined(companion);
        String held=jdbc.queryForList("select * from barrel_return_lot_hold where record_id=?",request).toString();
        Api approval=put("/api/barrels/records/"+request+"/approve",mgr,"{\"pickupFee\":0}");
        assertEquals(0,approval.code(),approval.toString());
        complete(companion,2);
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        int overBefore=ledger.overQty(customer,station,product);
        Api view=get("/api/barrels/records/"+request+"/handover-evidence",mgr);
        assertEquals(0,view.code(),view.toString());
        assertTrue(view.data().path("eligible").asBoolean(),view.toString());
        long receipt=view.data().path("deliveryReceiptId").asLong();assertTrue(receipt>0,view.toString());
        assertEquals(2,view.data().path("returnedQty").asInt());
        Api received=put("/api/barrels/records/"+request+"/status",mgr,"{\"status\":2,\"deliveryReceiptId\":"+receipt+"}");
        assertEquals(0,received.code(),received.toString());
        assertEquals(overBefore,ledger.overQty(customer,station,product));
        assertEquals(0,intOf("select count(*) from barrel_record where client_token=?","approved-return-"+request));
        assertEquals(1,intOf("select count(*) from barrel_return_delivery_allocation where return_record_id=? and delivery_record_id=? and quantity=1",request,receipt));
        assertEquals(held,jdbc.queryForList("select * from barrel_return_lot_hold where record_id=?",request).toString());
        assertEquals(1,status(request,2).code());
        assertEquals(0,status(request,3).code());
        assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        assertEquals(0,ledger.overQty(customer,station,product));
    }

    @Test void normalExchangeCannotBeRetiredUntilSeparateActualReturnRestoresCapacity() {
        seed(); deliver(paidOrder());long order=companion("exchange-only"),request=combined(order);approve(request);complete(order,1);
        long receipt=receipt(order);
        Api view=get("/api/barrels/records/"+request+"/handover-evidence",mgr);
        assertEquals("PHYSICAL_BALANCE_CHANGED",view.data().path("reasonCode").asText(),view.toString());
        assertEquals(1,receive(request,receipt).code());assertEquals(0,intOf("select count(*) from barrel_return_delivery_allocation"));
        Api returned=post("/api/barrels/return-empty",mgr,"{\"customerId\":"+customer+",\"clientToken\":\"later-actual-return\",\"items\":[{\"productId\":"+product+",\"qty\":1}]}");
        assertEquals(0,returned.code(),returned.toString());
        assertEquals(0,intOf("select count(*) from barrel_return_delivery_allocation"),"另一次还桶不会自动关联原申请");
        assertEquals(0,receive(request,receipt).code());assertEquals(1,ledger.occupiedQty(customer,station,product));
        assertEquals(0,status(request,3).code());assertEquals(0,ledger.overQty(customer,station,product));
    }

    @Test void missingLegacyProvenanceAndDuplicateReceiptsNeverFallBackToOverBalance() {
        seed();deliver(paidOrder());long order=companion("old-source"),request=combined(order);approve(request);complete(order,2);
        long receipt=receipt(order);
        jdbc.update("delete from barrel_delivery_receipt where record_id=?",receipt);
        Api missing=get("/api/barrels/records/"+request+"/handover-evidence",mgr);
        assertEquals("RECEIPT_MISSING",missing.data().path("reasonCode").asText(),missing.toString());
        assertEquals(1,receive(request,receipt).code());assertEquals(-1,ledger.overQty(customer,station,product));
        jdbc.update("insert into barrel_delivery_receipt(record_id,input_kind) values(?,'ITEMIZED')",receipt);
        jdbc.update("insert into barrel_record(customer_id,station_id,product_id,related_order_id,type,quantity,status,returned_qty) values(?,?,?,?,8,1,3,2)",customer,station,product,order);
        Api duplicate=get("/api/barrels/records/"+request+"/handover-evidence",mgr);
        assertEquals("RECEIPT_AMBIGUOUS",duplicate.data().path("reasonCode").asText(),duplicate.toString());
        assertEquals(1,receive(request,receipt).code());assertEquals(0,intOf("select count(*) from barrel_return_delivery_allocation"));
    }

    @Test void readingOrLinkingRequiresOriginalStationManagerAndExactCompanionReceipt() {
        seed();deliver(paidOrder());long order=companion("scoped-source"),request=combined(order);approve(request);complete(order,2);
        long receipt=receipt(order);
        long other=createStation("其他站"),otherManager=createStaff("其他站长","STATION_MANAGER",other,1);
        String foreign=staffToken(otherManager,"STATION_MANAGER",other);
        long delivery=createStaff("配送员","DELIVERY",station,1);String rider=staffToken(delivery,"DELIVERY",station);
        for(String token:List.of(cus,foreign,rider)) {
            assertNotEquals(0,get("/api/barrels/records/"+request+"/handover-evidence",token).code());
            assertNotEquals(0,put("/api/barrels/records/"+request+"/status",token,"{\"status\":2,\"deliveryReceiptId\":"+receipt+"}").code());
        }
        long foreignCustomer=createCustomer("其他客户","foreign-receipt-owner");
        long foreignReceipt=insert("insert into barrel_record(customer_id,station_id,product_id,related_order_id,type,quantity,status,returned_qty) values(?,?,?,?,8,1,3,2)",foreignCustomer,other,product,order);
        jdbc.update("insert into barrel_delivery_receipt(record_id,input_kind) values(?,'ITEMIZED')",foreignReceipt);
        assertEquals(1,receive(request,foreignReceipt).code());
        assertEquals(0,intOf("select count(*) from barrel_return_delivery_allocation"));assertEquals(0,receive(request,receipt).code());
    }

    @Test void companionNavigationMatchesExistingPaymentAndDeliveryStationAccess() {
        seed();deliver(paidOrder());long order=companion("navigation-source"),request=combined(order);approve(request);
        Api unpaid=get("/api/barrels/records/"+request+"/handover-evidence",mgr);
        assertEquals(0,unpaid.code(),unpaid.toString());assertTrue(unpaid.data().path("canOpenCompanion").isBoolean(),unpaid.toString());
        assertFalse(unpaid.data().path("canOpenCompanion").asBoolean());
        assertTrue(unpaid.data().path("companionNextStep").asText().contains("付款"),unpaid.toString());
        assertEquals(0,post("/api/payments",cus,"{\"orderId\":"+order+",\"paymentMethod\":1}").code());
        Api paid=get("/api/barrels/records/"+request+"/handover-evidence",mgr);
        assertTrue(paid.data().path("canOpenCompanion").isBoolean(),paid.toString());assertTrue(paid.data().path("canOpenCompanion").asBoolean(),paid.toString());
        long other=createStation("外派履约站"),otherManager=createStaff("外派站长","STATION_MANAGER",other,1);
        createInventoryFull(other,product,10,1,"8.00");String target=staffToken(otherManager,"STATION_MANAGER",other);
        Api sent=post("/api/delivery/orders/"+order+"/dispatch",mgr,"{\"targetStationId\":"+other+",\"riskAcknowledged\":true}");assertEquals(0,sent.code(),sent.toString());
        Api offered=get("/api/manager/dispatch-agreements/"+order,target);assertEquals(0,offered.code(),offered.toString());
        Api accepted=post("/api/delivery/orders/assign-quoted/"+order,target,"{\"deliveryStaffId\":"+otherManager+",\"riskAcknowledged\":true,\"quoteToken\":\""+offered.data().path("quoteToken").asText()+"\"}");assertEquals(0,accepted.code(),accepted.toString());
        Api outsourced=get("/api/barrels/records/"+request+"/handover-evidence",mgr);
        assertTrue(outsourced.data().path("canOpenCompanion").isBoolean(),outsourced.toString());assertFalse(outsourced.data().path("canOpenCompanion").asBoolean());
        assertTrue(outsourced.data().path("companionNextStep").asText().contains("履约站"),outsourced.toString());
    }

    @Test void concurrentConfirmationAllocatesOnlyOnceWithoutReducingPhysicalTwice() throws Exception {
        seed();deliver(paidOrder());long order=companion("parallel-source"),request=combined(order);approve(request);complete(order,2);
        long receipt=receipt(order);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> a=pool.submit(()->{start.await();return receive(request,receipt);});
            Future<Api> b=pool.submit(()->{start.await();return receive(request,receipt);});start.countDown();
            List<Api> result=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,result.stream().filter(r->r.code()==0).count(),result.toString());
            assertTrue(result.stream().allMatch(r->r.code()==0 || r.code()==1),result.toString());
            assertEquals(1,intOf("select count(*) from barrel_return_delivery_allocation"));
            assertEquals(-1,ledger.overQty(customer,station,product));assertEquals(1,ledger.occupiedQty(customer,station,product));
        } finally {pool.shutdownNow();}
    }

    @Test void singleProductLegacyTotalIsProvableButOmittedInputHasNoExactMarker() {
        seed();deliver(paidOrder());long order=companion("single-total"),request=combined(order);approve(request);
        payAndAccept(order);
        Api done=post("/api/delivery/orders/"+order+"/complete",mgr,"{\"returnBucketQty\":2}");assertEquals(0,done.code(),done.toString());
        long receipt=receipt(order);assertEquals("SINGLE_PRODUCT_TOTAL",jdbc.queryForObject("select input_kind from barrel_delivery_receipt where record_id=?",String.class,receipt));
        assertEquals(0,receive(request,receipt).code());assertEquals(0,status(request,3).code());
        long omitted=companion("omitted-total");payAndAccept(omitted);
        assertEquals(0,post("/api/delivery/orders/"+omitted+"/complete",mgr,"{}").code());
        assertEquals(0,intOf("select count(*) from barrel_delivery_receipt s join barrel_record r on r.id=s.record_id where r.related_order_id=?",omitted));
    }

    @Test void concurrentReturnsCannotShareOneVacantCapacityEvenWhenReceiptTotalCoversBoth() throws Exception {
        seed();
        Api purchased=post("/api/barrel-rights/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"paymentMethod\":2,\"idempotencyKey\":\"third-right\"}");
        assertEquals(0,purchased.code(),purchased.toString());assertEquals(0,put("/api/payments/"+purchased.data().path("paymentId").asLong()+"/confirm",mgr,null).code());
        deliver(paidOrder());complete(companion("third-physical"),0);assertEquals(3,ledger.occupiedQty(customer,station,product));
        long order=companion("shared-return-source"),first=combined(order);
        Api secondApplied=post("/api/barrels/return",cus,requestBody("COMBINED").replace("\"quantity\":1,","\"quantity\":1,\"companionOrderId\":"+order+",").replace("\"return\"","\"second-return\""));
        assertEquals(0,secondApplied.code(),secondApplied.toString());long second=secondApplied.data().path("recordId").asLong();
        approve(first);approve(second);complete(order,2);assertEquals(-1,ledger.overQty(customer,station,product));
        long receipt=receipt(order);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> a=pool.submit(()->{start.await();return receive(first,receipt);});
            Future<Api> b=pool.submit(()->{start.await();return receive(second,receipt);});start.countDown();
            List<Api> result=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,result.stream().filter(r->r.code()==0).count(),result.toString());
            assertTrue(result.stream().allMatch(r->r.code()==0 || r.code()==1),result.toString());
            assertEquals(1,intOf("select count(*) from barrel_return_delivery_allocation"));
            long pending=result.get(0).code()==0?second:first;
            assertEquals("APPROVED",jdbc.queryForObject("select status from barrel_return_detail where record_id=?",String.class,pending));
            Api returned=post("/api/barrels/return-empty",mgr,"{\"customerId\":"+customer+",\"clientToken\":\"extra-physical-for-second-return\",\"items\":[{\"productId\":"+product+",\"qty\":1}]}");
            assertEquals(0,returned.code(),returned.toString());assertEquals(0,receive(pending,receipt).code());
            assertEquals(2,intOf("select sum(quantity) from barrel_return_delivery_allocation where delivery_record_id=?",receipt));
            assertEquals(0,status(first,3).code());assertEquals(0,status(second,3).code());
            assertEquals(1,ledger.rightQty(customer,station,product));assertEquals(1,ledger.occupiedQty(customer,station,product));
        } finally {pool.shutdownNow();}
    }

    @Test void multiProductRatioAndOmittedItemNeverBecomeExactProductEvidence() {
        seed();deliver(paidOrder());
        long other=createProduct("另一桶型",1,"18.00","40.00",1,"7.00");createInventoryFull(station,other,10,1,"7.00");
        Api purchase=post("/api/barrel-rights/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+other+",\"quantity\":1,\"paymentMethod\":2,\"idempotencyKey\":\"other-product-right\"}");
        assertEquals(0,purchase.code(),purchase.toString());assertEquals(0,put("/api/payments/"+purchase.data().path("paymentId").asLong()+"/confirm",mgr,null).code());
        long firstOther=orderWithItems("first-other","{\"productId\":"+other+",\"quantity\":1}");complete(firstOther,0);
        String items="{\"productId\":"+product+",\"quantity\":1},{\"productId\":"+other+",\"quantity\":1}";
        long ratio=orderWithItems("ratio-input",items),request=combined(ratio);approve(request);payAndAccept(ratio);
        Api ratioDone=post("/api/delivery/orders/"+ratio+"/complete",mgr,"{\"returnBucketQty\":2}");assertEquals(0,ratioDone.code(),ratioDone.toString());
        assertEquals(0,intOf("select count(*) from barrel_delivery_receipt s join barrel_record r on r.id=s.record_id where r.related_order_id=?",ratio));
        Api view=get("/api/barrels/records/"+request+"/handover-evidence",mgr);assertEquals("RECEIPT_MISSING",view.data().path("reasonCode").asText(),view.toString());
        long omitted=orderWithItems("omit-one-row",items);payAndAccept(omitted);
        long item=jdbc.queryForObject("select id from order_item where order_id=? and product_id=?",Long.class,omitted,other);
        Api omittedDone=post("/api/delivery/orders/"+omitted+"/complete",mgr,"{\"itemReturns\":[{\"orderItemId\":"+item+",\"actual\":1}]}");assertEquals(0,omittedDone.code(),omittedDone.toString());
        assertEquals(0,intOf("select count(*) from barrel_delivery_receipt s join barrel_record r on r.id=s.record_id where r.related_order_id=? and r.product_id=?",omitted,product));
        assertEquals(1,intOf("select count(*) from barrel_delivery_receipt s join barrel_record r on r.id=s.record_id where r.related_order_id=? and r.product_id=?",omitted,other));
    }

    @Test void historicalDuplicateProductRowsRequireEveryRowBeforeExactMarker() {
        seed();deliver(paidOrder());
        long order=orderWithItems("legacy-duplicate-items","{\"productId\":"+product+",\"quantity\":2}");
        long first=jdbc.queryForObject("select id from order_item where order_id=?",Long.class,order);
        // 新建入口已拒重复商品；这里模拟尚未交付的存量同商品拆行，保持总价、数量和预留总量不变。
        jdbc.update("update order_item set quantity=1,subtotal=price,deducted_qty=1 where id=?",first);
        jdbc.update("update inventory_reservation set need_qty=1,reserved_qty=1 where order_item_id=? and status=1",first);
        long second=insert("insert into order_item(order_id,product_id,product_name_snapshot,price,quantity,deposit,subtotal,deducted_qty) select order_id,product_id,product_name_snapshot,price,1,deposit,price,1 from order_item where id=?",first);
        createReservation(order,second,product,station,1,1);
        payAndAccept(order);
        Api done=post("/api/delivery/orders/"+order+"/complete",mgr,"{\"itemReturns\":[{\"orderItemId\":"+first+",\"actual\":1}]}");
        assertEquals(0,done.code(),done.toString());
        assertEquals(1,intOf("select returned_qty from barrel_record where related_order_id=? and type=8",order));
        assertEquals(0,intOf("select count(*) from barrel_delivery_receipt s join barrel_record r on r.id=s.record_id where r.related_order_id=?",order),"同商品另一行未明确上报，不能把局部输入标记成该商品完整实收");
    }

    @Test void totalAllocationCannotExceedOriginalReceiptEvenAfterOtherRealReturns() {
        seed();
        Api purchase=post("/api/barrel-rights/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":2,\"paymentMethod\":2,\"idempotencyKey\":\"four-rights\"}");
        assertEquals(0,purchase.code(),purchase.toString());assertEquals(0,put("/api/payments/"+purchase.data().path("paymentId").asLong()+"/confirm",mgr,null).code());
        deliver(paidOrder());complete(companion("third-fill"),0);complete(companion("fourth-fill"),0);
        long order=companion("bounded-source");long[] requests=new long[3];
        for(int i=0;i<3;i++) {
            Api applied=post("/api/barrels/return",cus,requestBody("COMBINED").replace("\"quantity\":1,","\"quantity\":1,\"companionOrderId\":"+order+",").replace("\"return\"","\"return-"+i+"\""));
            assertEquals(0,applied.code(),applied.toString());requests[i]=applied.data().path("recordId").asLong();approve(requests[i]);
        }
        complete(order,2);long receipt=receipt(order);
        Api extra=post("/api/barrels/return-empty",mgr,"{\"customerId\":"+customer+",\"clientToken\":\"extra-two-physical\",\"items\":[{\"productId\":"+product+",\"qty\":2}]}");assertEquals(0,extra.code(),extra.toString());
        assertEquals(0,receive(requests[0],receipt).code());assertEquals(0,receive(requests[1],receipt).code());
        Api view=get("/api/barrels/records/"+requests[2]+"/handover-evidence",mgr);assertEquals("RECEIPT_INSUFFICIENT",view.data().path("reasonCode").asText(),view.toString());
        assertEquals(1,receive(requests[2],receipt).code());assertEquals(2,intOf("select sum(quantity) from barrel_return_delivery_allocation where delivery_record_id=?",receipt));
        assertEquals("APPROVED",jdbc.queryForObject("select status from barrel_return_detail where record_id=?",String.class,requests[2]));
        assertEquals(1,ledger.occupiedQty(customer,station,product));
    }

    private long orderWithItems(String key,String items) {
        Api created=post("/api/orders/create",cus,"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"idempotencyKey\":\""+key+"\",\"items\":["+items+"]}");
        assertEquals(0,created.code(),created.toString());return created.data().path("orderId").asLong();
    }

    private Api receive(long request,long receipt) {
        return put("/api/barrels/records/"+request+"/status",mgr,"{\"status\":2,\"deliveryReceiptId\":"+receipt+"}");
    }

    private long receipt(long order) {
        return jdbc.queryForObject("select id from barrel_record where related_order_id=? and product_id=? and type=8",Long.class,order,product);
    }

    private void approve(long request) {
        Api result=put("/api/barrels/records/"+request+"/approve",mgr,"{\"pickupFee\":0}");assertEquals(0,result.code(),result.toString());
    }

    private void payAndAccept(long order) {
        assertEquals(0,post("/api/payments",cus,"{\"orderId\":"+order+",\"paymentMethod\":1}").code());
        assertEquals(0,post("/api/delivery/orders/"+order+"/accept",mgr,"{}").code());
    }

    private long companion(String key) {
        Api created=post("/api/orders/create",cus,"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"idempotencyKey\":\""+key+"\",\"items\":[{\"productId\":"+product+",\"quantity\":1}]}");
        assertEquals(0,created.code(),created.toString());return created.data().path("orderId").asLong();
    }

    private long combined(long companion) {
        Api requested=post("/api/barrels/return",cus,requestBody("COMBINED").replace("\"quantity\":1,","\"quantity\":1,\"companionOrderId\":"+companion+","));
        assertEquals(0,requested.code(),requested.toString());return requested.data().path("recordId").asLong();
    }

    private void complete(long order,int actual) {
        payAndAccept(order);
        long item=jdbc.queryForObject("select id from order_item where order_id=?",Long.class,order);
        Api done=post("/api/delivery/orders/"+order+"/complete",mgr,"{\"itemReturns\":[{\"orderItemId\":"+item+",\"expected\":1,\"actual\":"+actual+",\"reasons\":[]}]}");
        assertEquals(0,done.code(),done.toString());
    }
}
