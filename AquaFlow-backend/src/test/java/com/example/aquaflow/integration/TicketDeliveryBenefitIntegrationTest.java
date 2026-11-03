package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.service.TicketDeliveryBenefitService;
import com.example.aquaflow.service.TicketLotService;
import com.example.aquaflow.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

class TicketDeliveryBenefitIntegrationTest extends AbstractIntegrationTest {
    @Autowired TicketDeliveryBenefitService benefits;
    @Autowired TicketLotService lots;
    long station,customer,product,manager,packageId,address;
    void seed() {
        station=createStation("配送费档位站");customer=createCustomer("档位购票人","delivery-benefit-customer");
        manager=createStaff("档位站长","STATION_MANAGER",station,1);product=createProduct("档位水",1,"20.00","30.00",1,"18.00");
        createInventoryFull(station,product,100,1,"18.00");address=createAddress(customer,"优惠送水地址");
        packageId=insert("insert into ticket_package(station_id,product_id,qty,price,unit_price,title,status,sort) values(?,?,10,180,18,'十张档',1,0)",station,product);
    }
    String managerToken(){return staffToken(manager,"STATION_MANAGER",station);}
    long buy(String key,int qty) {
        Api p=post("/api/tickets/purchase",customerToken(customer),"{\"stationId\":"+station+",\"productId\":"+product+",\"packageId\":"+packageId+",\"quantity\":"+qty+",\"paymentMethod\":2,\"idempotencyKey\":\""+key+"\"}");
        assertTrue(p.isSuccess(),p.message());return p.data().path("paymentId").asLong();
    }
    void enabled(boolean value){benefits.configure(station,"CUSTOM",packageId,product,value);}
    void paid(long payment){Api p=put("/api/payments/"+payment+"/confirm",managerToken(),null);assertTrue(p.isSuccess(),p.message());}
    long order() {
        long id=createOrderFull(customer,address,station,product,1,1,3,"18.00","0.00","23.00",false,1);
        createOrderItemFull(id,product,"档位水",1,0,"18.00","0.00");return id;
    }
    @Test
    void defaultsOffAndCannotConfigureAnotherStationOrAnInventedTier() {
        seed();assertFalse(benefits.enabled(station,product,"CUSTOM",packageId));
        assertTrue(get("/api/manager/ticket-delivery-offers",managerToken()).isSuccess());
        assertFalse(get("/api/manager/ticket-delivery-offers",customerToken(customer)).isSuccess());
        long other=createStation("另一站");
        assertThrows(BusinessException.class,()->benefits.configure(other,"CUSTOM",packageId,product,true));
        assertThrows(BusinessException.class,()->benefits.configure(station,"CUSTOM",packageId+100,product,true));
        long payment=buy("benefit-disabled",10);paid(payment);
        assertEquals(0,intOf("select count(*) from ticket_delivery_benefit"));
    }
    @Test
    void selectedTierSnapshotCreatesOneBenefitOnlyAfterActualPayment() {
        seed();enabled(true);long payment=buy("benefit-twenty",20);
        assertEquals("PENDING",jdbc.queryForObject("select state from ticket_delivery_benefit where payment_id=?",String.class,payment));
        enabled(false);paid(payment);
        assertEquals("AVAILABLE",jdbc.queryForObject("select state from ticket_delivery_benefit where payment_id=?",String.class,payment));
        assertEquals(1,intOf("select count(*) from ticket_delivery_benefit where payment_id=?",payment));
        assertEquals(payment,buy("benefit-twenty",20));
        assertEquals(1,intOf("select count(*) from ticket_delivery_benefit"));
    }
    @Test
    void sourceBoundBenefitReservesOnceAndCancellingUnfulfilledOrderReleasesIt() {
        seed();enabled(true);long payment=buy("benefit-reserve",10);paid(payment);
        long first=order();lots.reserveForOrder(customer,station,product,first,1);
        assertEquals(payment,benefits.reserve(customer,station,first));
        assertThrows(BusinessException.class,()->benefits.revokeOnUnusedTicketExit(payment));
        long second=order();lots.reserveForOrder(customer,station,product,second,1);
        assertNull(benefits.reserve(customer,station,second));
        benefits.releaseBeforeDelivery(first);lots.releaseOrderReservation(customer,station,first);
        assertEquals(payment,benefits.reserve(customer,station,second));
        lots.consumeOrderReservation(customer,station,second);jdbc.update("update orders set status=3 where id=?",second);benefits.delivered(second);
        assertThrows(BusinessException.class,()->benefits.releaseBeforeDelivery(second));
        benefits.revokeOnUnusedTicketExit(payment);
        assertEquals("USED",jdbc.queryForObject("select state from ticket_delivery_benefit where payment_id=?",String.class,payment));
    }

    @Test void unifiedTierIsExplicitAndGrantsOneProductBoundBenefitForMultipleCopies() {
        seed();jdbc.update("update inventory set ticket_enabled=0 where station_id=? and product_id=?",station,product);jdbc.update("update product set ticket_enabled=0 where id=?",product);
        assertTrue(post("/api/ticket-discounts",managerToken(),"{\"qty\":10,\"discountPerMille\":900}").isSuccess());
        benefits.configure(station,"UNIFIED",10L,0L,true);
        Api bought=post("/api/tickets/purchase",customerToken(customer),"{\"stationId\":"+station+",\"productId\":"+product+",\"unifiedQty\":10,\"quantity\":20,\"paymentMethod\":2,\"idempotencyKey\":\"unified-delivery-benefit\"}");assertTrue(bought.isSuccess(),bought.message());long payment=bought.data().path("paymentId").asLong();paid(payment);
        assertEquals(1,intOf("select count(*) from ticket_delivery_benefit where payment_id=? and customer_id=? and station_id=? and product_id=? and tier_kind='UNIFIED' and tier_key=10 and state='AVAILABLE'",payment,customer,station,product));
    }
}
