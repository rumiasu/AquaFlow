package com.example.aquaflow.integration;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** 资金修复的反例：拒绝后账本不变；迟到扫描必须重新核实当前订单与渠道凭据。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true", "aquaflow.barrel.maintenance-enabled=false", "app.payment.mock-wechat-pay=true"})
class FinanceLedgerRepairIntegrationTest extends AbstractIntegrationTest {
    @Autowired PaymentService payments;
    long station, manager, customer, address, product;
    String mgr, cus;

    void seed() {
        station=createStation("资金闭环站");manager=createStaff("站长","STATION_MANAGER",station,1);
        customer=createCustomer("客户","finance-repair");address=createAddress(customer,"一楼");
        product=createProduct("桶装水",1,"20.00","30.00",1,"8.00");
        createInventoryFull(station,product,20,1,"8.00");createCustomerStationConfig(customer,station,1);
        mgr=staffToken(manager,"STATION_MANAGER",station);cus=customerToken(customer);
    }
    long order(int status,int paid,int method) {
        long id=createOrderFull(customer,address,station,product,status,paid,method,"20.00","0.00","20.00",false,1);
        jdbc.update("update orders set settle_station_id=? where id=?",station,id);return id;
    }
    long revokeDraft(int qty,String key) {
        Api r=post("/api/manager/adjustments",mgr,"{\"customerId\":"+customer+",\"adjustType\":\"BARREL_REVOKE\",\"productId\":"+product+",\"qty\":"+qty+",\"reason\":\"核实更正\",\"clientToken\":\""+key+"\"}");
        assertEquals(0,r.code(),r.toString());return r.data().path("id").asLong();
    }

    @Test void orderPaymentCannotMintPurchasedTicketsEvenWhenOldRecordHasInjectedMetadata() {
        seed();long id=order(1,1,2);
        Api rejected=post("/api/payments",cus,"{\"orderId\":"+id+",\"paymentMethod\":2,\"ticketProductId\":"+product+",\"ticketQty\":99}");
        assertEquals(1,rejected.code(),rejected.toString());assertEquals(0,intOf("select count(*) from payment_record"));
        assertThrows(BusinessException.class,()->payments.createPayment(id,customer,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,0,2,product,99,null));
        long p=createPaymentRecord(id,customer,station,"20.00",2,1);
        jdbc.update("update payment_record set ticket_water_type_id=?,ticket_qty=99,water_amount=20 where id=?",product,p);
        Api confirm=put("/api/payments/"+p+"/confirm",mgr,null);assertEquals(0,confirm.code(),confirm.toString());
        assertEquals(2,intOf("select payment_status from orders where id=?",id));
        assertEquals(0,intOf("select count(*) from ticket_lot"));assertEquals(0,intOf("select count(*) from ticket_record"));
    }

    @Test void prepaidUnusedCapacityCannotBeManuallyRevokedIntoNegativePhysicalBuckets() {
        seed();Api buy=post("/api/barrel-rights/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"paymentMethod\":2,\"idempotencyKey\":\"prepaid\"}");
        assertEquals(0,buy.code(),buy.toString());assertEquals(0,put("/api/payments/"+buy.data().path("paymentId").asLong()+"/confirm",mgr,null).code());
        assertEquals(-1,intOf("select over_qty from customer_barrel_over where customer_id=?",customer));
        long draft=revokeDraft(1,"revoke-unused");Api r=post("/api/manager/adjustments/"+draft+"/execute",mgr,"{}");
        assertEquals(1,r.code(),r.toString());assertTrue(r.message().contains("实物桶数"),r.toString());
        assertEquals(1,intOf("select sum(remain_qty) from customer_barrel_lot where customer_id=?",customer));
        assertEquals(-1,intOf("select over_qty from customer_barrel_over where customer_id=?",customer));
        assertEquals(0,intOf("select count(*) from barrel_record where adjustment_id=?",draft));
    }

    @Test void manualRevokeCannotConsumeRightsOccupiedByLegacyOrder() {
        seed();createBarrelLot("LEGACY-RIGHT",customer,station,product,"30.00",2,2);
        createBarrelAsset(customer,station,product,2,"60.00");createBarrelOver(customer,station,product,0);
        long id=order(1,2,1);createOrderItemFull(id,product,"桶装水",1,0,"20.00","0.00");
        long draft=revokeDraft(2,"revoke-busy");Api r=post("/api/manager/adjustments/"+draft+"/execute",mgr,"{}");
        assertEquals(1,r.code(),r.toString());assertTrue(r.message().contains("占用"),r.toString());
        assertEquals(2,intOf("select sum(remain_qty) from customer_barrel_lot where customer_id=?",customer));
        assertEquals(0,intOf("select count(*) from barrel_record where adjustment_id=?",draft));
    }

    @Test void staleTimeoutCandidateCannotCancelPaidCashDeliveredOrRecentOrders() {
        seed();long paid=order(1,2,1),cash=order(1,1,2),delivered=order(3,0,1),recent=order(1,0,1);
        jdbc.update("update orders set create_time=date_sub(now(),interval 30 minute) where id in (?,?,?)",paid,cash,delivered);
        for(long id:new long[]{paid,cash,delivered,recent}) assertFalse(payments.cancelTimedOutWechatOrder(id,15));
        assertEquals(0,intOf("select count(*) from orders where status=5"));
        assertEquals(0,intOf("select count(*) from payment_record where amount<0"));
        assertEquals(20,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));
    }

    @Test void timedOutOrderWithPendingChannelRequestWaitsForActualPaymentResolution() {
        seed();long id=order(1,0,1);jdbc.update("update orders set create_time=date_sub(now(),interval 30 minute) where id=?",id);
        long p=createPaymentRecord(id,customer,station,"20.00",1,1);
        assertFalse(payments.cancelTimedOutWechatOrder(id,15));
        assertEquals(1,intOf("select status from orders where id=?",id));assertEquals(1,intOf("select status from payment_record where id=?",p));
    }

    @Test void expiredUnstartedWechatOrderReleasesReservationOnceWithoutInventingStockOrRefund() {
        seed();long id=order(1,0,1);long item=createReservedItem(id,product,"桶装水",1,"20.00","0.00");
        jdbc.update("update orders set create_time=date_sub(now(),interval 30 minute) where id=?",id);
        jdbc.update("update inventory_reservation set need_time=(select create_time from orders where id=?) where order_id=?",id,id);
        assertTrue(payments.cancelTimedOutWechatOrder(id,15));assertFalse(payments.cancelTimedOutWechatOrder(id,15));
        assertEquals(5,intOf("select status from orders where id=?",id));
        assertEquals(3,intOf("select status from inventory_reservation where order_item_id=?",item));
        assertEquals(0,intOf("select deducted_qty from order_item where id=?",item));
        assertEquals(20,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));
        assertEquals(0,intOf("select count(*) from payment_record where amount<0"));
    }

    @Test void multipleRefusalDebtsPermitCollectingExistingCashDebtButStillBlockNewPurchase() {
        seed();long one=order(4,1,2),two=order(4,1,2),next=order(1,0,1);
        for(long id:new long[]{one,two}) jdbc.update("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,note,create_time) values(?,?,?,?,?,1,?,'核实拒付',now())",id,id,customer,station,station,manager);
        jdbc.update("update customer_station_config set offline_payment_enabled=0 where customer_id=? and station_id=?",customer,station);
        Api p=post("/api/payments",mgr,"{\"orderId\":"+one+",\"paymentMethod\":2}");assertEquals(0,p.code(),p.toString());
        Api confirm=put("/api/payments/"+p.data().path("id").asLong()+"/confirm",mgr,null);assertEquals(0,confirm.code(),confirm.toString());
        assertEquals(2,intOf("select payment_status from orders where id=?",one));assertEquals(1,intOf("select payment_status from orders where id=?",two));
        Api blocked=post("/api/payments",cus,"{\"orderId\":"+next+",\"paymentMethod\":1}");assertEquals(1,blocked.code(),blocked.toString());
        assertEquals(0,intOf("select count(*) from payment_record where order_id=?",next));
        assertEquals(0,intOf("select offline_payment_enabled from customer_station_config where customer_id=? and station_id=?",customer,station));
    }
}
