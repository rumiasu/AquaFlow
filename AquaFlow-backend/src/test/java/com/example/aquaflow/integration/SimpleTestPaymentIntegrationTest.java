package com.example.aquaflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Same real HTTP/MySQL concurrency and cancellation invariants, enabled by only the deployment test switch. */
@TestPropertySource(inheritProperties=false, properties={"app.payment.test-mode=true","app.payment.mock-wechat-pay=false",
        "app.payment.mock-isolated-environment=false","app.payment.mock-customer-ids=",
        "aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false"})
class SimpleTestPaymentIntegrationTest extends IsolatedMockPaymentConcurrencyIntegrationTest {
    @Override @Test void capabilitiesUseAuthenticatedCustomerAndNeverCreateMoney() {
        long[] s=seed();long other=createCustomer("第二位测试顾客","simple-test-other");
        for(long id:new long[]{s[2],other}) {
            var result=get("/api/payments/capabilities",customerToken(id));assertEquals(0,result.code());
            var channel=result.data().path("wechatPay");assertTrue(channel.path("enabled").asBoolean());
            assertEquals("测试付款（不扣真实款）",channel.path("label").asText());
            assertEquals("SERVER_TEST",channel.path("simulationAuthorization").path("scope").asText());
            assertEquals(id,channel.path("simulationAuthorization").path("customerId").asLong());
        }
        assertEquals(1,get("/api/payments/capabilities",staffToken(s[1],"STATION_MANAGER",s[0])).code());
        assertEquals(0,intOf("select count(*) from payment_record"));
        assertEquals(0,intOf("select count(*) from deposit_record"));
    }
    @Override @Test void otherCustomerUnlistedOwnerAndStaffCannotTriggerSimulation() {
        long[] s=seed();long other=createCustomer("不可支付他人订单","simple-test-other-owner");
        assertEquals(1,post("/api/payments",customerToken(other),body(s[5])).code());
        assertEquals(1,post("/api/payments",staffToken(s[1],"STATION_MANAGER",s[0]),body(s[5])).code());
        assertEquals(0,intOf("select count(*) from payment_record"));
        assertEquals(0,intOf("select count(*) from customer_barrel_lot"));
        assertEquals(0,intOf("select payment_status from orders where id=?",s[5]));
    }
    @Test void allThreeEntriesPersistOnceAndCancellationPreservesIndependentTicketAndDeposit() {
        long[] s=seed();String token=customerToken(s[2]);
        var paid=post("/api/payments",token,body(s[5]));assertEquals(0,paid.code(),paid.toString());
        assertPaid(s,paid.data().path("id").asLong());
        assertEquals(2,get("/api/orders/"+s[5],token).data().path("paymentStatus").asInt());
        jdbc.update("update product set ticket_enabled=1,ticket_price=8 where id=?",s[3]);
        jdbc.update("update inventory set ticket_enabled=1,ticket_price=8 where station_id=? and product_id=?",s[0],s[3]);
        String input="{\"stationId\":"+s[0]+",\"productId\":"+s[3]+",\"quantity\":1,\"paymentMethod\":1,\"idempotencyKey\":\"";
        for(String kind:new String[]{"ticket","barrel"}) {
            String endpoint=kind.equals("ticket")?"/api/tickets/purchase":"/api/barrel-rights/purchase";
            String request=input+"simple-test-"+kind+"\"}";
            var first=post(endpoint,token,request);var retry=post(endpoint,token,request);
            assertEquals(0,first.code(),first.toString());assertEquals(0,retry.code(),retry.toString());
            assertEquals(2,first.data().path("status").asInt());assertTrue(first.data().path("simulated").asBoolean());
            assertTrue(first.data().path("paymentId").asLong()>0);
            assertEquals(first.data().path("paymentId"),retry.data().path("paymentId"));
        }
        assertEquals(3,intOf("select count(*) from payment_record"));
        assertEquals(1,intOf("select remain_quantity from ticket_account where customer_id=? and station_id=? and product_id=?",s[2],s[0],s[3]));
        assertEquals(2,intOf("select sum(remain_qty) from customer_barrel_lot"));
        money("60.00","select balance from customer_deposit_account");
        assertEquals(0,put("/api/orders/"+s[5]+"/customer-cancel",token,null).code());
        money("0.00","select sum(amount) from payment_record where order_id=?",s[5]);
        money("30.00","select balance from customer_deposit_account");
        assertEquals(1,intOf("select sum(remain_qty) from customer_barrel_lot"));
        assertEquals(1,intOf("select remain_quantity from ticket_account"));
        assertEquals(100,intOf("select quantity from inventory where station_id=? and product_id=?",s[0],s[3]));
    }
}
