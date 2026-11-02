package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** B-03 old HTTP commands cannot consent to a silently changed financial offer. */
@TestPropertySource(properties="aquaflow.barrel.independent-rights-enabled=true")
class DispatchOfferLegacyReproIntegrationTest extends AbstractIntegrationTest {
    long a,b,ma,mb,db,product,customer,address;
    private long offer(boolean pool) {
        a=createStation("合成报价甲");b=createStation("合成报价乙");
        ma=createStaff("合成甲站长","STATION_MANAGER",a,1);mb=createStaff("合成乙站长","STATION_MANAGER",b,1);db=createStaff("合成乙配送","DELIVERY",b,1);
        product=createProduct("合成报价瓶水",2,"40.00","0.00",0,"0.00");createInventoryFull(a,product,20,0,"0.00");createInventoryFull(b,product,20,0,"0.00");
        customer=createCustomer("合成报价客户","offer-repro");address=createAddress(customer,"合成报价门牌");
        long id=createOrderFull(customer,address,a,product,1,1,2,"40.00","0.00","40.00",false,0);
        assertTrue(post("/api/delivery/orders/transfer/"+id+"/outsource",staffToken(ma,"STATION_MANAGER",a),pool?"{}":"{\"targetStationId\":"+b+"}").isSuccess());
        assertTrue(put("/api/manager/dispatch-agreements/"+id,staffToken(ma,"STATION_MANAGER",a),"{\"serviceAmount\":5,\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0,\"note\":\"已改变报价\"}").isSuccess());
        return id;
    }
    private void rejectedWithoutEffects(long id,Api result,boolean pool) {
        assertEquals(1,result.code(),result.toString());assertTrue(result.message().contains("报价"),result.toString());
        assertEquals(1,intOf("select status from orders where id=?",id));
        assertEquals(1,intOf("select count(*) from orders where id=? and delivery_staff_id is null",id));
        assertEquals(pool?1:0,intOf("select count(*) from orders where id=? and delivery_station_id is null",id));
        assertEquals("OFFERED",jdbc.queryForObject("select status from dispatch_agreement where order_id=?",String.class,id));
        assertEquals(0,intOf("select count(*) from ticket_record where order_id=?",id));
        assertEquals(0,intOf("select count(*) from barrel_record where related_order_id=?",id));
    }
    @Test @DisplayName("旧分配请求不能接受未展示的新报价")
    void legacyAssignRejectsUnseenQuote() {
        long id=offer(false);rejectedWithoutEffects(id,post("/api/delivery/orders/assign/"+id,staffToken(mb,"STATION_MANAGER",b),"{\"deliveryStaffId\":"+db+"}"),false);
    }
    @Test @DisplayName("旧直接接单请求不能接受未展示的新报价")
    void legacyAcceptRejectsUnseenQuote() {
        long id=offer(false);rejectedWithoutEffects(id,post("/api/delivery/orders/"+id+"/accept",staffToken(mb,"STATION_MANAGER",b),"{}"),false);
    }
    @Test @DisplayName("旧抢单请求不能接受未展示的新报价")
    void legacyClaimRejectsUnseenQuote() {
        long id=offer(true);rejectedWithoutEffects(id,post("/api/delivery/orders/"+id+"/claim-pool",staffToken(mb,"STATION_MANAGER",b),"{\"deliveryStaffId\":"+db+"}"),true);
    }
}
