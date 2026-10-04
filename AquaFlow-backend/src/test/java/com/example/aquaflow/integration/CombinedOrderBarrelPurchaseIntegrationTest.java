package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 双路径真实 HTTP/MySQL 验收：同次收款、实物守恒、并发和组成退款。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=true"})
class CombinedOrderBarrelPurchaseIntegrationTest extends AbstractIntegrationTest {
    @Autowired BarrelLedgerService ledger;
    @Autowired ReconciliationService reconcile;
    @Autowired OrderBarrelPurchaseService orderPurchases;
    @Autowired PaymentService paymentService;
    long station,manager,customer,product,address;
    String cus,mgr;
    void seed() {
        station=createStation("合并收款站");manager=createStaff("站长","STATION_MANAGER",station,1);
        customer=createCustomer("客户","combined-customer");product=createProduct("桶装水",1,"20.00","30.00",1,"8.00");
        createInventoryFull(station,product,20,1,"8.00");jdbc.update("update inventory set deposit_price=50 where station_id=? and product_id=?",station,product);
        createInventoryRecord(station,product,20,"STOCK_IN",0);address=createAddress(customer,"一楼");
        cus=customerToken(customer);mgr=staffToken(manager,"STATION_MANAGER",station);
    }
    String items(int quantity) {return "[{\"productId\":"+product+",\"quantity\":"+quantity+"}]";}
    Api quote(String items,int method) {
        return post("/api/payments/quote",cus,"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":"+method+",\"items\":"+items+"}");
    }
    String body(String key,String items,int method,String purchases) {
        return "{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":"+method+",\"idempotencyKey\":\""+key+"\",\"items\":"+items+(purchases==null?"":",\"barrelPurchases\":"+purchases)+"}";
    }
    long order(String key,int quantity,int method) {
        Api q=quote(items(quantity),method);assertEquals(0,q.code(),q.toString());
        Api r=post("/api/orders/create",cus,body(key,items(quantity),method,q.data().path("barrelPurchases").toString()));
        assertEquals(0,r.code(),r.toString());return r.data().path("orderId").asLong();
    }
    long pay(long order,int method) {
        Api r=post("/api/payments",cus,"{\"orderId\":"+order+",\"paymentMethod\":"+method+"}");assertEquals(0,r.code(),r.toString());return r.data().path("id").asLong();
    }
    void buy(int quantity,String key) {
        Api r=post("/api/barrel-rights/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":"+quantity+",\"paymentMethod\":2,\"idempotencyKey\":\""+key+"\"}");assertEquals(0,r.code(),r.toString());
        assertEquals(0,put("/api/payments/"+r.data().path("paymentId").asLong()+"/confirm",mgr,null).code());
    }
    Api complete(long order,boolean collected,int returned) {
        long item=jdbc.queryForObject("select id from order_item where order_id=? limit 1",Long.class,order);
        return post("/api/delivery/orders/"+order+"/complete",mgr,"{\"collected\":"+collected+",\"itemReturns\":[{\"orderItemId\":"+item+",\"expected\":"+returned+",\"actual\":"+returned+",\"reasons\":[]}]}");
    }
    void deliver(long order,boolean collected,int returned) {
        assertEquals(0,post("/api/delivery/orders/"+order+"/accept",mgr,"{}").code());
        Api r=complete(order,collected,returned);assertEquals(0,r.code(),r.toString());
    }
    void balanced() {
        Map<String,Integer> r=reconcile.runReconcile();assertEquals(0,r.values().stream().mapToInt(Integer::intValue).sum(),r.toString());
        reconcile.runReconcileV2().entrySet().stream().filter(e -> e.getKey().matches("E(17|18|19|20|21|22)_.*")).forEach(e -> assertEquals(0,e.getValue(),e.toString()));
    }
    @Test void firstOrderPaysWaterAndStationDepositOnceAndCreatesNoUnpaidRight() {
        seed();Api q=quote(items(1),1);assertEquals(0,q.code(),q.toString());assertFalse(q.data().path("blocked").asBoolean());
        assertEquals(70,q.data().path("totalAmount").asInt());assertEquals(50,q.data().path("extraDeposit").asInt());
        assertEquals(1,post("/api/orders/create",cus,body("first",items(1),1,null)).code());assertEquals(0,intOf("select count(*) from orders"));
        String body=body("first",items(1),1,q.data().path("barrelPurchases").toString());Api created=post("/api/orders/create",cus,body);assertEquals(0,created.code(),created.toString());long id=created.data().path("orderId").asLong();
        assertEquals(0,ledger.rightQty(customer,station,product));assertEquals(0,ledger.occupiedQty(customer,station,product));assertEquals(1,intOf("select pending_qty from barrel_right_reservation where owner_id=?",id));balanced();
        long payment=pay(id,1);assertEquals(70,intOf("select amount from payment_record where id=?",payment));assertEquals(50,intOf("select barrel_deposit from payment_record where id=?",payment));
        assertEquals(1,ledger.rightQty(customer,station,product));assertEquals(0,ledger.occupiedQty(customer,station,product));assertEquals(0,ledger.availableRights(customer,station,product));
        assertEquals(payment,pay(id,1));assertEquals(id,post("/api/orders/create",cus,body).data().path("orderId").asLong());assertEquals(1,intOf("select count(*) from customer_barrel_lot"));
        deliver(id,false,0);assertEquals(1,ledger.occupiedQty(customer,station,product));assertEquals(0,intOf("select count(*) from order_barrel_exception"));balanced();
    }
    @Test void independentPurchaseAndEnoughCapacityDoNotChargeDepositAgain() {
        seed();buy(2,"independent");Api q=quote(items(1),1);assertEquals(0,q.data().path("extraDeposit").asInt());assertEquals(20,q.data().path("totalAmount").asInt());
        long id=order("enough",1,1);pay(id,1);assertEquals(0,intOf("select count(*) from order_barrel_purchase"));assertEquals(2,ledger.rightQty(customer,station,product));balanced();
    }
    @Test void partialAndMultipleProductsKeepSeparatePurchaseSnapshotsUnderOnePayment() {
        seed();buy(1,"one");long other=createProduct("另一款",1,"10.00","40.00",1,"7.00");createInventoryFull(station,other,20,1,"7.00");createInventoryRecord(station,other,20,"STOCK_IN",0);
        String items="[{\"productId\":"+product+",\"quantity\":2},{\"productId\":"+other+",\"quantity\":1}]";
        Api q=quote(items,1);assertEquals(90,q.data().path("extraDeposit").asInt());assertEquals(140,q.data().path("totalAmount").asInt());
        Api r=post("/api/orders/create",cus,body("multi",items,1,q.data().path("barrelPurchases").toString()));assertEquals(0,r.code(),r.toString());long id=r.data().path("orderId").asLong();long payment=pay(id,1);
        assertEquals(2,intOf("select count(*) from order_barrel_purchase where order_id=?",id));assertEquals(1,intOf("select count(distinct payment_id) from order_barrel_purchase where order_id=?",id));assertEquals(2,ledger.rightQty(customer,station,product));assertEquals(1,ledger.rightQty(customer,station,other));balanced();
        assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());
        assertEquals(140,intOf("select -sum(amount) from payment_record where order_id=? and amount<0",id));
        assertEquals(3,intOf("select status from payment_record where id=?",payment),"同一退款的多个商品押金凭据均须计入原款退清");balanced();
    }
    @Test void stalePriceQuantityAndTicketCannotSilentlyBuyCapacity() {
        seed();Api q=quote(items(1),1);jdbc.update("update inventory set deposit_price=60 where station_id=? and product_id=?",station,product);
        assertEquals(1,post("/api/orders/create",cus,body("null-confirmation",items(1),1,"[null]")).code());
        assertEquals(1,post("/api/orders/create",cus,body("stale",items(1),1,q.data().path("barrelPurchases").toString())).code());
        assertEquals(1,post("/api/orders/create",cus,body("more",items(2),1,q.data().path("barrelPurchases").toString())).code());assertEquals(0,intOf("select count(*) from orders"));
        Api ticket=quote(items(1),3);assertTrue(ticket.data().path("blocked").asBoolean());assertEquals(1,post("/api/orders/create",cus,body("ticket",items(1),3,ticket.data().path("barrelPurchases").toString())).code());
    }
    @Test void unpaidCancellationDoesNotCreateOrRefundMoney() {
        seed();long id=order("unpaid",1,1);assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());assertEquals(0,ledger.rightQty(customer,station,product));assertEquals(0,intOf("select count(*) from payment_record"));assertEquals("CANCELLED",jdbc.queryForObject("select status from order_barrel_purchase where order_id=?",String.class,id));balanced();
    }
    @Test void cancellationRefundsOnlyThisOrdersNewDepositAndPreservesOldIndependentAsset() {
        seed();buy(1,"old");long id=order("with-new",2,1);long payment=pay(id,1);Api preview=get("/api/payments/"+payment+"/refund-preview",mgr);assertEquals(90,preview.data().path("refundAmount").asInt());assertEquals(50,preview.data().path("refundableDeposit").asInt());assertEquals(0,preview.data().path("retainedDeposit").asInt());assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());
        assertEquals(1,ledger.rightQty(customer,station,product));assertEquals(0,ledger.occupiedQty(customer,station,product));assertEquals(50,intOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station));
        assertEquals(90,intOf("select -sum(amount) from payment_record where order_id=? and amount<0",id));assertEquals(3,intOf("select status from payment_record where id=?",payment));balanced();
    }
    @Test void anotherOrderCanKeepPartOfNewCapacityWithoutMakingCancellationOverRefund() {
        seed();long first=order("first",2,1);long payment=pay(first,1);
        // Simulate the first order being released before a concurrent successor claims one unit.
        ledger.releaseRights("ORDER",first);long second=order("second",1,1);pay(second,1);
        Api preview=get("/api/payments/"+payment+"/refund-preview",mgr);assertEquals(90,preview.data().path("refundAmount").asInt());assertEquals(50,preview.data().path("retainedDeposit").asInt());
        assertEquals(0,put("/api/orders/"+first+"/customer-cancel",cus,null).code());assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(90,intOf("select -sum(amount) from payment_record where order_id=? and amount<0",first));assertEquals(2,intOf("select status from payment_record where id=?",payment));balanced();
    }
    @Test void cashAtDoorRequiresCollectedAndRollsBackBothMoneyAndRightsWhenStockCannotShip() {
        seed();jdbc.update("insert into customer_station_config(customer_id,station_id,offline_payment_enabled) values(?,?,1)",customer,station);
        long id=order("cash",1,2);pay(id,2);assertEquals(0,post("/api/delivery/orders/"+id+"/accept",mgr,"{}").code());
        assertEquals(1,complete(id,false,0).code());assertEquals(0,ledger.rightQty(customer,station,product));
        jdbc.update("update inventory set quantity=0 where station_id=? and product_id=?",station,product);
        assertEquals(1,complete(id,true,0).code());assertEquals(0,ledger.rightQty(customer,station,product));assertEquals(0,intOf("select count(*) from deposit_record"));assertEquals(1,intOf("select status from payment_record where order_id=?",id));
        jdbc.update("update inventory set quantity=20 where station_id=? and product_id=?",station,product);
        Api done=complete(id,true,0);assertEquals(0,done.code(),done.toString());assertEquals(1,ledger.rightQty(customer,station,product));assertEquals(1,ledger.occupiedQty(customer,station,product));assertEquals(4,intOf("select status from orders where id=?",id));balanced();
    }
    @Test void twoOldPhysicalBucketsPlusOneCombinedNewUnitReturnTwo() {
        seed();buy(2,"old-two");long old=order("old-delivery",2,1);pay(old,1);deliver(old,false,0);
        long id=order("one-new",3,1);pay(id,1);assertEquals(1,intOf("select pickup_qty from barrel_right_reservation where owner_id=?",id));deliver(id,false,2);
        assertEquals(3,ledger.rightQty(customer,station,product));assertEquals(3,ledger.occupiedQty(customer,station,product));assertEquals(0,intOf("select count(*) from order_barrel_exception"));balanced();
    }
    @Test void consumptionRefundThenApprovedDepositReturnSharesOriginalAmountLimit() {
        seed();long id=order("delivered",1,1);long payment=pay(id,1);deliver(id,false,0);
        Api water=put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedRefundAmount\":20}");assertEquals(0,water.code(),water.toString());assertEquals(1,ledger.rightQty(customer,station,product));balanced();
        Api request=post("/api/barrels/return",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"pickupMode\":\"STORE\",\"idempotencyKey\":\"return\"}");assertEquals(0,request.code(),request.toString());long record=request.data().path("recordId").asLong();
        assertEquals(0,put("/api/barrels/records/"+record+"/approve",mgr,"{\"pickupFee\":0}").code());assertEquals(0,put("/api/barrels/records/"+record+"/customer-confirm",cus,"{}").code());assertEquals(0,put("/api/barrels/records/"+record+"/status",mgr,"{\"status\":2}").code());
        Api refund=put("/api/barrels/records/"+record+"/status",mgr,"{\"status\":3,\"refundChannel\":\"ONLINE\"}");assertEquals(0,refund.code(),refund.toString());
        assertEquals(70,intOf("select -sum(amount) from payment_record where order_id=? and amount<0",id));assertEquals(0,ledger.rightQty(customer,station,product));assertEquals(0,ledger.occupiedQty(customer,station,product));assertEquals(1,put("/api/barrels/records/"+record+"/status",mgr,"{\"status\":3,\"refundChannel\":\"ONLINE\"}").code());balanced();
    }
    @Test void parallelRequestsNeverReusePaidCapacityOrCreateDuplicatePurchaseForSameKey() throws Exception {
        seed();buy(1,"old");String body=body("same",items(2),1,quote(items(2),1).data().path("barrelPurchases").toString());
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Api>> fs=new ArrayList<>();for(int i=0;i<2;i++)fs.add(pool.submit(() -> {start.await();return post("/api/orders/create",cus,body);}));start.countDown();
            for(var f:fs){Api r=f.get(30,TimeUnit.SECONDS);assertTrue(r.code()==0 || r.code()==1,r.toString());}
            assertEquals(1,intOf("select count(*) from orders"));assertEquals(1,intOf("select count(*) from order_barrel_purchase"));assertEquals(1,intOf("select sum(quantity-pending_qty) from barrel_right_reservation where status='ACTIVE'"));balanced();
        } finally {pool.shutdownNow();}
    }
    @Test void otherStationHasNoFreeCapacityAndCannotConfirmOrRefundThisPurchase() {
        seed();long id=order("station-a",1,1);long other=createStation("乙站");long staff=createStaff("乙站长","STATION_MANAGER",other,1);String token=staffToken(staff,"STATION_MANAGER",other);
        long payment=pay(id,1);assertEquals(0,ledger.rightQty(customer,other,product));assertEquals(1,put("/api/payments/"+payment+"/refund",token,"{\"scope\":\"WATER\",\"expectedRefundAmount\":20}").code());assertEquals(1,ledger.rightQty(customer,station,product));balanced();
    }
    @Test void unavailableOriginalChannelRollsBackCancellationInsteadOfPretendingRefundSucceeded() {
        seed();long id=order("channel",1,1);long payment=pay(id,1);
        org.springframework.test.util.ReflectionTestUtils.setField(orderPurchases,"mockWechatPay",false);
        try {
            assertEquals(1,put("/api/orders/"+id+"/customer-cancel",cus,null).code());
            assertEquals(1,ledger.rightQty(customer,station,product));
            assertEquals(1,intOf("select count(*) from barrel_right_reservation where owner_id=? and status='ACTIVE'",id));
            assertEquals(2,intOf("select status from payment_record where id=?",payment));
            assertEquals(0,intOf("select count(*) from payment_record where amount<0"));balanced();
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(orderPurchases,"mockWechatPay",true);}
    }
    @Test void legacyWholePaymentRefundCannotBypassCompositionAndReceiptCorruptionIsDetected() {
        seed();long id=order("whole-refund",1,1);long payment=pay(id,1);
        assertThrows(com.example.aquaflow.exception.BusinessException.class,() -> paymentService.refundPayment(payment,"旧整笔入口"));
        assertEquals(1,ledger.rightQty(customer,station,product));
        jdbc.update("update order_barrel_purchase set refunded_amount=1 where order_id=?",id);
        assertEquals(1,reconcile.runReconcileV2().get("E21_orderBarrelReceipt"));
        jdbc.update("update order_barrel_purchase set refunded_amount=0 where order_id=?",id);
        jdbc.update("update payment_record set barrel_deposit=51 where id=?",payment);
        assertEquals(1,reconcile.runReconcileV2().get("E21_orderBarrelReceipt"));
        jdbc.update("update payment_record set barrel_deposit=50 where id=?",payment);balanced();
    }
    @Test void cancellationKeepsNewCapacityAlreadyUsedByAnExistingPhysicalBucket() {
        seed();createCustomerStationConfig(customer,station,1);
        Api adjustment=post("/api/manager/adjustments",mgr,"{\"customerId\":"+customer+",\"adjustType\":\"OVER_ADJUST\",\"productId\":"+product+",\"qty\":1,\"reason\":\"补记已借的一桶\",\"clientToken\":\"existing-physical\"}");
        assertEquals(0,adjustment.code(),adjustment.toString());
        assertEquals(0,post("/api/manager/adjustments/"+adjustment.data().path("id").asLong()+"/execute",mgr,"{}").code());
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        long id=order("cover-existing-physical",1,1);long payment=pay(id,1);
        assertEquals(1,ledger.rightQty(customer,station,product));assertEquals(1,ledger.occupiedQty(customer,station,product));
        Api preview=get("/api/payments/"+payment+"/refund-preview",mgr);
        assertEquals(0,preview.code(),preview.toString());
        assertTrue(preview.data().path("scopes").get(0).path("label").asText().contains("¥20"),preview.toString());
        assertEquals(20,preview.data().path("refundAmount").asInt(),"已覆盖实物的新增押金不能计入取消预览");
        assertEquals(50,preview.data().path("retainedDeposit").asInt());
        assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());
        assertEquals(1,ledger.rightQty(customer,station,product),"新容量已覆盖实际持桶，不能随水单取消退出");
        assertEquals(1,ledger.occupiedQty(customer,station,product));
        assertEquals(20,intOf("select -sum(amount) from payment_record where order_id=? and amount<0",id));
        assertEquals(2,intOf("select status from payment_record where id=?",payment));balanced();
        reconcile.runReconcileV2().entrySet().stream().filter(e -> e.getKey().startsWith("E5_")).forEach(e -> assertEquals(0,e.getValue(),e.toString()));
    }
    @Test void concurrentWaterRefundUsesCurrentReceiptsAfterOrderLockWait() throws Exception {
        seed();long id=order("concurrent-refund",1,1);long payment=pay(id,1);deliver(id,false,0);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        List<Future<Api>> requests=new ArrayList<>();
        try (java.sql.Connection blocker=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,blocker.getTransactionIsolation());
            blocker.setAutoCommit(false);
            try (java.sql.PreparedStatement statement=blocker.prepareStatement("select id from orders where id=? for update")) {
                statement.setLong(1,id);try (var rows=statement.executeQuery()) {assertTrue(rows.next());}
            }
            try {
                for(int i=0;i<2;i++)requests.add(pool.submit(() -> put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedRefundAmount\":20}")));
                // 两个真实 HTTP 事务都已完成旧快照读且等待同一订单锁，才释放阻塞连接。
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);int waiting=0;
                while(System.nanoTime()<deadline) {
                    waiting=intOf("select count(distinct w.requesting_engine_transaction_id) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id and l.engine=w.engine where l.object_schema=database() and l.object_name='orders' and l.index_name='PRIMARY' and l.lock_data=?",String.valueOf(id));
                    if(waiting==2)break;Thread.sleep(25);
                }
                assertEquals(2,waiting,"必须观察到两个订单行锁等待，不能靠随机时序猜测并发");
            } finally {blocker.commit();}
            List<Api> results=new ArrayList<>();for(var request:requests)results.add(request.get(30,TimeUnit.SECONDS));
            assertEquals(20,intOf("select sum(water_amount) from consumption_refund where original_payment_id=?",payment),results.toString());
            assertEquals(1,results.stream().filter(r -> r.code()==0).count(),results.toString());
            assertEquals(1,results.stream().filter(r -> r.code()==1).count(),results.toString());
            assertEquals(20,intOf("select sum(water_amount) from consumption_refund where original_payment_id=?",payment));
            assertEquals(20,intOf("select -sum(amount) from payment_record where order_id=? and amount<0",id));
            assertEquals(2,intOf("select status from payment_record where id=?",payment));
            assertEquals(1,ledger.rightQty(customer,station,product));balanced();
        } finally {pool.shutdownNow();}
    }
    @org.junit.jupiter.api.RepeatedTest(3)
    void differentOriginalPaymentsCanRefundConcurrently() throws Exception {
        seed();long first=order("different-first",1,1);long p1=pay(first,1);deliver(first,false,0);
        // 先占用已有容量再明确另买一份，使第二笔同样保留押金而非整笔退款终结。
        long holding=order("holding",1,1);long second=order("different-second",1,1);long p2=pay(second,1);
        assertEquals(0,put("/api/orders/"+holding+"/customer-cancel",cus,null).code());deliver(second,false,0);
        ExecutorService pool=Executors.newFixedThreadPool(2);List<Future<Api>> requests=new ArrayList<>();
        try(java.sql.Connection blocker=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("select id from orders where id in (?,?) order by id for update")) {
                statement.setLong(1,first);statement.setLong(2,second);try(var rows=statement.executeQuery()){while(rows.next()) {}}
            }
            try {
                requests.add(pool.submit(() -> put("/api/payments/"+p1+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedRefundAmount\":20}")));
                requests.add(pool.submit(() -> put("/api/payments/"+p2+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedRefundAmount\":20}")));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);int waiting=0;
                while(System.nanoTime()<deadline) {
                    waiting=intOf("select count(distinct w.requesting_engine_transaction_id) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id and l.engine=w.engine where l.object_schema=database() and l.object_name='orders' and l.index_name='PRIMARY' and l.lock_data in (?,?)",String.valueOf(first),String.valueOf(second));
                    if(waiting==2)break;Thread.sleep(25);
                }
                assertEquals(2,waiting);
            } finally {blocker.commit();}
            for(var request:requests) {Api result=request.get(30,TimeUnit.SECONDS);assertEquals(0,result.code(),result.toString());}
            assertEquals(20,intOf("select sum(water_amount) from consumption_refund where original_payment_id=?",p1));
            assertEquals(20,intOf("select sum(water_amount) from consumption_refund where original_payment_id=?",p2));balanced();
        } finally {pool.shutdownNow();}
    }
    @Test void cancellationRejectsConfirmedAmountWhenOtherCapacityIsReleased() {
        seed();createCustomerStationConfig(customer,station,1);
        long id=order("cash-preview",1,2);long payment=pay(id,2);assertEquals(0,put("/api/payments/"+payment+"/confirm",mgr,null).code());
        ledger.releaseRights("ORDER",id);long holding=order("preview-holding",1,1);
        Api preview=get("/api/payments/"+payment+"/refund-preview",mgr);assertEquals(20,preview.data().path("refundAmount").asInt());
        assertEquals(0,put("/api/orders/"+holding+"/customer-cancel",cus,null).code());
        Api changed=put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"ALL_CONSUMPTION\",\"expectedRefundAmount\":20}");
        assertEquals(1,changed.code(),changed.toString());assertTrue(changed.body().path("message").asText().contains("重新"),changed.toString());
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and amount<0",id));
        assertEquals(1,intOf("select status from orders where id=?",id));assertEquals(1,ledger.rightQty(customer,station,product));
        Api missing=put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"ALL_CONSUMPTION\"}");assertEquals(1,missing.code(),missing.toString());
        Api latest=get("/api/payments/"+payment+"/refund-preview",mgr);assertEquals(70,latest.data().path("refundAmount").asInt());
        Api confirmed=put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"ALL_CONSUMPTION\",\"expectedRefundAmount\":70}");
        assertEquals(0,confirmed.code(),confirmed.toString());assertEquals(70,intOf("select -sum(amount) from payment_record where order_id=? and amount<0",id));balanced();
    }
    @Test void repeatedConsumptionRefundAndLastDepositReturnHaveConsistentLockOrder() throws Exception {
        seed();long id=order("last-deposit",1,1);long payment=pay(id,1);deliver(id,false,0);
        assertEquals(0,put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedRefundAmount\":20}").code());
        Api request=post("/api/barrels/return",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"pickupMode\":\"STORE\",\"idempotencyKey\":\"last-return\"}");
        assertEquals(0,request.code(),request.toString());long record=request.data().path("recordId").asLong();
        assertEquals(0,put("/api/barrels/records/"+record+"/approve",mgr,"{\"pickupFee\":0}").code());
        assertEquals(0,put("/api/barrels/records/"+record+"/customer-confirm",cus,"{}").code());
        assertEquals(0,put("/api/barrels/records/"+record+"/status",mgr,"{\"status\":2}").code());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(java.sql.Connection blocker=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("select id from payment_record where id=? for update")) {
                statement.setLong(1,payment);try(var rows=statement.executeQuery()){assertTrue(rows.next());}
            }
            Future<Api> deposit=pool.submit(() -> put("/api/barrels/records/"+record+"/status",mgr,"{\"status\":3,\"refundChannel\":\"ONLINE\"}"));
            Future<Api> repeated;
            try {
                awaitRefundLockWaiters(1,"payment_record");
                repeated=pool.submit(() -> put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedRefundAmount\":20}"));
                awaitRefundLockWaiters(2,null);
            } finally {blocker.commit();}
            Api returned=deposit.get(30,TimeUnit.SECONDS),duplicate=repeated.get(30,TimeUnit.SECONDS);
            assertEquals(0,returned.code(),returned.toString());assertEquals(1,duplicate.code(),duplicate.toString());
            assertEquals(70,intOf("select -sum(amount) from payment_record where order_id=? and amount<0",id));
            assertEquals(3,intOf("select status from payment_record where id=?",payment));assertEquals(0,ledger.rightQty(customer,station,product));balanced();
        } finally {pool.shutdownNow();}
    }
    private void awaitRefundLockWaiters(int expected,String table) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);int waiting=0;
        while(System.nanoTime()<deadline) {
            waiting=intOf("select count(distinct w.requesting_engine_transaction_id) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id and l.engine=w.engine where l.object_schema=database() and l.object_name in ('orders','payment_record')"+(table==null?"":" and l.object_name='"+table+"'"));
            if(waiting==expected)break;Thread.sleep(25);
        }
        assertEquals(expected,waiting,"必须观察到退款事务的确定锁等待才放行");
    }
}
