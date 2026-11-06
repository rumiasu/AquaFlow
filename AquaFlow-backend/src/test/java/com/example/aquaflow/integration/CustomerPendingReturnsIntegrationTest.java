package com.example.aquaflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.test-mode=true"})
class CustomerPendingReturnsIntegrationTest extends CustomerConfirmationFlowFixture {
    @Test void customerPendingConfirmationsAreLiveScopedAndVersioned() {
        seed(); deliver(paidOrder()); long id=request("PICKUP");
        String path="/api/barrels/return/pending-confirmations";
        assertEquals(0,get(path,cus).data().size(),"未批准不应提示");
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":5}").code());
        Api pending=get(path,cus); assertEquals(0,pending.code()); assertEquals(1,pending.data().size());
        assertEquals(id,pending.data().get(0).path("recordId").asLong());
        assertEquals(station,pending.data().get(0).path("stationId").asLong());
        long other=createCustomer("另一客户","confirmation-other");
        assertEquals(0,get(path,customerToken(other)).data().size(),"不能泄露其他客户申请");
        assertNotEquals(0,get(path,mgr).code(),"员工不可读取客户待办");
        assertEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{\"expectedVersion\":1}").code());
        assertEquals(0,get(path,cus).data().size(),"确认后立即消失");
        assertEquals(0,put("/api/barrels/records/"+id+"/manager-arrangement",mgr,change("STORE",null,1,"station-new")).code());
        assertEquals(0,get(path,cus).data().size(),"重新批准前不提示客户确认");
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":0,\"expectedVersion\":2}").code());
        assertEquals(1,get(path,cus).data().size(),"站长的新免费安排仍需客户确认");
        assertNotEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{\"expectedVersion\":1}").code());
        assertEquals(1,get(path,cus).data().size(),"旧版本确认不能消除新待办");
        assertEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{\"expectedVersion\":2}").code());
        assertEquals(0,get(path,cus).data().size());
    }

}
