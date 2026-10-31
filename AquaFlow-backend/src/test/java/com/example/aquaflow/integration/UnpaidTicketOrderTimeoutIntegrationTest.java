package com.example.aquaflow.integration;

import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.service.UnpaidWechatOrderSweeper;
import com.example.aquaflow.service.BusinessWaitingService;
import com.example.aquaflow.mapper.BusinessWaitingMapper;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false"})
class UnpaidTicketOrderTimeoutIntegrationTest extends AbstractIntegrationTest {
    @Autowired UnpaidWechatOrderSweeper sweeper;
    @Autowired PaymentService payments;
    @Autowired BarrelLedgerService ledger;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BusinessWaitingMapper waiting;
    @Autowired BusinessWaitingService barrelExpiry;
    long station,customer,address,water;
    String token;

    void seed(int tickets) {
        station=createStation("Ticket expiry station");
        customer=createCustomer("Ticket expiry customer","ticket-expiry-customer");
        address=createAddress(customer,"Ticket expiry address");
        water=createProduct("Ticket expiry water",1,"20.00","30.00",1,"8.00");
        createInventoryFull(station,water,20,1,"8.00");
        createInventoryRecord(station,water,20,"STOCK_IN",0);
        createCustomerStationConfig(customer,station,1);
        ledger.purchaseRight(customer,station,water,new BigDecimal("30.00"),3,null);
        createDepositBalance(customer,station,"90.00");
        if(tickets>0)createTicketAccount(customer,station,water,tickets);
        token=customerToken(customer);
    }
    long place(int method,String key,boolean expired) {
        var created=post("/api/orders/create",token,"{\"stationId\":"+station+",\"addressId\":"+address
                +",\"paymentMethod\":"+method+",\"idempotencyKey\":\""+key+"\",\"items\":[{\"productId\":"+water+",\"quantity\":1}]}");
        assertEquals(0,created.code(),created.toString());
        long order=created.data().path("orderId").asLong();
        if(expired)jdbc.update("update orders set create_time=date_sub(now(),interval 31 minute) where id=?",order);
        return order;
    }
    Api pay(long order) {return post("/api/payments",token,"{\"orderId\":"+order+",\"paymentMethod\":3}");}
    int tickets() {return intOf("select coalesce(sum(remain_quantity),0) from ticket_account where customer_id=? and station_id=?",customer,station);}
    int reserved(long order) {return intOf("select count(*) from inventory_reservation where order_id=? and status=1",order);}
    void unchangedAssets() {
        assertEquals(3,ledger.rightQty(customer,station,water));
        assertEquals(0,ledger.occupiedQty(customer,station,water));
        assertEquals(90,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).intValueExact());
        assertEquals(20,intOf("select quantity from inventory where station_id=? and product_id=?",station,water));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void zeroTicketOrderReleasesInventoryAndBarrelCapacityEvenWithoutPaymentRequest(boolean failedPaymentAttempt) {
        seed(0);long order=place(3,"unpaid-ticket-expiry",true);
        if(failedPaymentAttempt)assertEquals(1,pay(order).code(),"Insufficient tickets must roll back the payment request");
        assertEquals(1,reserved(order));assertEquals(2,ledger.availableRights(customer,station,water));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=?",order));
        System.out.println("TICKET_TIMEOUT_BEFORE order="+order+" reserved="+reserved(order)+" availableRights="+ledger.availableRights(customer,station,water));
        assertEquals(1,sweeper.sweepOnce(),"An expired unpaid ticket order must not keep reservations forever");
        assertEquals(5,intOf("select status from orders where id=?",order));
        assertEquals(4,intOf("select payment_status from orders where id=?",order));
        assertEquals(0,reserved(order));assertEquals(3,ledger.availableRights(customer,station,water));
        assertEquals(0,tickets());unchangedAssets();
        assertEquals(0,intOf("select count(*) from payment_record where order_id=?",order),"Never invent a refund for an unpaid order");
        assertEquals(0,sweeper.sweepOnce());assertEquals(1,pay(order).code(),"Late payment cannot revive the cancelled order");
        assertEquals(0,reserved(order));unchangedAssets();
    }

    @Test void cashFreshAndPaidTicketOrdersAreProtectedAndManualCancellationRestoresTicketsOnce() {
        seed(2);
        long cash=place(2,"cash-expiry-protected",true),fresh=place(3,"fresh-ticket-protected",false),paid=place(3,"paid-ticket-protected",true);
        assertEquals(0,pay(paid).code());assertEquals(1,tickets());
        assertEquals(0,sweeper.sweepOnce());
        for(long id:new long[]{cash,fresh,paid}){assertEquals(1,intOf("select status from orders where id=?",id));assertEquals(1,reserved(id));}
        assertEquals(2,intOf("select payment_status from orders where id=?",paid));
        payments.refundOrder(paid,"Ticket timeout regression: customer cancellation");
        assertEquals(2,tickets());assertEquals(0,reserved(paid));
        assertThrows(com.example.aquaflow.exception.BusinessException.class,()->payments.refundOrder(paid,"Repeated cancellation"));
        assertEquals(2,tickets());assertEquals(1,intOf("select count(*) from payment_record where order_id=? and amount<0",paid));
        assertEquals(0,sweeper.sweepOnce());unchangedAssets();
    }

    @ParameterizedTest @ValueSource(ints={1,2})
    void activePaymentEvidenceIsNeverGuessedToBeUnpaid(int paymentStatus) {
        seed(0);long order=place(3,"ticket-active-evidence",true);
        createPaymentRecord(order,customer,station,"8.00",3,paymentStatus);
        assertEquals(0,sweeper.sweepOnce());
        assertFalse(payments.cancelTimedOutWechatOrder(order,30));
        assertEquals(1,intOf("select status from orders where id=?",order));assertEquals(1,reserved(order));
        assertEquals(paymentStatus,intOf("select status from payment_record where order_id=?",order));
        unchangedAssets();
    }

    @Test void nonBarrelTicketOrderWithoutAnyBarrelReservationAlsoExpires() {
        station=createStation("Nonbarrel ticket expiry station");
        customer=createCustomer("Nonbarrel ticket expiry customer","nonbarrel-ticket-expiry");
        address=createAddress(customer,"Nonbarrel ticket expiry address");
        water=createProduct("Ticket bottle water",2,"2.00","0.00",1,"1.00");
        createInventoryFull(station,water,20,1,"1.00");
        token=customerToken(customer);
        long order=place(3,"nonbarrel-ticket-expiry",true);
        assertEquals(0,intOf("select count(*) from barrel_right_reservation where owner_type='ORDER' and owner_id=?",order));
        assertFalse(waiting.unpaidExpired(15).contains(order),"The separate 15-minute barrel job cannot discover this order");
        assertEquals(1,reserved(order));
        assertEquals(1,sweeper.sweepOnce(),"The general prepaid timeout must cover nonbarrel ticket orders too");
        assertEquals(5,intOf("select status from orders where id=?",order));assertEquals(0,reserved(order));
        assertEquals(0,sweeper.sweepOnce());assertEquals(0,tickets());
        assertEquals(20,intOf("select quantity from inventory where station_id=? and product_id=?",station,water));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=?",order));
    }

    @Test void existingFifteenMinuteBarrelExpiryStillCoversNewTicketOrders() {
        seed(0);long order=place(3,"ticket-barrel-fifteen-minute",true);
        assertTrue(waiting.unpaidExpired(15).contains(order),"New-model barrel orders already have a separate timeout path");
        barrelExpiry.expire(order);
        assertEquals(5,intOf("select status from orders where id=?",order));assertEquals(0,reserved(order));
        assertEquals(3,ledger.availableRights(customer,station,water));assertEquals(0,sweeper.sweepOnce());
        assertEquals(0,tickets());unchangedAssets();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void paymentAndTimeoutSerializeOnTheSameOrderWithoutLosingTicketsOrReservations(boolean paymentWins) throws Exception {
        seed(2);long order=place(3,"ticket-expiry-race",true);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            if(paymentWins) {
                Future<Boolean> expiry=new TransactionTemplate(transactions).execute(status->{
                    jdbc.queryForList("select id from orders where id=? for update",order);
                    CountDownLatch entered=new CountDownLatch(1);
                    Future<Boolean> waiting=pool.submit(()->{entered.countDown();return payments.cancelTimedOutWechatOrder(order,30);});
                    try {assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiting.get(300,TimeUnit.MILLISECONDS));}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                    payments.createPayment(order,customer,null,null,null,0,3,null,null,"Ticket timeout race");
                    return waiting;
                });
                assertNotNull(expiry);assertFalse(expiry.get(15,TimeUnit.SECONDS));
                assertEquals(2,intOf("select payment_status from orders where id=?",order));
                assertEquals(1,intOf("select status from orders where id=?",order));assertEquals(1,tickets());assertEquals(1,reserved(order));
                payments.refundOrder(order,"Cancel paid ticket order after timeout race");
                assertEquals(2,tickets());assertEquals(0,reserved(order));
            } else {
                Future<Api> payment=new TransactionTemplate(transactions).execute(status->{
                    jdbc.queryForList("select id from orders where id=? for update",order);
                    CountDownLatch entered=new CountDownLatch(1);
                    Future<Api> waiting=pool.submit(()->{entered.countDown();return pay(order);});
                    try {assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiting.get(300,TimeUnit.MILLISECONDS));}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                    assertTrue(payments.cancelTimedOutWechatOrder(order,30));return waiting;
                });
                assertNotNull(payment);assertEquals(1,payment.get(15,TimeUnit.SECONDS).code());
                assertEquals(5,intOf("select status from orders where id=?",order));assertEquals(2,tickets());assertEquals(0,reserved(order));
                assertEquals(0,intOf("select count(*) from payment_record where order_id=?",order));
            }
            assertEquals(3,ledger.availableRights(customer,station,water));assertEquals(0,sweeper.sweepOnce());unchangedAssets();
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
