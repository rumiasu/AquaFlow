package com.example.aquaflow.integration;

import com.example.aquaflow.service.ConfirmedRefusalService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 精准历史增长反例：Java红绿交统一MySQL验证任务；本任务不运行Gradle/DB。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=true"})
class HistoricalBusinessEntryIntegrationTest extends CustomerConfirmationFlowFixture {
    @Autowired ConfirmedRefusalService refusals;
    long record(int type,int status,long atStation) {
        return insert("insert into barrel_record(customer_id,station_id,type,product_id,quantity,status,deposit_refund,create_time) values(?,?,?,?,1,?,0,now())",
                customer,atStation,type,product,status);
    }
    boolean contains(JsonNode rows,String key,long id) {
        for (JsonNode row:rows) if(row.path(key).asLong()==id)return true;
        return false;
    }
    Api returnPage(String query,String token) { return get("/api/manager/business-waiting/returns"+query,token); }
    Api refusalPage(String query,String token) { return get("/api/manager/refusal-cases/page"+query,token); }
    Map<String,Object> financialFacts() {
        Map<String,Object> out=new LinkedHashMap<>();
        for(String table:List.of("orders","payment_record","customer_deposit_account","deposit_record","customer_barrel_lot","customer_barrel_asset",
                "customer_barrel_over","ticket_lot","ticket_record","inventory","inventory_record"))out.put(table,jdbc.queryForList("select * from "+table));
        return out;
    }

    @Test void approvedUnclaimedOriginalIsStillReceivableAfter501UnrelatedRecords() {
        seed();long id=request("STORE");
        assertEquals(0,put("/api/barrels/records/"+id+"/approve",mgr,"{\"pickupFee\":0,\"expectedVersion\":1}").code());
        jdbc.update("update barrel_record set create_time=date_sub(now(),interval 1 day) where id=?",id);
        for(int i=0;i<501;i++)record(8,1,station);
        Api legacy=get("/api/barrels/all-records",mgr);assertEquals(0,legacy.code(),legacy.toString());assertEquals(500,legacy.data().size());assertFalse(contains(legacy.data(),"id",id));
        Map<String,Object> before=financialFacts();Api page=returnPage("",mgr);assertEquals(0,page.code(),page.toString());
        assertEquals(1,page.data().path("items").size());assertTrue(contains(page.data().path("items"),"id",id));
        JsonNode detail=page.data().path("items").get(0).path("returnDetail");assertEquals("APPROVED",detail.path("status").asText());
        assertFalse(detail.path("customerConfirmationRequired").asBoolean());assertEquals(0,detail.path("requiredBarrels").asInt());
        assertEquals(before,financialFacts(),"办理清单只读，不能伪造客户确认或动资产");
        assertEquals(0,status(id,2).code(),"免费原安排无需追加客户确认，未领容量无需交桶");
        assertEquals(0,status(id,3).code(),"仍从原退款写入口核对渠道并办理实际交付");
        assertEquals(0,returnPage("",mgr).data().path("items").size());
        Api exact=get("/api/manager/pending-summary/return-record/"+id,mgr);assertEquals(0,exact.code(),exact.toString());assertEquals(id,exact.data().path("id").asLong());
    }

    @Test void returnHistoryTraversesStableIdsDespiteSameTimeAndNewerInsertAndRejectsOtherStationLookup() {
        seed();long id=request("STORE");for(int i=0;i<121;i++)record(2,4,station);
        long foreign=createStation("他站"), foreignRecord=record(2,1,foreign);
        String other=staffToken(createStaff("他站长","STATION_MANAGER",foreign,1),"STATION_MANAGER",foreign);
        assertEquals(1,returnPage("?scope=BAD",mgr).code());assertEquals(1,returnPage("?beforeId=-1",mgr).code());
        assertEquals(1,returnPage("",cus).code());
        assertEquals(1,get("/api/manager/pending-summary/return-record/"+foreignRecord,mgr).code());
        assertEquals(station,returnPage("?stationId="+foreign,mgr).data().path("stationId").asLong(),"客户端站别不能覆盖登录站");
        assertFalse(contains(returnPage("?scope=ALL",other).data().path("items"),"id",id));
        Set<Long> ids=new HashSet<>();Long cursor=null;long newer=0;int pages=0;
        do {
            Api page=returnPage("?scope=ALL"+(cursor==null?"":"&beforeId="+cursor),mgr);assertEquals(0,page.code(),page.toString());
            JsonNode data=page.data();assertEquals(station,data.path("stationId").asLong());assertEquals(50,data.path("limit").asInt());assertTrue(data.path("items").size()<=50);
            for(JsonNode row:data.path("items"))assertTrue(ids.add(row.path("id").asLong()),"游标分页不能重复或遗漏旧申请");
            cursor=data.path("nextBeforeId").isNull()?null:data.path("nextBeforeId").asLong();
            if(pages++==0)newer=record(2,4,station);
            assertTrue(pages<10);
        } while(cursor!=null);
        assertEquals(122,ids.size());assertTrue(ids.contains(id));assertFalse(ids.contains(newer));
        assertEquals(1,returnPage("",mgr).data().path("items").size());
    }

    long refusal(long debt,boolean active) {
        long order=createOrderCrossStation(customer,address,station,debt,product,active?3:4,active?1:2,2,"20.00","0.00","20.00");
        jdbc.update("update orders set settle_station_id=? where id=?",debt,order);
        jdbc.update("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,note,create_time) values(?,1,?,?,?,1,?,'原判断',now())",order,customer,station,debt,manager);
        if(!active)jdbc.update("insert into customer_refusal_resolution(order_id,judgment_revoked,asset_freeze_released,version) values(?,1,1,2)",order);
        return order;
    }
    String action(String key,int version) { return "{\"idempotencyKey\":\""+key+"\",\"expectedVersion\":"+version+",\"reason\":\"核实原事实后处理本案\"}"; }

    @Test void activeRefusalAfter201ClosedCasesStillHasCorrectStationActionsAndOriginalReceiptReplay() {
        seed();long debt=createStation("债权站");String debtManager=staffToken(createStaff("债权站长","STATION_MANAGER",debt,1),"STATION_MANAGER",debt);
        long id=refusal(debt,true);jdbc.update("update customer_refusal_case set create_time=date_sub(now(),interval 1 day) where order_id=?",id);
        for(int i=0;i<201;i++)refusal(debt,false);
        assertFalse(contains(get("/api/manager/refusal-cases",mgr).data(),"order_id",id));assertTrue(refusals.frozen(customer,station));
        Map<String,Object> before=financialFacts();Api asset=refusalPage("",mgr), debtor=refusalPage("",debtManager);
        assertEquals(0,asset.code(),asset.toString());assertEquals(1,asset.data().path("items").size());
        assertTrue(asset.data().path("items").get(0).path("canRelease").asBoolean());assertFalse(asset.data().path("items").get(0).path("canRevoke").asBoolean());
        assertTrue(debtor.data().path("items").get(0).path("canRevoke").asBoolean());assertFalse(debtor.data().path("items").get(0).path("canRelease").asBoolean());
        Api revoke=post("/api/manager/refusal-cases/"+id+"/revoke",debtManager,action("original-revoke",0));assertEquals(0,revoke.code(),revoke.toString());
        assertTrue(refusals.frozen(customer,station));assertTrue(contains(refusalPage("",mgr).data().path("items"),"order_id",id));
        assertEquals(0,post("/api/manager/refusal-cases/"+id+"/release-freeze",mgr,action("release",1)).code());assertFalse(refusals.frozen(customer,station));
        for(int i=0;i<201;i++)refusal(debt,false);
        Api lookup=refusalPage("?orderId="+id,debtManager);assertEquals(0,lookup.code(),lookup.toString());assertEquals("LOOKUP",lookup.data().path("scope").asText());assertTrue(contains(lookup.data().path("items"),"order_id",id));
        Api replay=post("/api/manager/refusal-cases/"+id+"/revoke",debtManager,action("original-revoke",0));assertEquals(0,replay.code(),replay.toString());assertEquals(revoke.data().path("id"),replay.data().path("id"));
        assertEquals(2,get("/api/manager/refusal-cases/"+id+"/history",mgr).data().size());
        // 后增历史订单仅为分页压力；原案件及资产事实不因清单/纠错被改变。
        before.remove("orders");Map<String,Object> after=financialFacts();after.remove("orders");assertEquals(before,after);
        assertEquals(1,intOf("select payment_status from orders where id=?",id));assertEquals(3,intOf("select status from orders where id=?",id));
    }

    @Test void refusalHistoryTraversesBeyond200AndLookupNeverEscapesCurrentStationOrRole() {
        seed();long id=refusal(station,true);for(int i=0;i<201;i++)refusal(station,false);
        long foreign=createStation("无关站");String other=staffToken(createStaff("他站长","STATION_MANAGER",foreign,1),"STATION_MANAGER",foreign);
        String delivery=staffToken(createStaff("配送员","DELIVERY",station,1),"DELIVERY",station);
        assertEquals(1,refusalPage("?orderId="+id,other).code());assertEquals(1,refusalPage("",cus).code());assertEquals(1,refusalPage("",delivery).code());
        assertEquals(station,refusalPage("?stationId="+foreign,mgr).data().path("stationId").asLong(),"客户端站别不能覆盖登录站");
        assertEquals(1,refusalPage("?beforeId=0",mgr).code());assertEquals(1,refusalPage("?scope=BAD",mgr).code());assertEquals(1,refusalPage("?orderId="+id+"&beforeId=1",mgr).code());
        Set<Long> ids=new HashSet<>();Long cursor=null;int pages=0;
        do {
            Api page=refusalPage("?scope=ALL"+(cursor==null?"":"&beforeId="+cursor),mgr);assertEquals(0,page.code(),page.toString());
            assertTrue(page.data().path("items").size()<=50);
            for(JsonNode row:page.data().path("items"))assertTrue(ids.add(row.path("order_id").asLong()));
            cursor=page.data().path("nextBeforeId").isNull()?null:page.data().path("nextBeforeId").asLong();assertTrue(++pages<10);
        } while(cursor!=null);
        assertEquals(202,ids.size());assertTrue(ids.contains(id));assertEquals(0,refusalPage("",other).data().path("items").size());
    }
}
