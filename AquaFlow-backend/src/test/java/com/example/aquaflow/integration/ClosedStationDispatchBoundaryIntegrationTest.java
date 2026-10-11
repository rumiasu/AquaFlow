package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 硬停业挡新增接入；已承担的履约不被停业开关抹除。 */
class ClosedStationDispatchBoundaryIntegrationTest extends AbstractIntegrationTest {
    long a,b,ma,mb,driver,product,customer,address;
    String at,bt;
    void seed() {
        a=createStation("派单甲站");b=createStation("接单乙站");
        ma=createStaff("甲长","STATION_MANAGER",a,1);mb=createStaff("乙长","STATION_MANAGER",b,1);
        driver=createStaff("乙配送","DELIVERY",b,1);
        product=createProduct("普通水",1,"20.00","30.00",0,"0.00");
        createInventoryFull(a,product,50,1,"10.00");createInventoryFull(b,product,50,1,"10.00");
        customer=createCustomer("客户","closed-station-dispatch");address=createAddress(customer,"一楼");
        at=staffToken(ma,"STATION_MANAGER",a);bt=staffToken(mb,"STATION_MANAGER",b);
    }
    long order() { return createOrderFull(customer,address,a,product,1,1,2,"40.00","0.00","40.00",false,0); }
    @Test void hardClosedStationCannotClaimNewPoolOrder() {
        seed();long id=order();
        assertEquals(0,post("/api/delivery/orders/transfer/"+id+"/outsource",at,"{}").code());
        jdbc.update("update station set status=2 where id=?",b);
        Api r=post("/api/delivery/orders/"+id+"/claim-pool",bt,"{\"deliveryStaffId\":"+driver+"}");
        assertEquals(1,r.code(),"硬停业站不能新抢单："+r);
        assertNull(jdbc.queryForObject("select delivery_station_id from orders where id=?",Long.class,id));
        assertEquals(1,intOf("select status from orders where id=?",id));
    }
    @Test void hardClosedTargetCannotReceiveNewDirectDispatchThroughEitherEntry() {
        seed();jdbc.update("update station set status=2 where id=?",b);
        for(String entry:new String[]{"/api/delivery/orders/","/api/delivery/orders/transfer/"}) {
            long id=order();String suffix=entry.contains("transfer")?"/outsource":"/dispatch";
            Api r=post(entry+id+suffix,at,"{\"targetStationId\":"+b+"}");
            assertEquals(1,r.code(),"硬停业目标站不能新接定向外派："+r);
            assertEquals(a,longOf("select delivery_station_id from orders where id=?",id));
            assertEquals(0,intOf("select count(*) from order_transfer where order_id=?",id));
        }
    }
    @Test void hardClosureAfterDirectOfferStopsFirstAcceptanceWithoutChangingOrder() {
        seed();long id=order();
        assertEquals(0,post("/api/delivery/orders/"+id+"/dispatch",at,"{\"targetStationId\":"+b+"}").code());
        jdbc.update("update station set status=2 where id=?",b);
        assertEquals(1,post("/api/delivery/orders/"+id+"/accept",bt,null).code());
        assertEquals(1,post("/api/delivery/orders/assign/"+id,bt,"{\"deliveryStaffId\":"+driver+"}").code());
        assertEquals(1,intOf("select status from orders where id=?",id));
        assertNull(jdbc.queryForObject("select delivery_staff_id from orders where id=?",Long.class,id));
    }
    @Test void alreadyAssignedTaskAndOwnOldOrderCanStillBeAcceptedAfterHardClosure() {
        seed();long incoming=order(),own=order();
        assertEquals(0,post("/api/delivery/orders/"+incoming+"/dispatch",at,"{\"targetStationId\":"+b+"}").code());
        assertEquals(0,post("/api/delivery/orders/assign/"+incoming,bt,"{\"deliveryStaffId\":"+driver+"}").code());
        jdbc.update("update station set status=2 where id in (?,?)",a,b);
        String dt=staffToken(driver,"DELIVERY",b);
        assertEquals(0,post("/api/delivery/orders/"+incoming+"/accept",dt,null).code());
        assertEquals(0,post("/api/delivery/orders/"+own+"/accept",at,null).code());
        assertEquals(2,intOf("select status from orders where id=?",incoming));
        assertEquals(2,intOf("select status from orders where id=?",own));
    }
    @Test void restingAndPausedDeliverySoftStatesAllowNewDispatchAndClaim() {
        seed();for(int soft:new int[]{2,4}) {
            jdbc.update("update station set operating_status=? where id=?",soft,b);
            long pool=order(),direct=order();
            assertEquals(0,post("/api/delivery/orders/transfer/"+pool+"/outsource",at,"{}").code());
            assertEquals(0,post("/api/delivery/orders/"+pool+"/claim-pool",bt,"{\"deliveryStaffId\":"+driver+"}").code());
            assertEquals(0,post("/api/delivery/orders/"+direct+"/dispatch",at,"{\"targetStationId\":"+b+"}").code());
        }
    }
}
