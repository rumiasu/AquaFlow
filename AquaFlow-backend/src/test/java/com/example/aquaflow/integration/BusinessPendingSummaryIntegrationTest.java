package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** 含真实 HTTP/MySQL 与写动作；只能在明确获准的独立可清空测试库运行。 */
@TestPropertySource(properties = {"aquaflow.barrel.independent-rights-enabled=true", "aquaflow.barrel.maintenance-enabled=false", "app.payment.mock-wechat-pay=true"})
class BusinessPendingSummaryIntegrationTest extends AbstractIntegrationTest {
    long a,b,c,customer,product,address;
    String at,bt,ct,cus;
    private void seed() {
        a=createStation("责任甲站"); b=createStation("责任乙站"); c=createStation("无关丙站");
        at=staffToken(createStaff("甲站长","STATION_MANAGER",a,1),"STATION_MANAGER",a);
        bt=staffToken(createStaff("乙站长","STATION_MANAGER",b,1),"STATION_MANAGER",b);
        ct=staffToken(createStaff("丙站长","STATION_MANAGER",c,1),"STATION_MANAGER",c);
        customer=createCustomer("甲站客户","business-pending-customer"); cus=customerToken(customer);
        product=createProduct("同型桶水",1,"20.00","30.00",1,"8.00"); address=createAddress(customer,"甲站地址");
        createInventory(a,product,5); createInventory(b,product,0);
    }
    private Api waiting(String token) {
        Api result=get("/api/manager/business-waiting",token); assertEquals(0,result.code(),result.toString()); return result;
    }
    private JsonNode item(String token,String key) {
        Api result=get("/api/manager/pending-summary",token); assertEquals(0,result.code(),result.toString());
        for(JsonNode row:result.data().path("items")) if(key.equals(row.path("key").asText()))return row;
        fail("未找到待办 "+key); return null;
    }
    private long crossOrder(int status) {
        return createOrderCrossStation(customer,address,a,b,product,status,2,2,"20.00","0.00","20.00");
    }
    private long agreement(boolean disputed) {
        long order=crossOrder(3);
        jdbc.update("insert into dispatch_agreement(order_id,source_station_id,target_station_id,service_amount,net_barrels,actual_net_barrels,actual_barrel_items,barrel_mode,barrel_disputed,barrel_amount,barrel_items,status,accepted_time,create_time) values(?,?,?,20,1,1,'商品：同型空桶1个','RETURN_EMPTY',?,0,'同型空桶1个','ACCEPTED',now(),now())",order,a,b,disputed?1:0);
        return order;
    }
    private void noCustomerProfile(JsonNode row) {
        for(String key:new String[]{"customerName","customerPhone","receiverName","receiverPhone","customerId","serviceAmount"})assertFalse(row.has(key),key+"不得出现在跨站责任清单");
    }

    @Test void receivedReturnIsDiscoverableAndActualRefundRemovesOnlyItsResponsibility() {
        seed();
        Api purchase=post("/api/barrel-rights/purchase",cus,"{\"stationId\":"+a+",\"productId\":"+product+",\"quantity\":1,\"paymentMethod\":2,\"idempotencyKey\":\"pending-right\"}");
        assertEquals(0,purchase.code(),purchase.toString());
        assertEquals(0,put("/api/payments/"+purchase.data().path("paymentId").asLong()+"/confirm",at,null).code());
        Api request=post("/api/barrels/return",cus,"{\"stationId\":"+a+",\"productId\":"+product+",\"quantity\":1,\"pickupMode\":\"STORE\",\"idempotencyKey\":\"pending-return\"}");
        assertEquals(0,request.code(),request.toString()); long id=request.data().path("recordId").asLong();
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",at,"{\"pickupFee\":0}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/customer-confirm",cus,"{}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/status",at,"{\"status\":2}").code());
        // 未超提醒时间也是真实资金责任；改为逾期仅改变提示，不改变办理口径。
        assertEquals(1,item(at,"returnRefund").path("count").asInt());
        jdbc.update("update barrel_return_detail set refund_due_time=date_sub(now(),interval 1 hour) where record_id=?",id);
        JsonNode row=waiting(at).data().path("returns").get(0);assertEquals(id,row.path("recordId").asLong());
        assertEquals(a,row.path("responsibleStationId").asLong());assertEquals("refund",row.path("nextAction").asText());assertEquals(1,row.path("overdue").asInt());
        assertEquals(0,item(bt,"returnRefund").path("count").asInt());assertEquals(0,waiting(bt).data().path("returns").size());
        String path="/api/manager/pending-summary/return-record/"+id;
        assertEquals(id,get(path,at).data().path("id").asLong());assertEquals(1,get(path,bt).code());assertEquals(1,get(path,cus).code());assertEquals(401,get(path,null).status());
        assertEquals(1,put("/api/barrels/records/"+id+"/status",bt,"{\"status\":3,\"refundChannel\":\"CASH\"}").code());
        assertEquals(0,put("/api/barrels/records/"+id+"/status",at,"{\"status\":3,\"refundChannel\":\"CASH\"}").code());
        assertEquals(0,item(at,"returnRefund").path("count").asInt());assertEquals(0,waiting(at).data().path("returns").size());
        assertEquals("REFUNDED",get(path,at).data().path("returnDetail").path("status").asText());
    }

    @Test void stockBelongsToDeliveryStationAndInboundEliminatesShortage() {
        seed(); long order=crossOrder(1); long detail=createOrderItemFull(order,product,"同型桶水",2,0,"20.00","0.00");
        jdbc.update("insert into inventory_reservation(order_id,order_item_id,product_id,station_id,need_qty,need_time,reserved_qty,status) values(?,?,?,?,2,now(),0,1)",order,detail,product,b);
        assertEquals(0,item(at,"waitingStock").path("count").asInt());assertEquals(1,item(bt,"waitingStock").path("count").asInt());
        JsonNode row=waiting(bt).data().path("stock").get(0);assertEquals(order,row.path("orderId").asLong());assertEquals(String.valueOf(order),row.path("orderNo").asText());assertEquals(b,row.path("responsibleStationId").asLong());noCustomerProfile(row);
        // 伪造查询站别不改变实际登录态。
        Api forged=get("/api/manager/business-waiting?stationId="+b,at);assertEquals(0,forged.code());assertEquals(0,forged.data().path("stock").size());
        assertEquals(0,post("/api/inventory/inbound?stationId="+b,bt,"{\"items\":[{\"productId\":"+product+",\"quantity\":2}]}").code());
        assertEquals(0,item(bt,"waitingStock").path("count").asInt());assertEquals(0,waiting(bt).data().path("stock").size());
        // 未付微信订单不能进入站长待办。
        jdbc.update("update orders set payment_method=1,payment_status=0 where id=?",order);
        jdbc.update("update inventory_reservation set reserved_qty=0 where order_id=?",order);
        assertEquals(0,item(bt,"waitingStock").path("count").asInt());
    }

    @Test void recoveryResponsibilityMovesFromSenderToReceiverAndHistoryDisappears() {
        seed();long order=crossOrder(5);
        jdbc.update("insert into inter_station_recovery(order_id,from_station_id,to_station_id,amount,status,note,create_time) values(?,?,?,20,'PENDING','原款冲销',now())",order,b,a);
        assertEquals(1,item(bt,"recoverySend").path("count").asInt());assertEquals(0,item(at,"recoveryReceive").path("count").asInt());
        assertEquals(0,waiting(at).data().path("recoveries").size());assertEquals(0,waiting(ct).data().path("recoveries").size());
        noCustomerProfile(waiting(bt).data().path("recoveries").get(0));
        assertEquals(1,post("/api/manager/inter-station-recoveries/"+order+"/sent",at,"{\"note\":\"越权\"}").code());
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+order+"/sent",bt,"{\"note\":\"实际返还20元\"}").code());
        assertEquals(0,item(bt,"recoverySend").path("count").asInt());assertEquals(1,item(at,"recoveryReceive").path("count").asInt());
        assertEquals("received",waiting(at).data().path("recoveries").get(0).path("nextAction").asText());
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+order+"/received",at,"{}").code());
        assertEquals(0,item(at,"recoveryReceive").path("count").asInt());assertEquals(0,waiting(at).data().path("recoveries").size());
    }

    @Test void barrelHandoverAndDisputeFollowEachPartysActualAction() {
        seed();long order=agreement(true);
        assertEquals(1,item(at,"barrelDispute").path("count").asInt());assertEquals(0,item(bt,"barrelDispute").path("count").asInt());
        assertEquals(0,waiting(ct).data().path("barrels").size());noCustomerProfile(waiting(at).data().path("barrels").get(0));
        assertEquals(1,put("/api/manager/station-barrel-balances/"+order+"/proposal",bt,"{\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0,\"note\":\"越权\"}").code());
        assertEquals(0,put("/api/manager/station-barrel-balances/"+order+"/proposal",at,"{\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0,\"note\":\"补同型空桶\"}").code());
        assertEquals(0,item(at,"barrelDispute").path("count").asInt());assertEquals(1,item(bt,"barrelDispute").path("count").asInt());
        assertEquals(0,post("/api/manager/station-barrel-balances/"+order+"/agree",bt,"{}").code());
        assertEquals(1,item(at,"barrelHandover").path("count").asInt());assertEquals(1,item(bt,"barrelHandover").path("count").asInt());
        assertEquals(0,post("/api/manager/station-barrel-balances/"+order+"/received",at,"{\"note\":\"甲已交同型空桶\"}").code());
        assertEquals(0,item(at,"barrelHandover").path("count").asInt());assertEquals(1,item(bt,"barrelHandover").path("count").asInt());
        assertEquals(1,post("/api/manager/station-barrel-balances/"+order+"/received",at,"{\"note\":\"重复确认\"}").code());
        assertEquals(0,post("/api/manager/station-barrel-balances/"+order+"/received",bt,"{\"note\":\"乙已收到同型空桶\"}").code());
        assertEquals(0,item(bt,"barrelHandover").path("count").asInt());assertEquals(0,waiting(bt).data().path("barrels").size());
    }

    @Test void cappedListStillReportsActualTotalAndDeliveredHistoryIsExcluded() {
        seed();
        for(int i=0;i<201;i++) {
            long order=crossOrder(5);
            jdbc.update("insert into inter_station_recovery(order_id,from_station_id,to_station_id,amount,status,create_time) values(?,?,?,1,'PENDING',now())",order,b,a);
        }
        assertEquals(201,item(bt,"recoverySend").path("count").asInt());
        Api list=waiting(bt);assertEquals(200,list.data().path("recoveries").size());assertEquals(201,list.data().path("counts").path("recoveriesTotal").asInt());
        long first=list.data().path("recoveries").get(0).path("orderId").asLong();
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+first+"/sent",bt,"{\"note\":\"实际返还\"}").code());
        assertEquals(200,item(bt,"recoverySend").path("count").asInt());assertEquals(200,waiting(bt).data().path("recoveries").size());
        long rider=createStaff("配送员","DELIVERY",b,1);
        assertEquals(1,get("/api/manager/pending-summary",staffToken(rider,"DELIVERY",b)).code());
        assertEquals(1,get("/api/manager/business-waiting",cus).code());
    }
}
