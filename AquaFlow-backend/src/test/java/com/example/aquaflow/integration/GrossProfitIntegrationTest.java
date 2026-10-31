package com.example.aquaflow.integration;
import com.example.aquaflow.service.InventoryReservationService;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.support.TestBusinessClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
/** HTTP receipts and transactional cost snapshots; missing history stays unknown. */
@Import(TestBusinessClock.Config.class)
class GrossProfitIntegrationTest extends AbstractIntegrationTest {
 @Autowired InventoryReservationService reservations;
 @Autowired InventoryService inventories;
 long station,manager,customer,address,product; String token;
 @BeforeEach void seed(){
  TestBusinessClock.freezeAtDbNow(jdbc);station=createStation("成本测试站");manager=createStaff("站长","STATION_MANAGER",station,1);
  customer=createCustomer("客户","cost-snapshot-customer");address=createAddress(customer,"测试地址");
  product=createProduct("测试商品",2,"20.00","0.00",0,"0.00");createInventoryFull(station,product,0,0,"0.00");token=staffToken(manager,"STATION_MANAGER",station);
 }
 Api inbound(int qty,String cost,String key){return post("/api/inventory/inbound-with-cost-receipt?stationId="+station,token,
  "{\"clientRequestId\":\""+key+"\",\"items\":[{\"productId\":"+product+",\"quantity\":"+qty+(cost==null?"":",\"costAmount\":"+cost)+"}]}");}
 long sold(int qty,int method){String amount=BigDecimal.valueOf(20L*qty).toPlainString();long id=createOrderFull(customer,address,station,product,2,2,method,amount,"0.00",amount,false,qty);
  createReservedItem(id,product,"测试商品",qty,"20.00","0.00");reservations.shipForOrder(id,station);jdbc.update("update orders set status=4 where id=?",id);return id;}
 JsonNode report(){return report(TestBusinessClock.today().toString());}
 JsonNode report(String day){Api r=get("/api/manager/gross-profit?from="+day+"&to="+day,token);assertEquals(0,r.code(),r.toString());return r.data();}
 void money(String e,JsonNode r,String f){assertTrue(r.path(f).isNumber(),f+": "+r);assertEquals(0,new BigDecimal(e).compareTo(r.path(f).decimalValue()),f);}
 void incomeOnly(JsonNode r){assertFalse(r.path("costComplete").asBoolean());for(String f:new String[]{"totalCost","totalProfit","netProfit"})assertTrue(r.path(f).isNull(),f+": "+r);for(JsonNode row:r.path("items"))assertTrue(row.path("profit").isNull());}
 @Test void optionalCostNeverBecomesZero(){assertEquals(0,inbound(3,null,"INB-optional-cost-0001").code());sold(2,1);JsonNode r=report();money("40",r,"totalIncome");incomeOnly(r);assertNull(decimalOf("select cost_amount from inventory where station_id=? and product_id=?",station,product));assertEquals(0,get("/api/manager/gross-profit/missing-cost",token).data().size());}
 @Test void explicitZeroAndImmutableReceipt(){assertEquals(0,inbound(3,"0","INB-zero-cost-0000001").code());assertEquals(0,inbound(3,"0.00","INB-zero-cost-0000001").code());assertNotEquals(0,inbound(3,"1","INB-zero-cost-0000001").code());assertEquals(3,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));assertEquals(1,intOf("select count(*) from inventory_record where station_id=?",station));sold(2,1);JsonNode r=report();assertTrue(r.path("costComplete").asBoolean());money("0",r,"totalCost");money("40",r,"totalProfit");}
 @Test void mixedKnownBatchesAndLaterPriceCannotRewriteHistory(){assertEquals(0,inbound(2,"10","INB-known-batch-00001").code());assertEquals(0,inbound(2,"20","INB-known-batch-00002").code());sold(3,1);JsonNode r=report();money("60",r,"totalIncome");money("22.50",r,"totalCost");money("37.50",r,"netProfit");assertEquals(0,new BigDecimal("7.50").compareTo(decimalOf("select cost_amount from inventory where station_id=? and product_id=?",station,product)));assertEquals(0,inbound(1,"100","INB-known-batch-00003").code());money("22.50",report(),"totalCost");}
 @Test void mixedUnknownPoolAndPeriodIsolation(){assertEquals(0,inbound(1,null,"INB-unknown-batch-001").code());assertEquals(0,inbound(1,"10","INB-unknown-batch-002").code());long old=sold(2,1);incomeOnly(report());jdbc.update("update orders set create_time=? where id=?",TestBusinessClock.now().minusDays(2),old);assertEquals(0,inbound(1,"5","INB-unknown-batch-003").code());sold(1,1);money("5",report(),"totalCost");incomeOnly(report(TestBusinessClock.today().minusDays(2).toString()));}
 @Test void legacyPriceCannotBackfillHistoricalCost(){jdbc.update("update inventory set quantity=5,cost_price=12 where station_id=? and product_id=?",station,product);long id=createOrderFull(customer,address,station,product,4,2,1,"20","0","20",false,1);createOrderItemFull(id,product,"旧订单",1,0,"20","0");incomeOnly(report());jdbc.update("update inventory set cost_price=1 where station_id=?",station);incomeOnly(report());assertNotEquals(0,put("/api/manager/gross-profit/cost",token,"{\"productId\":"+product+",\"costPrice\":0}").code());}
 @Test void unrelatedCatalogMissingCostDoesNotHideCompletePeriod(){long other=createProduct("未入库商品",2,"99","0",0,"0");createInventoryFull(station,other,0,0,"0");assertEquals(0,inbound(2,"12","INB-scope-batch-00001").code());sold(1,1);money("6",report(),"totalCost");}
 @Test void invalidMoneyAndForeignProductDoNotMutateStock(){for(String invalid:new String[]{"-1","0.001","10000000000000000"})assertNotEquals(0,inbound(2,invalid,"INB-invalid-cost-0001").code());assertEquals(0,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));assertEquals(0,intOf("select count(*) from inventory_record"));long foreign=createStation("他站");jdbc.update("update product set owner_station_id=? where id=?",foreign,product);assertNotEquals(0,inbound(1,"10","INB-foreign-cost-0001").code());assertEquals(0,intOf("select count(*) from inventory_record"));}
 @Test void actualReturnRestoresCostButMoneyRefundDoesNot(){assertEquals(0,inbound(4,"20","INB-return-cost-00001").code());long id=sold(2,1);insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount) values(?,?,?,1,3,-40,-40)",id,customer,station);JsonNode r=report();money("0",r,"orderRevenue");money("10",r,"totalCost");money("-10",r,"totalProfit");inventories.setStock(station,product,3,"REFUND_RESTORE",id,"退回一件");money("5",report(),"totalCost");inventories.setStock(station,product,4,"REFUND_RESTORE",id,"再退一件");money("0",report(),"totalCost");assertEquals(0,new BigDecimal("20").compareTo(decimalOf("select cost_amount from inventory where station_id=? and product_id=?",station,product)));}
 @Test void ticketRevenueOnlyOnceAndCrossPeriodCost(){assertEquals(0,inbound(2,"24","INB-ticket-cost-00001").code());sold(2,3);long pay=createPaymentRecord(null,customer,station,"36",1,2);jdbc.update("update payment_record set ticket_water_type_id=?,ticket_qty=2,update_time=? where id=?",product,TestBusinessClock.now(),pay);JsonNode r=report();money("0",r,"orderRevenue");money("36",r,"ticketRevenue");money("24",r,"totalCost");money("12",r,"totalProfit");jdbc.update("update payment_record set update_time=? where id=?",TestBusinessClock.now().minusDays(40),pay);r=report();money("0",r,"totalIncome");money("-24",r,"totalProfit");}
 @Test void revenueFeesWagesAndDepositsKeepTheirBasis(){assertEquals(0,inbound(2,"24","INB-income-cost-00001").code());long id=sold(2,2);jdbc.update("update orders set delivery_fee=5,floor_fee=2,deposit_amount=60,total_amount=107 where id=?",id);insert("insert into staff_earning(station_id,staff_id,order_id,kind,product_id,amount) values(?,?,?,'DELIVERY_BUCKET',?,6)",station,manager,id,product);JsonNode r=report();money("40",r,"totalRevenue");money("47",r,"totalIncome");money("6",r,"wage");money("17",r,"netProfit");}
 @Test void stationAndRoleIsolation(){assertEquals(0,inbound(1,"7","INB-isolate-cost-0001").code());sold(1,1);long other=createStation("隔离站");String ot=staffToken(createStaff("他站长","STATION_MANAGER",other,1),"STATION_MANAGER",other);money("0",get("/api/manager/gross-profit?stationId="+station,ot).data(),"totalIncome");assertNotEquals(0,get("/api/manager/gross-profit",customerToken(customer)).code());assertNotEquals(0,post("/api/inventory/inbound-with-cost-receipt",customerToken(customer),"{}").code());}
 @Test void fulfilledCrossStationUsesOnlyFulfillingStationsSnapshot(){
  long other=createStation("履约站");createInventoryFull(other,product,2,0,"0");
  jdbc.update("update inventory set cost_amount=10 where station_id=? and product_id=?",other,product);
  long id=createOrderFull(customer,address,station,product,2,2,1,"40","0","40",false,2);
  jdbc.update("update orders set settle_station_id=?,delivery_station_id=? where id=?",other,other,id);
  createReservedItem(id,product,"跨站商品",2,"20","0");reservations.shipForOrder(id,other);
  jdbc.update("update orders set status=4 where id=?",id);
  money("0",report(),"totalIncome");
  String ot=staffToken(createStaff("履约站长","STATION_MANAGER",other,1),"STATION_MANAGER",other);
  JsonNode r=get("/api/manager/gross-profit",ot).data();money("40",r,"totalIncome");money("10",r,"totalCost");money("30",r,"totalProfit");
 }

}
