package com.example.aquaflow.integration;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真 HTTP/MySQL 锁与事务验收；只在 TestDatabaseTargetGuard 已确认的独立库执行。 */
// 2026-10-27：test profile 默认走旧桶账；漏开此属性会在建单时失败，根本没有执行支付竞态。
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true",
        "aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=true","app.payment.mock-customer-ids=1"})
class IsolatedMockPaymentConcurrencyIntegrationTest extends AbstractIntegrationTest {
    protected long[] seed() {
        long station=createStation("隔离模拟站"), manager=createStaff("隔离站长","STATION_MANAGER",station,1);
        long customer=createCustomer("授权测试客户","isolated-mock-one"), product=createProduct("模拟桶装水",1,"10.00","30.00",0,"0.00");
        assertEquals(1,customer,"每例须在已确认独立库中清表，不能放宽白名单迁就非隔离数据");
        createInventory(station,product,100);
        jdbc.update("update inventory set deposit_price=30 where station_id=? and product_id=?",station,product);
        createInventoryRecord(station,product,100,"STOCK_IN",0);
        long address=createAddress(customer,"测试地址");String token=customerToken(customer);
        // 2026-10-27：裸造旧订单没有随单押金凭据，不能证明当前报价→确认→权益激活链路。
        String input="{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"items\":[{\"productId\":"+product+",\"quantity\":1}]";
        Api quote=post("/api/payments/quote",token,input+"}");assertEquals(0,quote.code(),quote.toString());
        assertTrue(quote.data().path("independentRights").asBoolean(),quote.toString());
        assertEquals(30,quote.data().path("extraDeposit").asInt());assertEquals(40,quote.data().path("totalAmount").asInt());
        Api created=post("/api/orders/create",token,input+",\"idempotencyKey\":\"isolated-mock-first\",\"barrelPurchases\":"+quote.data().path("barrelPurchases")+"}");
        assertEquals(0,created.code(),created.toString());long order=created.data().path("orderId").asLong();assertTrue(order>0);
        assertEquals(1,intOf("select count(*) from order_barrel_purchase where order_id=? and status='PENDING'",order));
        assertEquals(0,intOf("select count(*) from payment_record"));
        assertEquals(0,intOf("select count(*) from deposit_record"));
        assertEquals(0,intOf("select coalesce(sum(remain_qty),0) from customer_barrel_lot"));
        assertEquals(PaymentStatus.UNPAID,intOf("select payment_status from orders where id=?",order));
        assertEquals(1,intOf("select pending_qty from barrel_right_reservation where owner_type='ORDER' and owner_id=? and status='ACTIVE'",order));
        return new long[]{station,manager,customer,product,address,order};
    }
    protected String body(long order) { return "{\"orderId\":"+order+",\"paymentMethod\":1,\"amount\":0.01}"; }

    @Test void capabilitiesUseAuthenticatedCustomerAndNeverCreateMoney() {
        long[] s=seed();
        Api result=get("/api/payments/capabilities",customerToken(s[2]));assertEquals(0,result.code(),result.toString());
        var channel=result.data().path("wechatPay");assertTrue(channel.path("enabled").asBoolean());
        assertEquals("LOCAL_TEST",channel.path("simulationAuthorization").path("scope").asText());
        assertEquals(s[2],channel.path("simulationAuthorization").path("customerId").asLong());
        long other=createCustomer("未授权能力客户","sandbox-cap-other");
        var denied=get("/api/payments/capabilities",customerToken(other));assertEquals(0,denied.code());
        assertFalse(denied.data().path("wechatPay").path("enabled").asBoolean());
        assertEquals(1,get("/api/payments/capabilities",staffToken(s[1],"STATION_MANAGER",s[0])).code());
        assertEquals(0,intOf("select count(*) from payment_record"));assertEquals(0,intOf("select count(*) from deposit_record"));
    }

    protected void money(String expected,String sql,Object... args) {
        assertEquals(0,new BigDecimal(expected).compareTo(decimalOf(sql,args)),sql);
    }

    /** 同一归属的原款、押金批次和分配必须成套落账；付款尚未交出实物。 */
    protected void assertPaid(long[] s,long payment) {
        assertTrue(payment>0);
        assertEquals(OrderStatus.PENDING,intOf("select status from orders where id=?",s[5]));
        assertEquals(PaymentStatus.PAID,intOf("select payment_status from orders where id=?",s[5]));
        assertEquals(1,intOf("select count(*) from payment_record where order_id=?",s[5]));
        assertEquals(1,intOf("select count(*) from payment_record where id=? and order_id=? and customer_id=? and station_id=? and payment_method=1 and status=2 and transaction_no like 'SIM-WX:%'",payment,s[5],s[2],s[0]));
        money("40.00","select amount from payment_record where id=?",payment);
        money("10.00","select water_amount from payment_record where id=?",payment);
        money("30.00","select barrel_deposit from payment_record where id=?",payment);
        money("0.00","select delivery_fee+floor_fee from payment_record where id=?",payment);
        assertEquals(1,intOf("select count(*) from deposit_record where related_order_id=?",s[5]));
        assertEquals(1,intOf("select count(*) from deposit_record where related_order_id=? and customer_id=? and station_id=? and type=?",s[5],s[2],s[0],DepositType.PREPAID));
        money("30.00","select sum(amount) from deposit_record where related_order_id=?",s[5]);
        money("30.00","select balance from customer_deposit_account where customer_id=? and station_id=?",s[2],s[0]);
        assertEquals(1,intOf("select count(*) from order_barrel_purchase where order_id=? and customer_id=? and station_id=? and product_id=? and status='PAID' and payment_id=? and quantity=1 and amount=30 and refunded_qty=0 and refunded_amount=0",s[5],s[2],s[0],s[3],payment));
        assertEquals(1,intOf("select count(*) from order_barrel_purchase p join customer_barrel_lot l on l.id=p.lot_id join deposit_record d on d.id=l.deposit_record_id where p.order_id=? and l.customer_id=? and l.station_id=? and l.product_id=? and l.qty=1 and l.remain_qty=1 and l.unit_price=30 and l.status=1 and d.related_order_id=p.order_id and d.type=?",s[5],s[2],s[0],s[3],DepositType.PREPAID));
        assertEquals(1,intOf("select sum(remain_qty) from customer_barrel_lot where customer_id=? and station_id=? and product_id=?",s[2],s[0],s[3]));
        assertEquals(-1,intOf("select over_qty from customer_barrel_over where customer_id=? and station_id=? and product_id=?",s[2],s[0],s[3]));
        assertEquals(1,intOf("select count(*) from barrel_right_reservation where owner_type='ORDER' and owner_id=? and customer_id=? and station_id=? and product_id=? and status='ACTIVE' and quantity=1 and pending_qty=0 and pickup_qty=1 and pending_pickup_qty=0",s[5],s[2],s[0],s[3]));
        assertEquals(1,intOf("select count(*) from inventory_reservation where order_id=? and station_id=? and product_id=? and status=1 and reserved_qty=1 and shipped_qty=0",s[5],s[0],s[3]));
        assertEquals(100,intOf("select quantity from inventory where station_id=? and product_id=?",s[0],s[3]),"付款不出库");
        assertEquals(0,intOf("select count(*) from ticket_record"));
        assertEquals(0,intOf("select count(*) from customer_barrel_in_transit"),"新模型不写旧配送中桶账");
    }

    @Test void concurrentClicksAndLostReceiptRetryHaveOnePaymentAndDeposit() throws Exception {
        long[] s=seed();String token=customerToken(s[2]);var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(8);var futures=new ArrayList<Future<Api>>();
        try {
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{start.await();return post("/api/payments",token,body(s[5]));}));
            start.countDown();long id=0;
            for(var future:futures) {var result=future.get(30,TimeUnit.SECONDS);assertEquals(0,result.code(),result.toString());
                long current=result.data().path("id").asLong();if(id==0)id=current;assertEquals(id,current);assertTrue(result.data().path("simulated").asBoolean());}
            assertPaid(s,id);
            String transaction=jdbc.queryForObject("select transaction_no from payment_record where id=?",String.class,id);
            // 忽略第一次响应后以原单重试，不能生成另一张单或另一条原款。
            var retry=post("/api/payments",token,body(s[5]));assertEquals(0,retry.code(),retry.toString());assertEquals(id,retry.data().path("id").asLong());
            assertTrue(retry.data().path("simulated").asBoolean());
            assertEquals(transaction,jdbc.queryForObject("select transaction_no from payment_record where id=?",String.class,id));
            assertPaid(s,id);
        } finally {pool.shutdownNow();}
        assertEquals(1,intOf("select count(*) from orders"));
    }
    @Test void otherCustomerUnlistedOwnerAndStaffCannotTriggerSimulation() {
        long[] s=seed();long other=createCustomer("未授权客户","isolated-mock-two");
        assertEquals(1,post("/api/payments",customerToken(other),body(s[5])).code());
        long address=createAddress(other,"另一测试地址");
        long own=createOrderFull(other,address,s[0],s[3],1,1,1,"10.00","30.00","40.00",true,1);
        assertEquals(1,post("/api/payments",customerToken(other),body(own)).code());
        assertEquals(1,post("/api/payments",staffToken(s[1],"STATION_MANAGER",s[0]),body(s[5])).code());
        assertEquals(0,intOf("select count(*) from payment_record"));
        assertEquals(0,intOf("select count(*) from deposit_record"));
        assertEquals(0,intOf("select count(*) from customer_barrel_lot"));
        assertEquals(PaymentStatus.UNPAID,intOf("select payment_status from orders where id=?",s[5]));
        assertEquals(1,intOf("select count(*) from order_barrel_purchase where order_id=? and status='PENDING' and payment_id is null and lot_id is null",s[5]));
        assertEquals(1,intOf("select pending_qty from barrel_right_reservation where owner_type='ORDER' and owner_id=? and status='ACTIVE'",s[5]));
    }
    @Test void cancellationAndPaymentRaceLeavesNoPaidResurrectionOrNetMoney() throws Exception {
        long[] s=seed();
        // 2026-10-27：随机抢锁只能覆盖一个赢家；另用响应完成闸门确定两种提交顺序，保留真正同时起跑。
        for(String first:new String[]{"TOGETHER","PAYMENT","CANCEL"}) {
            if(!first.equals("TOGETHER"))s[5]=anotherOrder(s,"isolated-race-"+first);
            race(s,first);
        }
    }

    private long anotherOrder(long[] s,String key) {
        String token=customerToken(s[2]);
        String input="{\"stationId\":"+s[0]+",\"addressId\":"+s[4]+",\"paymentMethod\":1,\"items\":[{\"productId\":"+s[3]+",\"quantity\":1}]";
        Api quote=post("/api/payments/quote",token,input+"}");assertEquals(0,quote.code(),quote.toString());
        assertEquals(0,intOf("select coalesce(sum(remain_qty),0) from customer_barrel_lot"));
        Api order=post("/api/orders/create",token,input+",\"idempotencyKey\":\""+key+"\",\"barrelPurchases\":"+quote.data().path("barrelPurchases")+"}");
        assertEquals(0,order.code(),order.toString());long id=order.data().path("orderId").asLong();assertTrue(id>0);return id;
    }

    private void race(long[] s,String first) throws Exception {
        String token=customerToken(s[2]);long order=s[5];
        var start=new CountDownLatch(1);var finishedFirst=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Api paid;
        try {
            var pay=pool.submit(()->{
                start.await();if(first.equals("CANCEL"))assertTrue(finishedFirst.await(30,TimeUnit.SECONDS));
                try {return post("/api/payments",token,body(order));}
                finally {if(first.equals("PAYMENT"))finishedFirst.countDown();}
            });
            var cancel=pool.submit(()->{
                start.await();if(first.equals("PAYMENT"))assertTrue(finishedFirst.await(30,TimeUnit.SECONDS));
                try {return put("/api/orders/"+order+"/customer-cancel",token,null);}
                finally {if(first.equals("CANCEL"))finishedFirst.countDown();}
            });
            start.countDown();paid=pay.get(40,TimeUnit.SECONDS);
            Api cancelled=cancel.get(40,TimeUnit.SECONDS);assertEquals(0,cancelled.code(),first+": "+cancelled);
            assertTrue(paid.code()==0 || paid.code()==1,first+": "+paid);
            if(first.equals("PAYMENT"))assertEquals(0,paid.code(),paid.toString());
            if(first.equals("CANCEL"))assertEquals(1,paid.code(),paid.toString());
        } finally {pool.shutdownNow();}
        boolean wasPaid=paid.code()==0;
        assertEquals(OrderStatus.CANCELLED,intOf("select status from orders where id=?",order));
        assertEquals(wasPaid?PaymentStatus.REFUNDED:PaymentStatus.CANCELLED,intOf("select payment_status from orders where id=?",order));
        assertEquals(wasPaid?2:0,intOf("select count(*) from payment_record where order_id=?",order));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and status<>3",order));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and (customer_id<>? or station_id<>? or payment_method<>1 or transaction_no is null or transaction_no not like 'SIM-WX:%')",order,s[2],s[0]));
        money("0.00","select coalesce(sum(amount),0) from payment_record where order_id=?",order);
        money(wasPaid?"40.00":"0.00","select coalesce(sum(amount),0) from payment_record where order_id=? and amount>0",order);
        money(wasPaid?"-40.00":"0.00","select coalesce(sum(amount),0) from payment_record where order_id=? and amount<0",order);
        money("0.00","select coalesce(sum(water_amount),0) from payment_record where order_id=?",order);
        money("0.00","select coalesce(sum(barrel_deposit),0) from payment_record where order_id=?",order);
        assertEquals(wasPaid?2:0,intOf("select count(*) from deposit_record where related_order_id=?",order));
        assertEquals(wasPaid?1:0,intOf("select count(*) from deposit_record where related_order_id=? and type=? and amount=30",order,DepositType.PREPAID));
        assertEquals(wasPaid?1:0,intOf("select count(*) from deposit_record where related_order_id=? and type=? and amount=-30",order,DepositType.CANCEL_PREPAID));
        money("0.00","select coalesce(sum(amount),0) from deposit_record where related_order_id=?",order);
        money("0.00","select coalesce(sum(balance),0) from customer_deposit_account where customer_id=? and station_id=?",s[2],s[0]);
        assertEquals(1,intOf("select count(*) from order_barrel_purchase where order_id=? and status=? and refunded_qty=? and refunded_amount=?",order,wasPaid?"REFUNDED":"CANCELLED",wasPaid?1:0,wasPaid?30:0));
        assertEquals(wasPaid?1:0,intOf("select count(*) from order_barrel_purchase p join customer_barrel_lot l on l.id=p.lot_id where p.order_id=? and l.customer_id=? and l.station_id=? and l.product_id=? and l.remain_qty=0",order,s[2],s[0],s[3]));
        assertEquals(wasPaid?1:0,intOf("select count(*) from order_barrel_refund r join order_barrel_purchase p on p.id=r.purchase_id join payment_record original on original.id=r.original_payment_id join payment_record refund on refund.id=r.refund_payment_id where p.order_id=? and r.original_payment_id=p.payment_id and original.order_id=p.order_id and refund.order_id=p.order_id and original.amount=40 and refund.amount=-40 and r.reason='CANCEL' and r.quantity=1 and r.amount=30",order));
        assertEquals(0,intOf("select coalesce(sum(remain_qty),0) from customer_barrel_lot where customer_id=? and station_id=? and product_id=?",s[2],s[0],s[3]));
        assertEquals(0,intOf("select coalesce(sum(over_qty),0) from customer_barrel_over where customer_id=? and station_id=? and product_id=?",s[2],s[0],s[3]));
        assertEquals(1,intOf("select count(*) from barrel_right_reservation where owner_type='ORDER' and owner_id=? and status='RELEASED'",order));
        assertEquals(0,intOf("select count(*) from inventory_reservation where order_id=? and (status<>3 or shipped_qty<>0)",order));
        assertEquals(0,intOf("select count(*) from barrel_right_reservation where owner_type='ORDER' and owner_id=? and status='ACTIVE'",order));
        assertEquals(1,post("/api/payments",token,body(order)).code());
        assertEquals(wasPaid?2:0,intOf("select count(*) from payment_record where order_id=?",order),"取消后的重试不能另起原款");
        assertEquals(0,intOf("select count(*) from ticket_record"));
        assertEquals(100,intOf("select quantity from inventory where station_id=? and product_id=?",s[0],s[3]));
    }
}
