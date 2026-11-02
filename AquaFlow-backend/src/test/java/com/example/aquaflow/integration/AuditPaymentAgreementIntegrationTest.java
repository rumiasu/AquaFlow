package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-01: real purchase/payment/dispatch SQL, never real payment channels. */
@TestPropertySource(properties="aquaflow.barrel.independent-rights-enabled=true")
class AuditPaymentAgreementIntegrationTest extends AbstractIntegrationTest {
    long a,b,ma,mb,driver,customer,address,product;
    String ca,ta,tb;
    void seed(boolean capacity) {
        a=createStation("审计付款A");b=createStation("审计付款B");
        ma=createStaff("审计站长A","STATION_MANAGER",a,1);mb=createStaff("审计站长B","STATION_MANAGER",b,1);
        driver=createStaff("审计配送员","DELIVERY",b,1);
        customer=createCustomer("合成付款客户","audit-payment-only");address=createAddress(customer,"合成地址");
        product=createProduct("审计桶装水",1,"20.00","30.00",1,"15.00");
        createInventoryFull(a,product,50,1,"15.00");createInventoryFull(b,product,50,1,"15.00");
        createCustomerStationConfig(customer,a,1);
        ca=customerToken(customer);ta=staffToken(ma,"STATION_MANAGER",a);tb=staffToken(mb,"STATION_MANAGER",b);
        if(capacity) confirmPurchase("/api/barrel-rights/purchase",10,"right",null);
    }
    long confirmPurchase(String route,int quantity,String key,Long packageId) {
        Api p=post(route,ca,"{\"stationId\":"+a+",\"productId\":"+product+",\"quantity\":"+quantity+",\"paymentMethod\":2,\"idempotencyKey\":\""+key+"\""+(packageId==null?"":",\"packageId\":"+packageId)+"}");
        assertEquals(0,p.code(),p.toString());long id=p.data().path("paymentId").asLong();
        assertEquals(0,put("/api/payments/"+id+"/confirm",ta,null).code());return id;
    }
    long order(int method,int quantity,boolean purchase) {
        Api p=post("/api/orders/create",ca,"{\"stationId\":"+a+",\"addressId\":"+address+",\"paymentMethod\":"+method+",\"idempotencyKey\":\""+UUID.randomUUID()+"\",\"items\":[{\"productId\":"+product+",\"quantity\":"+quantity+"}]"+(purchase?",\"barrelPurchases\":[{\"productId\":"+product+",\"quantity\":"+quantity+",\"unitPrice\":30}]":"")+"}");
        assertEquals(0,p.code(),p.toString());return p.data().path("orderId").asLong();
    }
    Api pay(long id,int method) {return post("/api/payments",ca,"{\"orderId\":"+id+",\"paymentMethod\":"+method+"}");}

    @ParameterizedTest(name="原方式{0}不能隐式改为{1}")
    @CsvSource({"1,2","1,3","2,1","2,3","3,1","3,2"})
    void everyDifferentPaymentMethodRejectsBeforeAnyIdempotentReturnOrWrites(int original,int requested) {
        seed(true);createTicketAccount(customer,a,product,10);long id=order(original,1,false);
        long records=longOf("select count(*) from payment_record");int before=intOf("select payment_status from orders where id=?",id);
        Api response=pay(id,requested);assertEquals(1,response.code(),response.toString());
        assertTrue(response.message().contains("付款方式"),response.toString());
        assertEquals(records,longOf("select count(*) from payment_record"));assertEquals(before,intOf("select payment_status from orders where id=?",id));
        assertEquals(10,intOf("select remain_quantity from ticket_account where customer_id=?",customer));
        assertEquals(0,longOf("select count(*) from ticket_record where order_id=?",id));
    }
    @ParameterizedTest @CsvSource({"1,2","2,3"})
    void additionalDepositCannotBeUsedToSwitchTheConfirmedMethod(int original,int requested) {
        seed(false);long id=order(original,1,true);long count=longOf("select count(*) from payment_record");
        assertEquals(1,pay(id,requested).code());assertEquals(count,longOf("select count(*) from payment_record"));
    }
    @ParameterizedTest @CsvSource({"1","2","3"})
    void matchingMethodIsIdempotentButDifferentReplayIsStillRejected(int method) {
        seed(true);createTicketAccount(customer,a,product,10);long id=order(method,1,false);
        Api first=pay(id,method);assertEquals(0,first.code(),first.toString());
        Api again=pay(id,method);assertEquals(0,again.code(),again.toString());assertEquals(first.data().path("id"),again.data().path("id"));
        assertEquals(1,pay(id,method==2?3:2).code(),"幂等返回之前也必须核对方式");
        assertEquals(method==3?9:10,intOf("select remain_quantity from ticket_account where customer_id=?",customer));
    }

    @ParameterizedTest @CsvSource({"3,2","2,3"})
    void simultaneousMatchingAndMismatchedRequestsCannotCreateAnAlternativePayment(int original,int changed) throws Exception {
        seed(true);createTicketAccount(customer,a,product,10);long id=order(original,1,false);
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var good=pool.submit(()->{start.await();return pay(id,original);});
            var bad=pool.submit(()->{start.await();return pay(id,changed);});start.countDown();
            assertEquals(0,good.get(20,java.util.concurrent.TimeUnit.SECONDS).code());
            assertEquals(1,bad.get(20,java.util.concurrent.TimeUnit.SECONDS).code());
            assertEquals(1,longOf("select count(*) from payment_record where order_id=?",id));
            assertEquals(original==3?9:10,intOf("select remain_quantity from ticket_account where customer_id=?",customer));
            assertEquals(50,intOf("select quantity from inventory where station_id=? and product_id=?",a,product));
        } finally {pool.shutdownNow();}
    }

    @Test void unauthorizedActorsCannotPayAndAuthorizedManagerCannotSwitchTheMethod() {
        seed(true);createTicketAccount(customer,a,product,10);long id=order(3,1,false);long other=createCustomer("其他合成客户","audit-payment-other");
        String body="{\"orderId\":"+id+",\"paymentMethod\":3}";
        assertEquals(1,post("/api/payments",customerToken(other),body).code());
        assertEquals(1,post("/api/payments",tb,body).code());
        assertEquals(1,post("/api/payments",staffToken(driver,"DELIVERY",b),body).code());
        assertEquals(1,post("/api/payments",ta,"{\"orderId\":"+id+",\"paymentMethod\":2}").code());
        assertEquals(0,longOf("select count(*) from payment_record where order_id=?",id));
        assertEquals(10,intOf("select remain_quantity from ticket_account where customer_id=?",customer));
        assertEquals(0,post("/api/payments",ta,body).code(),"本站站长原有代录付款权限保留，但仍须沿用原方式");
        assertEquals(9,intOf("select remain_quantity from ticket_account where customer_id=?",customer));
    }

    @Test void legacyMismatchedPendingCashCannotBeConfirmedAsATicketPayment() {
        seed(true);createTicketAccount(customer,a,product,10);long id=order(3,1,false);
        long payment=insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount,barrel_deposit) values(?,?,?,?,?,?,?,?)",id,customer,a,2,1,new BigDecimal("15"),new BigDecimal("15"),BigDecimal.ZERO);
        assertEquals(1,put("/api/payments/"+payment+"/confirm",ta,null).code());
        assertEquals(1,intOf("select status from payment_record where id=?",payment));
        assertEquals(0,intOf("select payment_status from orders where id=?",id));
        assertEquals(10,intOf("select remain_quantity from ticket_account where customer_id=?",customer));
    }

    @Test void missingConsumptionEvidenceCannotFreezeAZeroOffer() {
        seed(true);long id=order(3,1,false);jdbc.update("update orders set payment_status=2 where id=?",id);
        assertEquals(1,post("/api/delivery/orders/transfer/"+id+"/outsource",ta,"{\"targetStationId\":"+b+",\"riskAcknowledged\":true}").code());
        assertEquals(0,longOf("select count(*) from dispatch_agreement where order_id=?",id));
        assertEquals(a,longOf("select delivery_station_id from orders where id=?",id));
    }

    @Test void consumedZeroValueGiftIsEvidenceAndMayProduceAZeroDefaultOffer() {
        seed(true);createTicketAccount(customer,a,product,2);
        jdbc.update("update ticket_lot set unit_price=0 where customer_id=?",customer);
        jdbc.update("update ticket_account set right_amount=0 where customer_id=?",customer);
        long id=order(3,1,false);assertEquals(0,pay(id,3).code());
        assertEquals(0,post("/api/delivery/orders/transfer/"+id+"/outsource",ta,"{\"targetStationId\":"+b+",\"riskAcknowledged\":true}").code());
        assertEquals(0,BigDecimal.ZERO.compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?",id)));
    }

    @ParameterizedTest @CsvSource({"false,0.00","false,5.00","true,0.00","true,5.00"})
    @DisplayName("真实购票、消费、外派接受到结款使用实付批次金额")
    void realTicketConsumptionIsIncludedInDefaultAcceptedAgreement(boolean mixed,String fee) {
        seed(true);
        if(mixed) {buyTicketsAt(1,"8.00","first");buyTicketsAt(1,"12.00","second");}
        else buyTicketsAt(2,"10.00","single");
        long id=order(3,2,false);assertEquals(0,pay(id,3).code());
        jdbc.update("update orders set delivery_fee=?,total_amount=water_amount+? where id=?",new BigDecimal(fee),new BigDecimal(fee),id);
        assertEquals(0,post("/api/delivery/orders/transfer/"+id+"/outsource",ta,"{\"targetStationId\":"+b+",\"riskAcknowledged\":true}").code());
        BigDecimal expected=new BigDecimal("20.00").add(new BigDecimal(fee));
        assertEquals(0,expected.compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?",id)));
        assertEquals(0,post("/api/delivery/orders/assign-quoted/"+id,tb,"{\"deliveryStaffId\":"+driver+",\"riskAcknowledged\":true,\"quoteToken\":\""+quoteTokenOf(id,tb)+"\"}").code());
        assertEquals(0,post("/api/delivery/orders/"+id+"/accept",staffToken(driver,"DELIVERY",b),"{}").code());
        assertEquals(0,post("/api/delivery/orders/"+id+"/complete",staffToken(driver,"DELIVERY",b),"{\"collected\":true,\"itemReturns\":[]}").code());
        Api settled=post("/api/manager/inter-station-settlements/"+id+"/settle",ta,"{\"note\":\"合成线下交付凭据\"}");assertEquals(0,settled.code(),settled.toString());
        assertEquals(0,expected.compareTo(decimalOf("select amount from inter_station_settlement where order_id=?",id)));
    }
    void buyTicketsAt(int quantity,String price,String key) {
        Api pkg=post("/api/ticket-packages",ta,"{\"productId\":"+product+",\"qty\":"+quantity+",\"price\":"+new BigDecimal(price).multiply(BigDecimal.valueOf(quantity))+",\"title\":\"合成批次\"}");
        assertEquals(0,pkg.code(),pkg.toString());confirmPurchase("/api/tickets/purchase",quantity,key,pkg.data().path("id").asLong());
        // Real purchased batches remain intact even if this station later changes its retail price.
    }
    /** B-03: the receiving station must present the token it actually displayed. */
    String quoteTokenOf(long id,String stationToken) {
        Api view=get("/api/manager/dispatch-agreements/"+id,stationToken);assertEquals(0,view.code(),view.toString());
        String token=view.data().path("quoteToken").asText();assertTrue(token.matches("[a-f0-9]{64}"),view.toString());return token;
    }
    /**
     * B-03 第 4 项：真实水票批次消费（8+12=20）+ 手工报价 37.50 + 抢单池认领。
     * 用非桶装（category=2、deposit=0）的可购票商品，避免改动「押金/桶权益单不得入池」这条规则。
     */
    @Test void realTicketConsumptionBacksAManualPoolQuoteAndTheViewedTokenFreezesIt() {
        seed(false);
        long bottle=createProduct("审计瓶装水",2,"20.00","0.00",1,"20.00");
        createInventoryFull(a,bottle,50,1,"20.00");createInventoryFull(b,bottle,50,1,"20.00");
        product=bottle;
        buyTicketsAt(1,"8.00","pool-batch-8");buyTicketsAt(1,"12.00","pool-batch-12");
        long id=order(3,2,false);assertEquals(0,pay(id,3).code());
        String consumption="select coalesce(sum(unit_price*decrease_qty),0) from ticket_record where order_id=? and source='消费'";
        assertEquals(0,new BigDecimal("20.00").compareTo(decimalOf(consumption,id)),"两批 8+12 都真实扣票");
        assertEquals(0,post("/api/delivery/orders/transfer/"+id+"/outsource",ta,"{}").code());
        assertEquals(0,new BigDecimal("20.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?",id)),"默认报价取实付票值");
        String viewed=quoteTokenOf(id,tb);
        assertEquals(0,put("/api/manager/dispatch-agreements/"+id,ta,"{\"serviceAmount\":37.50,\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0,\"note\":\"合成手工报价\"}").code());
        String current=quoteTokenOf(id,tb);assertNotEquals(viewed,current,"手工改价必须换轮次");
        assertEquals(1,post("/api/delivery/orders/"+id+"/claim-pool-quoted",tb,"{\"deliveryStaffId\":"+driver+",\"quoteToken\":\""+viewed+"\"}").code());
        assertEquals("OFFERED",jdbc.queryForObject("select status from dispatch_agreement where order_id=?",String.class,id));
        assertEquals(1,intOf("select count(*) from orders where id=? and delivery_station_id is null",id));
        assertEquals(0,post("/api/delivery/orders/"+id+"/claim-pool-quoted",tb,"{\"deliveryStaffId\":"+driver+",\"quoteToken\":\""+current+"\"}").code());
        assertEquals("ACCEPTED",jdbc.queryForObject("select status from dispatch_agreement where order_id=?",String.class,id));
        assertEquals(0,new BigDecimal("37.50").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?",id)),"接受后按展示的 37.50 冻结");
        assertEquals(b,longOf("select target_station_id from dispatch_agreement where order_id=?",id));
        assertEquals(b,longOf("select delivery_station_id from orders where id=?",id));
        assertEquals(a,longOf("select station_id from orders where id=?",id));
        assertEquals(0,new BigDecimal("20.00").compareTo(decimalOf(consumption,id)),"接受报价不改写真实票消费");
        assertEquals(1,intOf("select count(*) from ticket_account where customer_id=? and product_id=? and remain_quantity=0",customer,product),"两批票已全部扣完");
    }
    /**
     * B-03 第 4 项的兜底覆盖：若可购票的非桶装商品不被现有购票规则支持，
     * 这条同样证明「真实票消费 + 手工报价 + 出示 token 的定向接受」可用（桶装商品、定向外派）。
     */
    @Test void realTicketConsumptionWithAManualQuoteIsAcceptedOnTheDirectedRoute() {
        seed(true);
        buyTicketsAt(1,"8.00","directed-8");buyTicketsAt(1,"12.00","directed-12");
        long id=order(3,2,false);assertEquals(0,pay(id,3).code());
        assertEquals(0,post("/api/delivery/orders/transfer/"+id+"/outsource",ta,"{\"targetStationId\":"+b+",\"riskAcknowledged\":true}").code());
        assertEquals(0,new BigDecimal("20.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?",id)));
        assertEquals(0,put("/api/manager/dispatch-agreements/"+id,ta,"{\"serviceAmount\":37.50,\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0,\"note\":\"合成手工报价\"}").code());
        assertEquals(0,post("/api/delivery/orders/assign-quoted/"+id,tb,"{\"deliveryStaffId\":"+driver+",\"riskAcknowledged\":true,\"quoteToken\":\""+quoteTokenOf(id,tb)+"\"}").code());
        assertEquals("ACCEPTED",jdbc.queryForObject("select status from dispatch_agreement where order_id=?",String.class,id));
        assertEquals(0,new BigDecimal("37.50").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?",id)));
        assertEquals(0,new BigDecimal("20.00").compareTo(decimalOf("select coalesce(sum(unit_price*decrease_qty),0) from ticket_record where order_id=? and source='消费'",id)));
    }
}
