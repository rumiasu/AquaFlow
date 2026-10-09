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

/** 站点及商品停售的新购票边界与旧款清结；隔离 MySQL、真实 HTTP，不接生产收款渠道。 */
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

    @Test void pendingIndependentDepositCanReplayAndCollectOnceAfterStationClosure() {
        seed();String request=body("pending-deposit",2).replace("\"quantity\":3","\"quantity\":1");
        Api original=post("/api/barrel-rights/purchase",cus,request);assertEquals(0,original.code(),original.toString());
        long payment=original.data().path("paymentId").asLong();
        assertEquals(1,original.data().path("status").asInt());
        assertEquals(0,ledger.rightQty(customer,station,product));
        assertEquals(0,intOf("select count(*) from customer_deposit_account"));
        close();
        Api replay=post("/api/barrel-rights/purchase",cus,request);assertEquals(0,replay.code(),replay.toString());
        assertEquals(payment,replay.data().path("paymentId").asLong());
        assertEquals(1,replay.data().path("status").asInt());
        Api collected=put("/api/payments/"+payment+"/confirm",mgr,null);assertEquals(0,collected.code(),collected.toString());
        assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(0,ledger.occupiedQty(customer,station,product));
        assertEquals(new BigDecimal("30.00"),decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station));
        assertEquals("PAID",jdbc.queryForObject("select status from barrel_right_purchase where payment_id=?",String.class,payment));
        assertEquals(1,put("/api/payments/"+payment+"/confirm",mgr,null).code());
        Api paidReplay=post("/api/barrel-rights/purchase",cus,request);assertEquals(0,paidReplay.code(),paidReplay.toString());
        assertEquals(payment,paidReplay.data().path("paymentId").asLong());assertEquals(2,paidReplay.data().path("status").asInt());
        assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(1,intOf("select count(*) from customer_barrel_lot"));
        assertEquals(1,intOf("select count(*) from deposit_record"));
        assertEquals(1,post("/api/barrel-rights/purchase",cus,request.replace("pending-deposit","new-after-close")).code());
        assertEquals(1,intOf("select count(*) from payment_record"));
        assertEquals(1,intOf("select count(*) from barrel_right_purchase"));
    }

    private String unifiedBody(String key) {
        return body(key,2).replace("\"quantity\":3","\"quantity\":10,\"unifiedQty\":10");
    }
    private void unifiedSeed() {
        seed();buyRight();
        jdbc.update("update inventory set ticket_enabled=0 where station_id=? and product_id=?",station,product);
        assertEquals(0,post("/api/ticket-discounts",mgr,"{\"qty\":10,\"discountPerMille\":950}").code());
    }
    @ParameterizedTest
    @ValueSource(strings={"station-shelf-off","product-off","product-stopped","not-selected","foreign-product"})
    void newUnifiedPurchaseRejectsUnsellableProductWithoutMoneyOrAssetWrites(String reason) {
        unifiedSeed();
        switch (reason) {
            case "station-shelf-off" -> jdbc.update("update inventory set enabled=0 where station_id=? and product_id=?",station,product);
            case "product-off" -> jdbc.update("update product set status=0 where id=?",product);
            case "product-stopped" -> jdbc.update("update product set status=2 where id=?",product);
            case "not-selected" -> jdbc.update("delete from inventory where station_id=? and product_id=?",station,product);
            case "foreign-product" -> jdbc.update("update product set owner_station_id=? where id=?",createStation("其它合成站"),product);
            default -> fail("未知测试场景");
        }
        int payments=intOf("select count(*) from payment_record");
        int fences=intOf("select count(*) from ticket_purchase_fence");
        Api rejected=post("/api/tickets/purchase",cus,unifiedBody("catalog-new"));
        assertEquals(1,rejected.code(),"不可售商品不能因统一折扣而建款: "+rejected);
        assertEquals(payments,intOf("select count(*) from payment_record"));
        assertEquals(fences,intOf("select count(*) from ticket_purchase_fence"),"保留既有押金凭据，不留失败购票的新锁行");
        assertEquals(0,intOf("select count(*) from ticket_purchase_fence where customer_id=? and idempotency_key='catalog-new'",customer));
        assertEquals(0,intOf("select count(*) from ticket_account"));
        assertEquals(0,intOf("select count(*) from ticket_lot"));
        assertEquals(0,intOf("select count(*) from ticket_record"));
    }
    @Test void zeroPhysicalStockStillAllowsTicketPrepayment() {
        unifiedSeed();jdbc.update("update inventory set quantity=0 where station_id=? and product_id=?",station,product);
        Api result=post("/api/tickets/purchase",cus,unifiedBody("zero-stock"));
        assertEquals(0,result.code(),"上架不等于有现货，预购票不承诺即时配送: "+result);
        assertEquals(1,result.data().path("status").asInt());
        assertEquals(new BigDecimal("76.00"),decimalOf("select amount from payment_record where id=?",result.data().path("paymentId").asLong()));
        assertEquals(0,intOf("select count(*) from ticket_lot"));
    }
    @Test void pendingUnifiedPurchaseCanReplayAndCollectOnceAfterProductIsWithdrawn() {
        unifiedSeed();String request=unifiedBody("original-pending");
        Api original=post("/api/tickets/purchase",cus,request);assertEquals(0,original.code(),original.toString());
        long payment=original.data().path("paymentId").asLong();
        jdbc.update("update inventory set enabled=0,ticket_price=99 where station_id=? and product_id=?",station,product);
        jdbc.update("update product set status=2 where id=?",product);
        Api replay=post("/api/tickets/purchase",cus,request);assertEquals(0,replay.code(),replay.toString());
        assertEquals(payment,replay.data().path("paymentId").asLong());
        assertEquals(new BigDecimal("76.00"),decimalOf("select amount from payment_record where id=?",payment));
        assertEquals(0,put("/api/payments/"+payment+"/confirm",mgr,null).code());
        assertEquals(1,put("/api/payments/"+payment+"/confirm",mgr,null).code());
        assertEquals(10,intOf("select sum(remain_qty) from ticket_lot"));
        assertEquals(1,intOf("select count(*) from ticket_lot"));
        assertEquals(1,intOf("select count(*) from payment_record where ticket_qty is not null"));
        assertEquals(0,post("/api/tickets/purchase",cus,request).code());
        assertEquals(10,intOf("select sum(remain_qty) from ticket_lot"));
    }
}
