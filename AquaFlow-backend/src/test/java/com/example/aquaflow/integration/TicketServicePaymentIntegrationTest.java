package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.service.TicketLotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="app.payment.test-mode=true")
class TicketServicePaymentIntegrationTest extends AbstractIntegrationTest {
    @Autowired com.example.aquaflow.service.TicketDeliveryBenefitService benefits;
    @Autowired TicketLotService lots;
    @Autowired com.example.aquaflow.service.PaymentService payments;
    @Autowired com.example.aquaflow.mapper.GrossProfitMapper profits;
    @Autowired com.example.aquaflow.mapper.OrderMapper orderMapper;
    long station,customer,product,address,manager;
    void seed() {
        station=createStation("票水服务费站");customer=createCustomer("分项客户","split-payment");
        manager=createStaff("分项站长","STATION_MANAGER",station,1);product=createProduct("分项水",1,"20.00","30.00",1,"18.00");
        createInventoryFull(station,product,100,1,"18.00");address=createAddress(customer,"分项地址");
        createTicketAccount(customer,station,product,10);createBarrelAsset(customer,station,product,10,"300.00");createBarrelLot("SPLIT-RIGHTS",customer,station,product,"30",10,10);
        createCustomerStationConfig(customer,station,1);
        assertTrue(put("/api/manager/delivery-config",staffToken(manager,"STATION_MANAGER",station),"{\"baseDeliveryFee\":3,\"floorFeePerLevel\":2,\"floorFreeLevel\":1}").isSuccess());
        jdbc.update("update address set floor=3,has_elevator=0 where id=?",address);
    }
    String customerToken(){return customerToken(customer);} String managerToken(){return staffToken(manager,"STATION_MANAGER",station);}
    String body(String key,int channel) {return "{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":3,\"ticketFeePaymentMethod\":"+channel+",\"ticketFeeQuoteAmount\":7,\"ticketDeliveryCreditQuote\":0,\"idempotencyKey\":\""+key+"\",\"items\":[{\"productId\":"+product+",\"quantity\":2}]}";}
    long create(String key,int channel) {Api r=post("/api/orders/create",customerToken(),body(key,channel));assertTrue(r.isSuccess(),r.message());return r.data().path("orderId").asLong();}
    int balance(){return intOf("select remain_quantity from ticket_account where customer_id=?",customer);}
    @Test void codTicketTasksRemainReadableOnlyByCurrentStationAndAssignedDriver() {
        seed();
        long driver=createStaff("票单配送员","DELIVERY",station,1);
        long colleague=createStaff("其他配送员","DELIVERY",station,1);
        long otherStation=createStation("他站");
        long outsider=createStaff("他站站长","STATION_MANAGER",otherStation,1);
        String driverToken=staffToken(driver,"DELIVERY",station);
        String colleagueToken=staffToken(colleague,"DELIVERY",station);
        String outsiderToken=staffToken(outsider,"STATION_MANAGER",otherStation);
        long order=create("split-readable-cod",2);
        Api assigned=post("/api/delivery/orders/assign/"+order,managerToken(),"{\"deliveryStaffId\":"+driver+"}");
        assertTrue(assigned.isSuccess(),assigned.message());
        assertTaskListed("assigned-to-me",driverToken,order,true);
        assertTaskListed("assigned-to-me",colleagueToken,order,false);
        for(String path:java.util.List.of("/api/orders/","/api/delivery/orders/")) {
            assertTrue(get(path+order,driverToken).isSuccess(),path);
            assertTrue(get(path+order,managerToken()).isSuccess(),path);
            assertFalse(get(path+order,colleagueToken).isSuccess(),path);
            assertFalse(get(path+order,outsiderToken).isSuccess(),path);
        }
        Api accepted=post("/api/delivery/orders/"+order+"/accept",driverToken,null);
        assertTrue(accepted.isSuccess(),accepted.message());
        assertTaskListed("delivering",driverToken,order,true);
        assertTaskListed("delivering",colleagueToken,order,false);
        assertTrue(get("/api/orders/"+order,driverToken).isSuccess());
        assertTrue(get("/api/delivery/orders/"+order,driverToken).isSuccess());
        assertEquals(1,intOf("select payment_status from orders where id=?",order));
        assertEquals(10,balance(),"reading and accepting must not invent payment or consume tickets");
        long online=create("split-hidden-online",1);
        assertFalse(get("/api/orders/"+online,managerToken()).isSuccess());
        assertFalse(get("/api/delivery/orders/"+online,managerToken()).isSuccess());
    }
    private void assertTaskListed(String list,String token,long order,boolean expected) {
        Api response=get("/api/delivery/orders/"+list,token);
        assertTrue(response.isSuccess(),response.message());
        boolean found=false;
        for(var row:response.data()) if(row.path("id").asLong()==order) found=true;
        assertEquals(expected,found,list);
    }
    @Test void ticketTaskVisibilityRequiresAnActiveCodReservation() {
        var order=new com.example.aquaflow.entity.Orders();
        order.setStatus(1);order.setPaymentStatus(1);order.setPaymentMethod(3);order.setTicketFeePaymentMethod(2);
        for(String state:new String[]{null,"CANCELLED","REFUNDED","UNKNOWN"}) {
            order.setTicketWaterState(state);
            assertFalse(com.example.aquaflow.util.OrderTaskAccess.visibleToEmployee(order));
        }
        for(String state:java.util.List.of("HELD","CONSUMED")) {
            order.setTicketWaterState(state);
            assertTrue(com.example.aquaflow.util.OrderTaskAccess.visibleToEmployee(order));
        }
        order.setTicketFeePaymentMethod(1);
        assertFalse(com.example.aquaflow.util.OrderTaskAccess.visibleToEmployee(order));
        order.setPaymentStatus(2);
        assertTrue(com.example.aquaflow.util.OrderTaskAccess.visibleToEmployee(order));
    }
    Api pay(long order){return post("/api/payments",customerToken(),"{\"orderId\":"+order+",\"paymentMethod\":3,\"amount\":0.01}");}
    @Test void quoteTicketsCoverWaterOnlyAndCreationReservesWithoutConsuming() {
        seed();Api q=post("/api/payments/quote",customerToken(),"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":3,\"ticketFeePaymentMethod\":2,\"items\":[{\"productId\":"+product+",\"quantity\":2}]}");
        assertTrue(q.isSuccess(),q.message());assertEquals(0,new BigDecimal("7").compareTo(q.data().path("ticketPay").path("payableAmount").decimalValue()));
        long order=create("split-quote",2);assertEquals(10,balance());assertEquals(8,lots.availableQuantity(customer,station,product));
        assertEquals(1,intOf("select payment_status from orders where id=?",order));
        assertEquals(0,new BigDecimal("7").compareTo(decimalOf("select amount from payment_record where order_id=?",order)));
        assertEquals(0,decimalOf("select water_amount from payment_record where order_id=?",order).signum());
        assertTrue(jdbc.queryForObject("select note from payment_record where order_id=?",String.class,order).startsWith("水票订单其他费用（不含水费）"));
        Api detail=get("/api/orders/"+order,customerToken());assertTrue(detail.isSuccess(),detail.message());assertEquals(0,new BigDecimal("7").compareTo(detail.data().path("collectAmount").decimalValue()));
        assertTrue(detail.data().path("needCollect").asBoolean());assertFalse(detail.data().path("canRepay").asBoolean());
    }
    @Test void simulatedOnlinePaysOnlyFeesAndRepeatedOriginalRequestConsumesTicketsOnce() {
        seed();long order=create("split-online",1);assertEquals(10,balance());Api paid=pay(order);assertTrue(paid.isSuccess(),paid.message());
        assertEquals(1,paid.data().path("paymentMethod").asInt());assertEquals(0,new BigDecimal("7").compareTo(paid.data().path("amount").decimalValue()));
        assertEquals(8,balance());assertTrue(pay(order).isSuccess());assertEquals(8,balance());
        assertEquals(2,intOf("select payment_status from orders where id=?",order));assertEquals(1,intOf("select count(*) from payment_record where order_id=? and status in (1,2)",order));
        assertFalse(post("/api/orders/create",customerToken(),body("split-online",2)).isSuccess(),"same intent cannot change fee channel");
    }
    @Test void cancellationReleasesUnpaidTicketsAndPaidOnlineReturnsOriginalLotsAndActualCashOnly() {
        seed();long unpaid=create("split-cancel-unpaid",2);assertTrue(put("/api/orders/"+unpaid+"/customer-cancel",customerToken(),null).isSuccess());
        assertEquals(10,lots.availableQuantity(customer,station,product));assertEquals(10,balance());
        long paid=create("split-cancel-paid",1);assertTrue(pay(paid).isSuccess());assertEquals(8,balance());
        Api cancelled=put("/api/orders/"+paid+"/customer-cancel",customerToken(),null);assertTrue(cancelled.isSuccess(),cancelled.message());assertEquals(10,balance());
        assertEquals(0,new BigDecimal("-7").compareTo(decimalOf("select amount from payment_record where order_id=? and status=3 and amount<0",paid)));
        assertEquals(0,decimalOf("select water_amount from payment_record where order_id=? and amount<0",paid).signum());
        assertFalse(pay(paid).isSuccess());assertEquals(10,balance());
    }
    @Test void changedFeeQuoteOrMissingServiceChannelCannotCreateOrConsumeAnything() {
        seed();assertFalse(post("/api/orders/create",customerToken(),body("split-stale",2).replace("\"ticketFeeQuoteAmount\":7","\"ticketFeeQuoteAmount\":0")).isSuccess());
        assertFalse(post("/api/orders/create",customerToken(),body("split-no-channel",2).replace("\"ticketFeePaymentMethod\":2,","")).isSuccess());
        assertEquals(0,intOf("select count(*) from orders where customer_id=?",customer));assertEquals(10,lots.availableQuantity(customer,station,product));assertEquals(10,balance());
    }

    void deliver(long order,boolean collected) {
        Api accepted=post("/api/delivery/orders/"+order+"/accept",managerToken(),null);assertTrue(accepted.isSuccess(),accepted.message());
        Api done=post("/api/delivery/orders/"+order+"/complete",managerToken(),"{\"returnBucketQty\":2,\"collected\":"+collected+"}");assertTrue(done.isSuccess(),done.message());
    }
    long originalPayment(long order){return jdbc.queryForObject("select id from payment_record where order_id=? and status in (1,2)",Long.class,order);}
    Api refund(long payment,String scope,int qty,int cash) {return put("/api/payments/"+payment+"/refund",managerToken(),"{\"scope\":\""+scope+"\",\"note\":\"核对原单实际退回\",\"expectedTicketQty\":"+qty+",\"expectedRefundAmount\":"+cash+"}");}
    @Test void codDeliversWaterWithoutInventingReceivedCashAndThenCollectsOnlyServiceFee() {
        seed();long order=create("split-cod-delivery",2);deliver(order,false);
        assertEquals(3,intOf("select status from orders where id=?",order));assertEquals(1,intOf("select payment_status from orders where id=?",order));assertEquals(8,balance());
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and status=2",order));
        Api collected=post("/api/delivery/orders/"+order+"/confirm-offline-pay",managerToken(),null);assertTrue(collected.isSuccess(),collected.message());
        assertEquals(4,intOf("select status from orders where id=?",order));assertEquals(2,intOf("select payment_status from orders where id=?",order));assertEquals(8,balance());
        assertEquals(0,new BigDecimal("7").compareTo(decimalOf("select amount from payment_record where order_id=? and status=2",order)));
        assertTrue(jdbc.queryForObject("select note from payment_record where order_id=? and status=2",String.class,order).startsWith("水票订单其他费用（不含水费）"));
    }
    @Test void serviceRefundDoesNotReturnWaterAndWaterRefundDoesNotRefundNominalCash() {
        seed();long order=create("split-aftersale",1);assertTrue(pay(order).isSuccess());deliver(order,true);long payment=originalPayment(order);
        Api service=refund(payment,"SERVICE",0,7);assertTrue(service.isSuccess(),service.message());assertEquals(8,balance());
        assertFalse(refund(payment,"SERVICE",0,7).isSuccess());assertEquals(1,intOf("select count(*) from consumption_refund where order_id=?",order));
        Api water=refund(payment,"WATER",2,0);assertTrue(water.isSuccess(),water.message());assertEquals(10,balance());
        assertEquals(3,intOf("select payment_status from orders where id=?",order));
        assertEquals(0,new BigDecimal("-7").compareTo(decimalOf("select sum(amount) from payment_record where order_id=? and amount<0",order)));
        assertFalse(refund(payment,"WATER",2,0).isSuccess());assertEquals(10,balance());
    }
    @Test void returnedWaterDoesNotEraseOutstandingDeliveredServiceFees() {
        seed();long order=create("split-water-before-cod",2);deliver(order,false);long payment=originalPayment(order);
        Api water=refund(payment,"WATER",2,0);assertTrue(water.isSuccess(),water.message());assertEquals(10,balance());assertEquals(1,intOf("select payment_status from orders where id=?",order));
        Api collected=post("/api/delivery/orders/"+order+"/confirm-offline-pay",managerToken(),null);assertTrue(collected.isSuccess(),collected.message());assertEquals(10,balance());
        assertEquals(0,new BigDecimal("7").compareTo(decimalOf("select amount from payment_record where order_id=? and status=2",order)));
    }
    @Test void tierBenefitOffsetsDeliveryOnceNeverFloorAndCancelledHoldCanBeReused() {
        seed();jdbc.update("update ticket_lot set remain_qty=0,status=2 where customer_id=?",customer);jdbc.update("update ticket_account set remain_quantity=0,right_amount=0 where customer_id=?",customer);
        long pack=insert("insert into ticket_package(station_id,product_id,qty,price,unit_price,title,status,sort) values(?,?,10,180,18,'十张送一次配送',1,0)",station,product);
        benefits.configure(station,"CUSTOM",pack,product,true);
        Api bought=post("/api/tickets/purchase",customerToken(),"{\"stationId\":"+station+",\"productId\":"+product+",\"packageId\":"+pack+",\"quantity\":10,\"paymentMethod\":1,\"idempotencyKey\":\"split-benefit-buy\"}");assertTrue(bought.isSuccess(),bought.message());
        String discounted=body("split-benefit-cancel",2).replace("\"ticketFeeQuoteAmount\":7","\"ticketFeeQuoteAmount\":4").replace("\"ticketDeliveryCreditQuote\":0","\"ticketDeliveryCreditQuote\":3");
        Api first=post("/api/orders/create",customerToken(),discounted);assertTrue(first.isSuccess(),first.message());long order=first.data().path("orderId").asLong();assertEquals(0,new BigDecimal("4").compareTo(decimalOf("select cash_due from order_ticket_payment where order_id=?",order)));
        assertTrue(put("/api/orders/"+order+"/customer-cancel",customerToken(),null).isSuccess());
        Api second=post("/api/orders/create",customerToken(),discounted.replace("split-benefit-cancel","split-benefit-use"));assertTrue(second.isSuccess(),second.message());long used=second.data().path("orderId").asLong();deliver(used,true);
        assertEquals("USED",jdbc.queryForObject("select state from ticket_delivery_benefit",String.class));assertEquals(8,balance());
        long next=create("split-benefit-not-permanent",2);assertEquals(0,new BigDecimal("7").compareTo(decimalOf("select cash_due from order_ticket_payment where order_id=?",next)));
    }
    @Test void concurrentOriginalOnlinePaymentsRemainOneCashRecordAndOneTicketConsumption() throws Exception {
        seed();long order=create("split-concurrent",1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {var a=pool.submit(()->{start.await();return pay(order);});var b=pool.submit(()->{start.await();return pay(order);});start.countDown();assertTrue(a.get(30,java.util.concurrent.TimeUnit.SECONDS).isSuccess());assertTrue(b.get(30,java.util.concurrent.TimeUnit.SECONDS).isSuccess());}
        finally{pool.shutdownNow();}
        assertEquals(8,balance());assertEquals(1,intOf("select count(*) from payment_record where order_id=?",order));assertEquals(1,intOf("select count(*) from ticket_record where order_id=? and source='消费'",order));
    }

    @Test void concurrentServiceRefundCannotUseSnapshotFromBeforeTheOtherRefund() throws Exception {
        seed();long order=create("split-concurrent-refund",1);assertTrue(pay(order).isSuccess());deliver(order,true);long payment=originalPayment(order);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {var a=pool.submit(()->{start.await();return refund(payment,"SERVICE",0,7);});var b=pool.submit(()->{start.await();return refund(payment,"SERVICE",0,7);});start.countDown();assertEquals(1,(a.get(30,java.util.concurrent.TimeUnit.SECONDS).isSuccess()?1:0)+(b.get(30,java.util.concurrent.TimeUnit.SECONDS).isSuccess()?1:0));}
        finally{pool.shutdownNow();}
        assertEquals(8,balance());assertEquals(1,intOf("select count(*) from consumption_refund where order_id=?",order));
        assertEquals(0,new BigDecimal("-7").compareTo(decimalOf("select sum(amount) from payment_record where order_id=? and amount<0",order)));
    }

    @Test void unpaidOnlineFeeTimeoutReleasesOriginalReservationButNeverTimesOutCod() {
        seed();long online=create("split-fee-timeout",1),cod=create("split-cod-no-timeout",2);
        jdbc.update("update orders set create_time=date_sub(now(),interval 2 hour) where id in (?,?)",online,cod);
        assertTrue(orderMapper.listTimedOutWechatOrders(30,100).contains(online));assertFalse(orderMapper.listTimedOutWechatOrders(30,100).contains(cod));
        assertTrue(payments.cancelTimedOutWechatOrder(online,30));assertFalse(payments.cancelTimedOutWechatOrder(online,30));assertFalse(payments.cancelTimedOutWechatOrder(cod,30));
        assertEquals(10,balance());assertEquals(8,lots.availableQuantity(customer,station,product));assertEquals(0,intOf("select count(*) from payment_record where order_id=?",online));
    }
    @Test void unknownOriginalFeeChannelIsQueriedAndReplayedWithoutNewMoneyOrTickets() {
        seed();long order=create("split-unknown-channel",1);jdbc.update("update orders set create_time=date_sub(now(),interval 2 hour) where id=?",order);
        long original=insert("insert into payment_record(order_id,customer_id,station_id,payment_method,amount,water_amount,barrel_deposit,delivery_fee,floor_fee,status,transaction_no) values(?,?,?,1,7,0,0,3,4,1,'SIM-WX:00000000-0000-0000-0000-000000000090')",order,customer,station);
        assertFalse(payments.cancelTimedOutWechatOrder(order,30));assertEquals(10,balance());assertEquals(8,lots.availableQuantity(customer,station,product));
        Api retry=pay(order);assertTrue(retry.isSuccess(),retry.message());assertEquals(original,retry.data().path("id").asLong());assertEquals(8,balance());
        assertEquals(1,intOf("select count(*) from payment_record where order_id=?",order));assertTrue(pay(order).isSuccess());assertEquals(8,balance());
    }
    @Test void crossStationTicketBasisAndActuallyCollectedFeesStaySeparate() {
        seed();jdbc.update("update ticket_lot set unit_price=8 where customer_id=?",customer);lots.refreshRightAmount(customer,station,product);
        long order=create("split-cross-station",2);long target=createStation("服务费履约站"),receiver=createStaff("履约站长","STATION_MANAGER",target,1);
        createInventoryFull(target,product,100,1,"18");
        // Isolate settlement accounting from separately covered dispatch agreement/stock-transfer tests.
        jdbc.update("update orders set delivery_station_id=?,settle_station_id=? where id=?",target,target,order);
        jdbc.update("update inventory_reservation set station_id=? where order_id=?",target,order);payments.relocatePendingCollection(order,target);
        String receiverToken=staffToken(receiver,"STATION_MANAGER",target);
        Api assigned=post("/api/delivery/orders/assign/"+order,receiverToken,"{\"deliveryStaffId\":"+receiver+",\"riskAcknowledged\":true}");assertTrue(assigned.isSuccess(),assigned.message());
        Api accepted=post("/api/delivery/orders/"+order+"/accept",receiverToken,null);assertTrue(accepted.isSuccess(),accepted.message());
        Api done=post("/api/delivery/orders/"+order+"/complete",receiverToken,"{\"returnBucketQty\":2,\"collected\":true}");assertTrue(done.isSuccess(),done.message());
        assertEquals(0,new BigDecimal("7").compareTo(decimalOf("select amount from payment_record where order_id=? and station_id=? and status=2",order,target)));
        Api ledger=get("/api/manager/inter-station-settlements",managerToken());assertTrue(ledger.isSuccess(),ledger.message());
        var rows=ledger.data().path("items");assertEquals(1,rows.size());assertEquals(0,new BigDecimal("16").compareTo(rows.get(0).path("amount").decimalValue()),"owner transfers actual prepaid ticket value only; fulfillment already holds fee cash");
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and water_amount<>0",order));assertEquals(8,balance());
    }

    @Test void actualServiceIncomeExcludesUnpaidFeesAndSubtractsOnlyActualServiceRefunds() {
        seed();long order=create("split-actual-service-income",2);
        java.time.LocalDateTime start=java.time.LocalDateTime.now().minusDays(1),end=start.plusDays(2);
        assertEquals(0,((BigDecimal)profits.orderCountAndFees(station,start,end).get("deliveryFee")).signum());
        deliver(order,true);var paid=profits.orderCountAndFees(station,start,end);
        assertEquals(0,new BigDecimal("3").compareTo((BigDecimal)paid.get("deliveryFee")));assertEquals(0,new BigDecimal("4").compareTo((BigDecimal)paid.get("floorFee")));
        Api r=refund(originalPayment(order),"SERVICE",0,7);assertTrue(r.isSuccess(),r.message());var after=profits.orderCountAndFees(station,start,end);
        assertEquals(0,((BigDecimal)after.get("deliveryFee")).signum());assertEquals(0,((BigDecimal)after.get("floorFee")).signum());assertEquals(8,balance());
    }
    @Test void olderTicketsCannotBorrowDeliveryBenefitFromAnUnconsumedNewPurchase() {
        seed();long pack=insert("insert into ticket_package(station_id,product_id,qty,price,unit_price,title,status,sort) values(?,?,10,180,18,'来源绑定档位',1,0)",station,product);benefits.configure(station,"CUSTOM",pack,product,true);
        Api bought=post("/api/tickets/purchase",customerToken(),"{\"stationId\":"+station+",\"productId\":"+product+",\"packageId\":"+pack+",\"quantity\":10,\"paymentMethod\":1,\"idempotencyKey\":\"split-source-bound-buy\"}");assertTrue(bought.isSuccess(),bought.message());
        long order=create("split-old-source",2);assertEquals(0,decimalOf("select delivery_credit from order_ticket_payment where order_id=?",order).signum());
        assertEquals("AVAILABLE",jdbc.queryForObject("select state from ticket_delivery_benefit",String.class));
        assertEquals(0,intOf("select count(*) from order_ticket_hold h join ticket_lot l on l.id=h.lot_id where h.order_id=? and l.source_type=1",order));
    }
    @Test void cancellingDuringPaymentCannotLoseHeldTicketsOrLeaveCashPretendingToBeWater() throws Exception {
        seed();long order=create("split-cancel-pay-race",1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {var a=pool.submit(()->{start.await();return pay(order);});var b=pool.submit(()->{start.await();return put("/api/orders/"+order+"/customer-cancel",customerToken(),null);});start.countDown();Api payment=a.get(30,java.util.concurrent.TimeUnit.SECONDS),cancel=b.get(30,java.util.concurrent.TimeUnit.SECONDS);assertTrue(cancel.isSuccess(),cancel.message());assertTrue(payment.code()==0 || payment.code()==1);}
        finally{pool.shutdownNow();}
        assertEquals(5,intOf("select status from orders where id=?",order));assertEquals(10,balance());assertEquals(10,lots.availableQuantity(customer,station,product));
        assertEquals(0,decimalOf("select coalesce(sum(amount),0) from payment_record where order_id=?",order).signum());
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and water_amount<>0",order));
    }
}
