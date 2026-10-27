package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真 HTTP/MySQL 锁与事务验收；只在 TestDatabaseTargetGuard 已确认的独立库执行。 */
@TestPropertySource(properties={"app.payment.mock-wechat-pay=true","app.payment.mock-customer-ids=1"})
class IsolatedMockPaymentConcurrencyIntegrationTest extends AbstractIntegrationTest {
    private long[] seed() {
        long station=createStation("隔离模拟站"), manager=createStaff("隔离站长","STATION_MANAGER",station,1);
        long customer=createCustomer("授权测试客户","isolated-mock-one"), product=createProduct("模拟桶装水",1,"10.00","30.00",0,"0.00");
        assertEquals(1,customer,"每例须在已确认独立库中清表，不能放宽白名单迁就非隔离数据");
        createInventory(station,product,100);long address=createAddress(customer,"测试地址");
        long order=createOrderFull(customer,address,station,product,1,1,1,"10.00","30.00","40.00",true,1);
        return new long[]{station,manager,customer,product,address,order};
    }
    private String body(long order) { return "{\"orderId\":"+order+",\"paymentMethod\":1,\"amount\":0.01}"; }

    @Test void concurrentClicksAndLostReceiptRetryHaveOnePaymentAndDeposit() throws Exception {
        long[] s=seed();String token=customerToken(s[2]);var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(8);var futures=new ArrayList<Future<Api>>();
        try {
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{start.await();return post("/api/payments",token,body(s[5]));}));
            start.countDown();long id=0;
            for(var future:futures) {var result=future.get(30,TimeUnit.SECONDS);assertEquals(0,result.code(),result.toString());
                long current=result.data().path("id").asLong();if(id==0)id=current;assertEquals(id,current);assertTrue(result.data().path("simulated").asBoolean());}
            // 忽略第一次响应后以原单重试，不能生成另一张单或另一条原款。
            var retry=post("/api/payments",token,body(s[5]));assertEquals(0,retry.code());assertEquals(id,retry.data().path("id").asLong());
        } finally {pool.shutdownNow();}
        assertEquals(1,intOf("select count(*) from payment_record where order_id=?",s[5]));
        assertEquals(1,intOf("select count(*) from deposit_record where related_order_id=? and type=1",s[5]));
        assertEquals(0,new java.math.BigDecimal("40.00").compareTo(decimalOf("select amount from payment_record where order_id=?",s[5])));
        assertEquals(100,intOf("select quantity from inventory where station_id=? and product_id=?",s[0],s[3]),"付款不出库");
    }
    @Test void otherCustomerUnlistedOwnerAndStaffCannotTriggerSimulation() {
        long[] s=seed();long other=createCustomer("未授权客户","isolated-mock-two");
        assertNotEquals(0,post("/api/payments",customerToken(other),body(s[5])).code());
        long address=createAddress(other,"另一测试地址");
        long own=createOrderFull(other,address,s[0],s[3],1,1,1,"10.00","30.00","40.00",true,1);
        assertNotEquals(0,post("/api/payments",customerToken(other),body(own)).code());
        assertNotEquals(0,post("/api/payments",staffToken(s[1],"STATION_MANAGER",s[0]),body(s[5])).code());
        assertEquals(0,intOf("select count(*) from payment_record"));
    }
    @Test void cancellationAndPaymentRaceLeavesNoPaidResurrectionOrNetMoney() throws Exception {
        long[] s=seed();String token=customerToken(s[2]);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var pay=pool.submit(()->{start.await();return post("/api/payments",token,body(s[5]));});
            var cancel=pool.submit(()->{start.await();return put("/api/orders/"+s[5]+"/customer-cancel",token,null);});
            start.countDown();pay.get(30,TimeUnit.SECONDS);assertEquals(0,cancel.get(30,TimeUnit.SECONDS).code());
        } finally {pool.shutdownNow();}
        assertEquals(5,intOf("select status from orders where id=?",s[5]));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and status=2",s[5]));
        assertEquals(0,decimalOf("select coalesce(sum(amount),0) from payment_record where order_id=?",s[5]).signum());
        assertNotEquals(0,post("/api/payments",token,body(s[5])).code());
        assertEquals(100,intOf("select quantity from inventory where station_id=? and product_id=?",s[0],s[3]));
    }
}
