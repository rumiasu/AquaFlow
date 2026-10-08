package com.example.aquaflow.integration;

import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Exact isolated MySQL and real HTTP; no production channel. */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=false"})
class ClosedStationTicketPurchaseIntegrationTest extends AbstractIntegrationTest {
    @Autowired BarrelLedgerService ledger;
    long station,customer,manager,product; String cus,mgr;
    void seed() {
        station=createStation("停业专项站"); customer=createCustomer("客户","closed-ticket-customer");
        manager=createStaff("站长","STATION_MANAGER",station,1);
        product=createProduct("水",1,"20.00","30.00",1,"8.00");
        createInventoryFull(station,product,20,1,"8.00");
        cus=customerToken(customer);mgr=staffToken(manager,"STATION_MANAGER",station);
    }
    String body(String key,int method) {return "{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":3,\"paymentMethod\":"+method+",\"idempotencyKey\":\""+key+"\"}";}
    long buyRight() {
        Api r=post("/api/barrel-rights/purchase",cus,body("right",2).replace("\"quantity\":3","\"quantity\":1"));assertEquals(0,r.code(),r.toString());
        long p=r.data().path("paymentId").asLong();assertEquals(0,put("/api/payments/"+p+"/confirm",mgr,null).code());return p;
    }
    void close() {jdbc.update("update station set status=2 where id=?",station);}
    @ParameterizedTest @ValueSource(ints={1,2})
    void closedStationRejectsNewTicketMoneyWithoutAssetWrites(int method) {
        seed();close();Api r=post("/api/tickets/purchase",cus,body("new",method));assertEquals(1,r.code(),r.toString());
        assertEquals(0,intOf("select count(*) from payment_record"));assertEquals(0,intOf("select count(*) from ticket_account"));
        assertEquals(0,intOf("select count(*) from ticket_lot"));assertEquals(0,intOf("select count(*) from ticket_record"));
        assertEquals(0,intOf("select count(*) from ticket_purchase_fence"),"failed transaction must release its tentative fence");
    }
    @ParameterizedTest @ValueSource(ints={1,2,4})
    void softStatusDoesNotBlockNewPurchase(int soft) {
        seed();buyRight();jdbc.update("update station set operating_status=? where id=?",soft,station);
        Api r=post("/api/tickets/purchase",cus,body("soft",2));assertEquals(0,r.code(),r.toString());
        assertEquals(1,r.data().path("status").asInt());assertEquals(0,intOf("select count(*) from ticket_lot"));
    }
    @Test void heldTicketsOriginalReplayAndActualExitStillWorkAfterClosure() {
        seed();buyRight();Api r=post("/api/tickets/purchase",cus,body("old",2));assertEquals(0,r.code(),r.toString());long p=r.data().path("paymentId").asLong();
        assertEquals(0,put("/api/payments/"+p+"/confirm",mgr,null).code());close();
        assertEquals(0,get("/api/tickets?stationId="+station,cus).code());
        Api replay=post("/api/tickets/purchase",cus,body("old",2));assertEquals(0,replay.code(),replay.toString());assertEquals(p,replay.data().path("paymentId").asLong());
        assertEquals(3,intOf("select sum(remain_qty) from ticket_lot"));
        Api refund=put("/api/payments/"+p+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedTicketQty\":3,\"expectedTicketAmount\":24}");assertEquals(0,refund.code(),refund.toString());
        assertEquals(0,intOf("select sum(remain_qty) from ticket_lot"));assertEquals(3,intOf("select quantity from ticket_exit_refund where original_payment_id=?",p));
        assertEquals(1,put("/api/payments/"+p+"/refund",mgr,"{\"scope\":\"WATER\"}").code());assertEquals(1,intOf("select count(*) from ticket_exit_refund"));
    }
    @Test void existingDepositReturnCanFinishAfterClosure() {
        seed();buyRight();close();assertEquals(0,get("/api/barrels/summary-by-type?stationId="+station,cus).code());
        Api r=post("/api/barrels/return",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"pickupMode\":\"STORE\",\"idempotencyKey\":\"return\"}");assertEquals(0,r.code(),r.toString());long id=r.data().path("recordId").asLong();
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":0}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/status",mgr,"{\"status\":2}").code());
        Api paid=put("/api/barrels/records/"+id+"/status",mgr,"{\"status\":3,\"refundChannel\":\"CASH\"}");assertEquals(0,paid.code(),paid.toString());
        assertEquals(0,ledger.rightQty(customer,station,product));assertNotNull(jdbc.queryForObject("select refund_paid_time from barrel_record where id=?",java.sql.Timestamp.class,id));
        assertEquals(new BigDecimal("0.00"),decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station));
    }
    @Test void historicalOrderCashRefundIsNotBlockedByClosure() {
        seed();long address=createAddress(customer,"地址");long order=createOrderFull(customer,address,station,product,4,2,2,"20.00","0.00","20.00",false,1);
        long payment=createPaymentRecord(order,customer,station,"20.00",2,2);
        jdbc.update("update payment_record set water_amount=20,barrel_deposit=0 where id=?",payment);close();
        Api refunded=put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\"}");assertEquals(0,refunded.code(),refunded.toString());
        assertEquals(new BigDecimal("-20.00"),decimalOf("select amount from payment_record where order_id=? and amount<0",order));
        assertEquals(4,intOf("select status from orders where id=?",order));assertEquals(3,intOf("select payment_status from orders where id=?",order));
    }
    @Test void pendingPreClosurePurchaseKeepsExistingCollectionBehaviorPendingDecision() {
        seed();buyRight();Api r=post("/api/tickets/purchase",cus,body("pending",2));assertEquals(0,r.code(),r.toString());long p=r.data().path("paymentId").asLong();close();
        Api replay=post("/api/tickets/purchase",cus,body("pending",2));assertEquals(0,replay.code(),replay.toString());assertEquals(p,replay.data().path("paymentId").asLong());assertEquals(1,replay.data().path("status").asInt());
        assertEquals(0,put("/api/payments/"+p+"/confirm",mgr,null).code());assertEquals(3,intOf("select sum(remain_qty) from ticket_lot"));
    }
}
