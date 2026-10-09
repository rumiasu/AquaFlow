package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import static org.junit.jupiter.api.Assertions.*;

/** 确认减负专项共用真实业务准备；不模拟支付/桶账/库存状态迁移。 */
abstract class CustomerConfirmationFlowFixture extends AbstractIntegrationTest {
    long station,manager,customer,product,address;
    String cus,mgr;
    void seed() {
        station=createStation("确认减负站");manager=createStaff("站长","STATION_MANAGER",station,1);
        customer=createCustomer("客户","confirmation-flow");product=createProduct("桶装水",1,"20.00","30.00",1,"8.00");
        createInventoryFull(station,product,20,1,"8.00");createInventoryRecord(station,product,20,"STOCK_IN",0);address=createAddress(customer,"一楼");
        cus=customerToken(customer);mgr=staffToken(manager,"STATION_MANAGER",station);
        Api purchase=post("/api/barrel-rights/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":2,\"paymentMethod\":2,\"idempotencyKey\":\"rights\"}");
        assertEquals(0,purchase.code(),purchase.toString());assertEquals(0,put("/api/payments/"+purchase.data().path("paymentId").asLong()+"/confirm",mgr,null).code());
    }
    long paidOrder() {
        Api created=post("/api/orders/create",cus,"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"idempotencyKey\":\"water\",\"items\":[{\"productId\":"+product+",\"quantity\":2}]}");
        assertEquals(0,created.code(),created.toString());long id=created.data().path("orderId").asLong();
        assertEquals(0,post("/api/payments",cus,"{\"orderId\":"+id+",\"paymentMethod\":1}").code());
        assertEquals(0,post("/api/delivery/orders/"+id+"/accept",mgr,"{}").code());return id;
    }
    String completion(long order) {
        long item=jdbc.queryForObject("select id from order_item where order_id=?",Long.class,order);
        return "{\"itemReturns\":[{\"orderItemId\":"+item+",\"expected\":0,\"actual\":0,\"reasons\":[]}]}";
    }
    void deliver(long id) { Api done=post("/api/delivery/orders/"+id+"/complete",mgr,completion(id));assertEquals(0,done.code(),done.toString()); }
    Api status(long id,int status) {return put("/api/barrels/records/"+id+"/status",mgr,"{\"status\":"+status+",\"refundChannel\":\"CASH\"}");}
    long request(String mode) {
        Api r=post("/api/barrels/return",cus,requestBody(mode));assertEquals(0,r.code(),r.toString());return r.data().path("recordId").asLong();
    }
    String requestBody(String mode) {return "{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"pickupMode\":\""+mode+"\",\"idempotencyKey\":\"return\"}";}
    String change(String mode,Long order,int version,String key) {return "{\"pickupMode\":\""+mode+"\",\"companionOrderId\":"+order+",\"expectedVersion\":"+version+",\"idempotencyKey\":\""+key+"\",\"reason\":\"旧安排无法继续，改用明确选择的新安排\"}";}
}
