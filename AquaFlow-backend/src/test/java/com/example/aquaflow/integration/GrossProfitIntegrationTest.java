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
import java.time.LocalDateTime;
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
 void ticketSeparated(JsonNode r) {
  assertFalse(r.path("profitComparable").asBoolean());
  assertEquals("购票与兑水成本未对应，利润暂不显示。",r.path("profitUnavailableReason").asText());
  assertTrue(r.path("netProfit").isNull());assertTrue(r.path("totalProfit").isNull());
  for(JsonNode row:r.path("items")){assertTrue(row.path("profit").isNull());assertEquals("—",row.path("profitRateText").asText());}
 }
 @Test void optionalCostNeverBecomesZero(){assertEquals(0,inbound(3,null,"INB-optional-cost-0001").code());sold(2,1);JsonNode r=report();money("40",r,"totalIncome");incomeOnly(r);assertNull(decimalOf("select cost_amount from inventory where station_id=? and product_id=?",station,product));assertEquals(0,get("/api/manager/gross-profit/missing-cost",token).data().size());}
 @Test void explicitZeroAndImmutableReceipt(){assertEquals(0,inbound(3,"0","INB-zero-cost-0000001").code());assertEquals(0,inbound(3,"0.00","INB-zero-cost-0000001").code());assertNotEquals(0,inbound(3,"1","INB-zero-cost-0000001").code());assertEquals(3,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));assertEquals(1,intOf("select count(*) from inventory_record where station_id=?",station));sold(2,1);JsonNode r=report();assertTrue(r.path("costComplete").asBoolean());money("0",r,"totalCost");money("40",r,"totalProfit");money("40",r,"netProfit");}
 @Test void mixedKnownBatchesAndLaterPriceCannotRewriteHistory(){assertEquals(0,inbound(2,"10","INB-known-batch-00001").code());assertEquals(0,inbound(2,"20","INB-known-batch-00002").code());sold(3,1);JsonNode r=report();money("60",r,"totalIncome");money("22.50",r,"totalCost");money("37.50",r,"netProfit");assertEquals(0,new BigDecimal("7.50").compareTo(decimalOf("select cost_amount from inventory where station_id=? and product_id=?",station,product)));assertEquals(0,inbound(1,"100","INB-known-batch-00003").code());money("22.50",report(),"totalCost");}
 @Test void mixedUnknownPoolAndPeriodIsolation(){assertEquals(0,inbound(1,null,"INB-unknown-batch-001").code());assertEquals(0,inbound(1,"10","INB-unknown-batch-002").code());long old=sold(2,1);incomeOnly(report());jdbc.update("update orders set create_time=? where id=?",TestBusinessClock.now().minusDays(2),old);assertEquals(0,inbound(1,"5","INB-unknown-batch-003").code());sold(1,1);money("5",report(),"totalCost");incomeOnly(report(TestBusinessClock.today().minusDays(2).toString()));}
 @Test void legacyPriceCannotBackfillHistoricalCost(){jdbc.update("update inventory set quantity=5,cost_price=12 where station_id=? and product_id=?",station,product);long id=createOrderFull(customer,address,station,product,4,2,1,"20","0","20",false,1);createOrderItemFull(id,product,"旧订单",1,0,"20","0");incomeOnly(report());jdbc.update("update inventory set cost_price=1 where station_id=?",station);incomeOnly(report());assertNotEquals(0,put("/api/manager/gross-profit/cost",token,"{\"productId\":"+product+",\"costPrice\":0}").code());}
 @Test void unrelatedCatalogMissingCostDoesNotHideCompletePeriod(){long other=createProduct("未入库商品",2,"99","0",0,"0");createInventoryFull(station,other,0,0,"0");assertEquals(0,inbound(2,"12","INB-scope-batch-00001").code());sold(1,1);money("6",report(),"totalCost");}
 @Test void invalidMoneyAndForeignProductDoNotMutateStock(){for(String invalid:new String[]{"-1","0.001","10000000000000000"})assertNotEquals(0,inbound(2,invalid,"INB-invalid-cost-0001").code());assertEquals(0,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));assertEquals(0,intOf("select count(*) from inventory_record"));long foreign=createStation("他站");jdbc.update("update product set owner_station_id=? where id=?",foreign,product);assertNotEquals(0,inbound(1,"10","INB-foreign-cost-0001").code());assertEquals(0,intOf("select count(*) from inventory_record"));}
 @Test void actualReturnRestoresCostButMoneyRefundDoesNot(){assertEquals(0,inbound(4,"20","INB-return-cost-00001").code());long id=sold(2,1);insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount) values(?,?,?,1,3,-40,-40)",id,customer,station);JsonNode r=report();money("0",r,"orderRevenue");money("10",r,"totalCost");money("-10",r,"totalProfit");inventories.setStock(station,product,3,"REFUND_RESTORE",id,"退回一件");money("5",report(),"totalCost");inventories.setStock(station,product,4,"REFUND_RESTORE",id,"再退一件");money("0",report(),"totalCost");assertEquals(0,new BigDecimal("20").compareTo(decimalOf("select cost_amount from inventory where station_id=? and product_id=?",station,product)));}
 @Test void ticketRevenueOnlyOnceAndCrossPeriodCost() {
  assertEquals(0,inbound(2,"24","INB-ticket-cost-00001").code());
  sold(2,3);
  long pay=createPaymentRecord(null,customer,station,"36",1,2);
  jdbc.update("update payment_record set ticket_water_type_id=?,ticket_qty=2,update_time=? where id=?",product,TestBusinessClock.now(),pay);
  JsonNode samePeriod=report();
  assertEquals("订单按下单日，购票按收款日；水票收款与兑水成本可能跨期。", samePeriod.path("periodBasisNote").asText());
  money("0",samePeriod,"orderRevenue");
  money("36",samePeriod,"ticketRevenue");
  money("24",samePeriod,"totalCost");
  assertTrue(samePeriod.path("costComplete").asBoolean());
  money("0",samePeriod,"orderIncome");
  ticketSeparated(samePeriod);

  LocalDateTime purchasedAt=TestBusinessClock.now().minusDays(40);
  jdbc.update("update payment_record set update_time=? where id=?",purchasedAt,pay);
  JsonNode purchasePeriod=report(purchasedAt.toLocalDate().toString());
  money("36",purchasePeriod,"ticketRevenue");
  money("36",purchasePeriod,"totalIncome");
  assertTrue(purchasePeriod.path("costComplete").asBoolean());
  money("0",purchasePeriod,"totalCost");
  money("0",purchasePeriod,"wage");
  assertEquals(0,purchasePeriod.path("missingCostKinds").asInt());
  ticketSeparated(purchasePeriod);
  JsonNode redemptionPeriod=report();
  money("0",redemptionPeriod,"orderRevenue");
  money("0",redemptionPeriod,"ticketRevenue");
  money("0",redemptionPeriod,"totalIncome");
  money("24",redemptionPeriod,"totalCost");
  money("0",redemptionPeriod,"wage");
  assertTrue(redemptionPeriod.path("costComplete").asBoolean());
  ticketSeparated(redemptionPeriod);
 }

 @Test void zeroAmountGiftReceiptAndGiftRedemptionRemainDifferentFacts() {
  long gift=createPaymentRecord(null,customer,station,"0",1,2);
  jdbc.update("update payment_record set ticket_water_type_id=?,ticket_qty=2,update_time=? where id=?",product,TestBusinessClock.now(),gift);
  JsonNode noDelivery=report();
  assertTrue(noDelivery.path("costComplete").asBoolean());assertTrue(noDelivery.path("profitComparable").asBoolean());
  money("0",noDelivery,"ticketRevenue");money("0",noDelivery,"totalCost");money("0",noDelivery,"netProfit");
  assertEquals(0,inbound(2,"24","INB-gift-profit-00001").code());sold(2,3);
  JsonNode delivered=report();ticketSeparated(delivered);
  assertTrue(delivered.path("costComplete").asBoolean());money("0",delivered,"ticketRevenue");money("24",delivered,"totalCost");
 }

 @Test void splitPaymentEvidenceSeparatesServiceRemainderEvenWhenMethodIsNotTicket() {
  assertEquals(0,inbound(2,"24","INB-split-profit-0001").code());
  long order=sold(2,2);
  jdbc.update("update orders set delivery_fee=5,floor_fee=2,total_amount=47 where id=?",order);
  jdbc.update("insert into order_ticket_payment(order_id,customer_id,station_id,water_amount,delivery_fee,floor_fee,cash_due,cash_method,state) values(?,?,?,40,5,2,7,2,'CONSUMED')",order,customer,station);
  long payment=createPaymentRecord(order,customer,station,"7",2,2);
  jdbc.update("update payment_record set water_amount=0,delivery_fee=5,floor_fee=2 where id=?",payment);
  insert("insert into staff_earning(station_id,staff_id,order_id,kind,product_id,amount) values(?,?,?,'DELIVERY_BUCKET',?,6)",station,manager,order,product);
  JsonNode paid=report();ticketSeparated(paid);
  assertTrue(paid.path("costComplete").asBoolean());money("0",paid,"orderRevenue");money("7",paid,"orderIncome");
  money("7",paid,"totalIncome");money("24",paid,"totalCost");money("6",paid,"wage");
  insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount,delivery_fee,floor_fee) values(?,?,?,2,3,-7,0,-5,-2)",order,customer,station);
  JsonNode refunded=report();ticketSeparated(refunded);money("0",refunded,"orderIncome");money("24",refunded,"totalCost");money("6",refunded,"wage");
 }

 @Test void fullAndPartialTicketExitDoNotTurnMatchingIntoCostCompleteness() {
  long original=createPaymentRecord(null,customer,station,"36",2,2);
  jdbc.update("update payment_record set ticket_water_type_id=?,ticket_qty=3,update_time=? where id=?",product,TestBusinessClock.now(),original);
  long partial=createPaymentRecord(null,customer,station,"-12",2,3);
  insert("insert into ticket_exit_refund(original_payment_id,refund_payment_id,idempotency_key,quantity,amount,operator_id,create_time) values(?,?,'profit-partial-exit',1,12,?,NOW())",original,partial,manager);
  JsonNode remaining=report();ticketSeparated(remaining);money("24",remaining,"ticketRevenue");
  assertTrue(remaining.path("costComplete").asBoolean());money("0",remaining,"totalCost");
  long rest=createPaymentRecord(null,customer,station,"-24",2,3);
  insert("insert into ticket_exit_refund(original_payment_id,refund_payment_id,idempotency_key,quantity,amount,operator_id,create_time) values(?,?,'profit-full-exit',2,24,?,NOW())",original,rest,manager);
  jdbc.update("update payment_record set status=3,update_time=? where id=?",TestBusinessClock.now(),original);
  JsonNode returned=report();ticketSeparated(returned);money("0",returned,"ticketRevenue");
  assertTrue(returned.path("costComplete").asBoolean());money("0",returned,"totalCost");
 }

 @Test void cancelledAndForeignTicketFactsDoNotHideCurrentStationsOrdinaryProfit() {
  assertEquals(0,inbound(2,"24","INB-ticket-scope-0001").code());sold(2,2);
  long cancelled=createOrderFull(customer,address,station,product,5,4,3,"40","0","40",false,2);
  jdbc.update("insert into order_ticket_payment(order_id,customer_id,station_id,water_amount,delivery_fee,floor_fee,cash_due,state) values(?,?,?,40,0,0,0,'CANCELLED')",cancelled,customer,station);
  long other=createStation("票款隔离站");
  long payment=createPaymentRecord(null,customer,other,"36",2,2);
  jdbc.update("update payment_record set ticket_water_type_id=?,ticket_qty=2,update_time=? where id=?",product,TestBusinessClock.now(),payment);
  JsonNode current=report();assertTrue(current.path("profitComparable").asBoolean());money("16",current,"netProfit");
  String otherToken=staffToken(createStaff("票款隔离站长","STATION_MANAGER",other,1),"STATION_MANAGER",other);
  JsonNode foreign=get("/api/manager/gross-profit",otherToken).data();ticketSeparated(foreign);money("36",foreign,"ticketRevenue");
  assertTrue(foreign.path("costComplete").asBoolean());money("0",foreign,"totalCost");
 }

 @Test void revenueFeesWagesAndDepositsKeepTheirBasis() {
  assertEquals(0,inbound(2,"24","INB-income-cost-00001").code());
  long id=sold(2,2);
  jdbc.update("update orders set delivery_fee=5,floor_fee=2,deposit_amount=60,total_amount=107 where id=?",id);
  insert("insert into staff_earning(station_id,staff_id,order_id,kind,product_id,amount) values(?,?,?,'DELIVERY_BUCKET',?,6)",station,manager,id,product);
  JsonNode r=report();
  assertTrue(r.path("costComplete").asBoolean());
  assertTrue(r.path("profitComparable").asBoolean());
  money("40",r,"orderRevenue");
  money("0",r,"ticketRevenue");
  money("5",r,"deliveryFee");
  money("2",r,"floorFee");
  money("47",r,"totalIncome");
  money("47",r,"orderIncome");
  money("24",r,"totalCost");
  money("6",r,"wage");
  money("17",r,"netProfit");
  money("40",r,"totalRevenue");
  money("16",r,"totalProfit");
 }
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

 @Test void barrelCountUsesOrderSnapshotsWithoutMultiplyingOrderFees(){
  JsonNode empty=report();
  assertBucketSnapshot(empty,0L,0);
  assertEquals(0,empty.path("orderCount").asInt());
  reportOrder(station,product,4,2,1,7,0,TestBusinessClock.now());
  JsonNode nonBarrel=report();
  assertBucketSnapshot(nonBarrel,0L,0);
  assertEquals(1,nonBarrel.path("orderCount").asInt());

  long barrelA=createProduct("桶装水甲",1,"20","0",0,"0");
  long barrelB=createProduct("桶装水乙",1,"20","0",0,"0");
  long device=createProduct("饮水设备",3,"20","0",0,"0");
  long mixed=reportOrder(station,barrelA,4,2,1,2,5,TestBusinessClock.now());
  createOrderItemFull(mixed,barrelB,"桶装水乙",3,0,"20","0");
  createOrderItemFull(mixed,product,"瓶装水",9,0,"20","0");
  createOrderItemFull(mixed,device,"饮水设备",4,0,"20","0");
  long second=reportOrder(station,barrelA,4,2,2,4,4,TestBusinessClock.now());
  jdbc.update("update orders set water_amount=360,total_amount=367,delivery_fee=5,floor_fee=2,quantity=99 where id=?",mixed);
  jdbc.update("update orders set total_amount=90,delivery_fee=7,floor_fee=3 where id=?",second);
  jdbc.update("update product set status=0,category=2 where id=?",barrelB);
  jdbc.update("update product set category=1 where id=?",product);
  JsonNode r=report();
  assertEquals(3,r.path("orderCount").asInt());
  assertBucketSnapshot(r,9L,0);
  money("12",r,"deliveryFee");money("5",r,"floorFee");
  assertMatchesDashboardWeek(9L,0);

  jdbc.update("update orders set delivery_bucket_qty=null where id=?",second);
  JsonNode missing=report();
  assertBucketSnapshot(missing,null,1);
  assertEquals(3,missing.path("orderCount").asInt());
  money("12",missing,"deliveryFee");money("5",missing,"floorFee");
  assertMatchesDashboardWeek(null,1);

  jdbc.update("update orders set status=5 where id=?",second);
  assertBucketSnapshot(report(),5L,0);
  assertMatchesDashboardWeek(5L,0);
 }

 @Test void barrelCountKeepsCreationWindowStatusAndPaymentScope(){
  long barrel=createProduct("桶装水",1,"20","0",0,"0");
  LocalDateTime start=TestBusinessClock.today().atStartOfDay();
  TestBusinessClock.freezeAt(start.plusHours(12));
  reportOrder(station,barrel,1,0,1,1,1,start);
  reportOrder(station,barrel,2,1,2,2,2,start.plusHours(1));
  reportOrder(station,barrel,3,2,3,3,3,start.plusHours(2));
  reportOrder(station,barrel,4,2,1,4,4,start.plusDays(1).minusSeconds(1));
  reportOrder(station,barrel,5,2,1,8,null,start.plusHours(3));
  long old=reportOrder(station,barrel,4,2,1,16,null,start.minusSeconds(1));
  jdbc.update("update orders set update_time=? where id=?",start.plusHours(4),old);
  reportOrder(station,barrel,4,2,1,32,null,start.plusDays(1));
  JsonNode r=report();
  assertEquals(4,r.path("orderCount").asInt());
  assertBucketSnapshot(r,10L,0);
  Api dashboard=get("/api/dashboard/report?range=today",token);
  assertEquals(0,dashboard.code(),dashboard.toString());
  assertBucketSnapshot(dashboard.data().path("summary"),6L,0);
 }

 @Test void barrelCountKeepsSettlementStationPrecedenceAndFallbacks(){
  long other=createStation("另一水站");
  long barrel=createProduct("桶装水",1,"20","0",0,"0");
  long settledHere=reportOrder(other,barrel,4,2,1,2,2,TestBusinessClock.now());
  jdbc.update("update orders set settle_station_id=? where id=?",station,settledHere);
  long deliveredHere=reportOrder(other,barrel,4,2,1,3,3,TestBusinessClock.now());
  jdbc.update("update orders set settle_station_id=null,delivery_station_id=? where id=?",station,deliveredHere);
  long ownedHere=reportOrder(station,barrel,4,2,1,5,5,TestBusinessClock.now());
  jdbc.update("update orders set settle_station_id=null,delivery_station_id=null where id=?",ownedHere);
  long settledElsewhere=reportOrder(station,barrel,4,2,1,7,7,TestBusinessClock.now());
  jdbc.update("update orders set settle_station_id=? where id=?",other,settledElsewhere);
  long deliveredElsewhere=reportOrder(station,barrel,4,2,1,11,11,TestBusinessClock.now());
  jdbc.update("update orders set settle_station_id=null,delivery_station_id=? where id=?",other,deliveredElsewhere);
  JsonNode r=report();
  assertEquals(3,r.path("orderCount").asInt());
  assertBucketSnapshot(r,10L,0);
  String otherToken=staffToken(createStaff("另一站长","STATION_MANAGER",other,1),"STATION_MANAGER",other);
  String day=TestBusinessClock.today().toString();
  Api response=get("/api/manager/gross-profit?from="+day+"&to="+day+"&stationId="+station,otherToken);
  assertEquals(0,response.code(),response.toString());
  assertEquals(2,response.data().path("orderCount").asInt());
  assertBucketSnapshot(response.data(),18L,0);
 }

 private void assertMatchesDashboardWeek(Long quantity,long missing) {
  var today=TestBusinessClock.today();
  Api profit=get("/api/manager/gross-profit?from="+today.minusDays(6)+"&to="+today,token);
  Api dashboard=get("/api/dashboard/report?range=7d",token);
  assertEquals(0,profit.code(),profit.toString());
  assertEquals(0,dashboard.code(),dashboard.toString());
  assertBucketSnapshot(profit.data(),quantity,missing);
  assertBucketSnapshot(dashboard.data().path("summary"),quantity,missing);
 }

 private void assertBucketSnapshot(JsonNode row,Long quantity,long missing) {
  JsonNode value=row.path("deliveryBucketQty");
  if (quantity==null) assertTrue(value.isNull(),row.toString());
  else {
   assertTrue(value.isIntegralNumber(),row.toString());
   assertEquals(quantity.longValue(),value.longValue());
  }
  assertEquals(quantity!=null,row.path("deliveryBucketQtyKnown").booleanValue());
  assertTrue(row.path("deliveryBucketQtyKnown").isBoolean(),row.toString());
  assertTrue(row.path("deliveryBucketUnknownOrders").isIntegralNumber(),row.toString());
  assertEquals(missing,row.path("deliveryBucketUnknownOrders").longValue());
  if (row.has("orderCount") && row.has("totalIncome")) assertEquals(value,row.path("barrelCount"));
 }

 private long reportOrder(long ownerStation,long itemProduct,int status,int paymentStatus,int method,int quantity,Integer bucketSnapshot,LocalDateTime created){
  String amount=BigDecimal.valueOf(20L*quantity).toPlainString();
  long id=createOrderFull(customer,address,ownerStation,itemProduct,status,paymentStatus,method,amount,"0",amount,false,quantity);
  createOrderItemFull(id,itemProduct,"统计商品",quantity,0,"20","0");
  jdbc.update("update orders set create_time=?,delivery_bucket_qty=? where id=?",created,bucketSnapshot,id);
  return id;
 }

}
