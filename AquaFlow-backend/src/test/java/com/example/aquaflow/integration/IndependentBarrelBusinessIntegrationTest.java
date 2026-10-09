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

/** 新模型走真实 HTTP/MySQL，检查客户钱、票、实物和占用后果。旧模型另保留回归。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.mock-wechat-pay=true"})
class IndependentBarrelBusinessIntegrationTest extends AbstractIntegrationTest {
    @Autowired BarrelLedgerService ledger;
    @Autowired ReconciliationService reconcile;
    @Autowired BusinessWaitingService waiting;
    long station,manager,customer,product,address;
    String cus,mgr;
    void seed() {
        station=createStation("新权益站"); manager=createStaff("站长","STATION_MANAGER",station,1);
        customer=createCustomer("客户","independent-customer"); product=createProduct("桶装水",1,"20.00","30.00",1,"8.00");
        createInventoryFull(station,product,20,1,"8.00"); address=createAddress(customer,"一楼");
        createInventoryRecord(station,product,20,"STOCK_IN",0);
        cus=customerToken(customer); mgr=staffToken(manager,"STATION_MANAGER",station);
    }
    String purchaseBody(int q,String key) { return "{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":"+q+",\"paymentMethod\":2,\"idempotencyKey\":\""+key+"\"}"; }
    long buy(int qty,String key) {
        Api p=post("/api/barrel-rights/purchase",cus,purchaseBody(qty,key)); assertEquals(0,p.code(),p.toString());
        long payment=p.data().path("paymentId").asLong(); Api confirmed=put("/api/payments/"+payment+"/confirm",mgr,null); assertEquals(0,confirmed.code(),confirmed.toString()); return payment;
    }
    String orderBody(String key,int qty) { return "{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"idempotencyKey\":\""+key+"\",\"items\":[{\"productId\":"+product+",\"quantity\":"+qty+"}]}"; }
    long order(String key,int qty) { Api r=post("/api/orders/create",cus,orderBody(key,qty)); assertEquals(0,r.code(),r.toString()); long id=r.data().path("orderId").asLong(); assertTrue(id>0,r.toString());return id; }
    long request(int qty,String key) {
        Api r=post("/api/barrels/return",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":"+qty+",\"pickupMode\":\"STORE\",\"idempotencyKey\":\""+key+"\"}");
        assertEquals(0,r.code(),r.toString());return r.data().path("recordId").asLong();
    }
    Api status(long id,int status) { return put("/api/barrels/records/"+id+"/status",mgr,"{\"status\":"+status+",\"refundChannel\":\"CASH\"}"); }
    @Test void paidRightIsUsableWithoutDeliveryButDoesNotInventPhysicalBuckets() {
        seed(); Api first=post("/api/barrel-rights/purchase",cus,purchaseBody(1,"one")); assertEquals(0,first.code(),first.toString());
        Api pending=get("/api/payments/pending",mgr);assertEquals(0,pending.code(),pending.toString());
        assertEquals("独立桶押金",pending.data().get(0).path("purposeText").asText());
        assertEquals(0,ledger.rightQty(customer,station,product));
        assertEquals(1,post("/api/tickets/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":3,\"paymentMethod\":2,\"idempotencyKey\":\"no-right\"}").code());
        long pay=first.data().path("paymentId").asLong(); assertEquals(0,put("/api/payments/"+pay+"/confirm",mgr,null).code());
        assertEquals(1,ledger.rightQty(customer,station,product));assertEquals(0,ledger.occupiedQty(customer,station,product));
        Api asset=get("/api/customers/"+customer+"/assets",mgr);assertEquals(0,asset.code(),asset.toString());
        assertEquals(1,asset.data().path("rightBuckets").asInt());assertEquals(0,asset.data().path("actualBuckets").asInt());
        Api replay=post("/api/barrel-rights/purchase",cus,purchaseBody(1,"one"));assertEquals(pay,replay.data().path("paymentId").asLong());
        assertEquals(1,intOf("select count(*) from customer_barrel_lot"));
        assertEquals(0,reconcile.runReconcile().values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test void orderReservesRightsAndCancellationReleasesWithoutRefundingIndependentDeposit() {
        seed(); buy(1,"one"); long id=order("order-one",1);
        assertEquals(0,ledger.availableRights(customer,station,product));
        assertEquals(1,post("/api/orders/create",cus,orderBody("second",1)).code());
        assertEquals(1,post("/api/barrels/return",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"idempotencyKey\":\"return-busy\"}").code());
        assertEquals(0,put("/api/orders/"+id+"/customer-cancel",cus,null).code());
        assertEquals(1,ledger.availableRights(customer,station,product));assertEquals(new BigDecimal("30.00"),jdbc.queryForObject("select balance from customer_deposit_account where customer_id=? and station_id=?",BigDecimal.class,customer,station));
    }
    @Test void freeUnusedRightReturnRequiresApprovalAndHandoverButNoSecondCustomerConfirmation() {
        seed();buy(1,"one");long r=request(1,"return-one");
        assertEquals(1,status(r,2).code());assertEquals(1,status(r,3).code());
        assertEquals(0,put("/api/barrels/records/"+r+"/approve",mgr,"{\"pickupFee\":0}").code());
        assertEquals(1,status(r,3).code(),"批准不等于实际交接，不能跳到退款");
        assertNull(jdbc.queryForObject("select customer_confirmed_time from barrel_return_detail where record_id=?",java.sql.Timestamp.class,r));
        assertEquals(0,status(r,2).code());assertEquals(1,ledger.rightQty(customer,station,product));
        assertEquals(0,status(r,3).code());assertEquals(0,ledger.rightQty(customer,station,product));assertEquals(0,ledger.occupiedQty(customer,station,product));
        assertEquals(1,status(r,3).code());assertEquals(0,reconcile.runReconcile().values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test void parallelOrdersCannotReuseSingleRight() throws Exception {
        seed();buy(1,"one"); ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> a=pool.submit(()->{start.await();return post("/api/orders/create",cus,orderBody("parallel-a",1));});
            Future<Api> b=pool.submit(()->{start.await();return post("/api/orders/create",cus,orderBody("parallel-b",1));});start.countDown();
            List<Api> rs=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,rs.stream().filter(r->r.code()==0).count(),rs.toString());assertTrue(rs.stream().allMatch(r->r.code()==0 || r.code()==1),rs.toString());
            assertEquals(1,intOf("select coalesce(sum(quantity),0) from barrel_right_reservation where status='ACTIVE'"));
        } finally {pool.shutdownNow();}
    }
    @Test void expiredUnpaidNewOrderReleasesRightsButKeepsPaidDeposit() {
        seed();buy(1,"one");long id=order("expires",1);jdbc.update("update orders set create_time=date_sub(now(),interval 20 minute) where id=?",id);
        waiting.expire(id); assertEquals(5,intOf("select status from orders where id=?",id));assertEquals(1,ledger.availableRights(customer,station,product));assertEquals(1,ledger.rightQty(customer,station,product));
    }
    @Test void waterRefundDoesNotEndBucketRightsAndCanLaterRefundServiceFees() {
        seed();buy(1,"one");long id=createOrderFull(customer,address,station,product,4,2,2,"20.00","0.00","25.00",false,1);
        jdbc.update("update orders set delivery_fee=5,settle_station_id=? where id=?",station,id);
        long p=insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount,barrel_deposit,delivery_fee) values(?,?,?,2,2,25,20,0,5)",id,customer,station);
        Api r=put("/api/payments/"+p+"/refund",mgr,"{\"scope\":\"WATER\",\"note\":\"水质问题退水费\"}");assertEquals(0,r.code(),r.toString());
        assertEquals(1,ledger.rightQty(customer,station,product));assertEquals(2,intOf("select payment_status from orders where id=?",id));
        assertEquals(new BigDecimal("-20.00"),jdbc.queryForObject("select amount from payment_record where order_id=? and amount<0",BigDecimal.class,id));
        assertEquals(1,put("/api/payments/"+p+"/refund",mgr,"{\"scope\":\"WATER\"}").code());
        assertEquals(0,put("/api/payments/"+p+"/refund",mgr,"{\"scope\":\"SERVICE\"}").code());assertEquals(3,intOf("select payment_status from orders where id=?",id));
    }
    long pay(long order,int method) {
        Api r=post("/api/payments",cus,"{\"orderId\":"+order+",\"paymentMethod\":"+method+"}");assertEquals(0,r.code(),r.toString());return r.data().path("id").asLong();
    }
    void deliver(long order,String token,int expected,int actual,boolean collected) {
        Api accept=post("/api/delivery/orders/"+order+"/accept",token,"{}");assertEquals(0,accept.code(),accept.toString());
        long item=jdbc.queryForObject("select id from order_item where order_id=? limit 1",Long.class,order);
        String reasons=expected>actual?"[{\"key\":\"customer_kept\",\"qty\":"+(expected-actual)+"}]":"[]";
        Api done=post("/api/delivery/orders/"+order+"/complete",token,"{\"collected\":"+collected+",\"itemReturns\":[{\"orderItemId\":"+item+",\"expected\":"+expected+",\"actual\":"+actual+",\"reasons\":"+reasons+"}]}");assertEquals(0,done.code(),done.toString());
    }
    void assertBalanced() {
        Map<String,Integer> result=reconcile.runReconcile();assertEquals(0,result.values().stream().mapToInt(Integer::intValue).sum(),result.toString());
        Map<String,Integer> v2=reconcile.runReconcileV2();
        v2.entrySet().stream().filter(e->e.getKey().matches("E(17|18|19|20)_.*")).forEach(e->assertEquals(0,e.getValue(),e.toString()));
    }
    @Test void twoExistingBucketsAndOneNewRightReturnTwoAndCanceledWaterKeepsAllDeposits() {
        seed();buy(2,"two-old");long first=order("first-two",2);pay(first,1);deliver(first,mgr,0,0,false);
        buy(1,"one-new");assertEquals(2,ledger.occupiedQty(customer,station,product));
        long next=order("two-old-one-new",3);long paid=pay(next,1);
        assertEquals(1,intOf("select pickup_qty from barrel_right_reservation where owner_id=? and owner_type='ORDER'",next));
        Api preview=get("/api/barrels/return/preview?stationId="+station+"&productId="+product+"&quantity=1",cus);assertEquals(0,preview.code(),preview.toString());assertEquals(1,preview.data().path("requiredBarrels").asInt());
        assertEquals(0,put("/api/payments/"+paid+"/refund",mgr,"{\"scope\":\"WATER\"}").code());
        assertEquals(5,intOf("select status from orders where id=?",next));assertEquals(3,ledger.availableRights(customer,station,product));
        assertEquals(new BigDecimal("90.00"),jdbc.queryForObject("select balance from customer_deposit_account where customer_id=? and station_id=?",BigDecimal.class,customer,station));
        long retry=order("actual-two-old-one-new",3);pay(retry,1);deliver(retry,mgr,2,2,false);
        assertEquals(3,ledger.occupiedQty(customer,station,product));assertEquals(0,intOf("select count(*) from order_barrel_exception"));assertBalanced();
    }
    long refusal(long order,String managerToken) {
        Api ex=post("/api/manager/exceptions?orderId="+order,managerToken,"{\"category\":\"CUSTOMER_REFUSE\",\"type\":\"DELIVERY_PROBLEM\",\"staffNote\":\"客户已收水，明确拒付\"}");assertEquals(0,ex.code(),ex.toString());
        long id=ex.data().path("id").asLong();Api handled=post("/api/manager/exceptions/"+id+"/write-off",managerToken,"{\"managerNote\":\"已核实客户拒付\"}");assertEquals(0,handled.code(),handled.toString());return id;
    }
    @Test void firstPhysicalDeliveryNeedsNoEmptyBucketAndDoesNotCreateFalseException() {
        seed();buy(1,"first-right");long id=order("first-physical",1);pay(id,1);deliver(id,mgr,0,0,false);
        assertEquals(1,ledger.occupiedQty(customer,station,product));assertEquals(1,ledger.availableRights(customer,station,product));
        assertEquals(0,intOf("select count(*) from order_barrel_exception"));
        long r=request(1,"physical-return");assertEquals(1,intOf("select required_barrels from barrel_return_detail where record_id=?",r));
        assertEquals(0,put("/api/barrels/records/"+r+"/approve",mgr,"{\"pickupFee\":0}").code());
        assertEquals(0,status(r,2).code());assertEquals(0,ledger.occupiedQty(customer,station,product));assertEquals(1,status(r,2).code());assertEquals(0,status(r,3).code());assertBalanced();
    }
    @Test void rightsAreIsolatedByStationAndProductAndCashIntentCanBeWithdrawn() {
        seed();buy(1,"isolated");long other=createProduct("另一桶型",1,"18.00","40.00",1,"7.00");createInventoryFull(station,other,10,1,"7.00");
        assertEquals(1,post("/api/orders/create",cus,orderBody("wrong-product",1).replace("\"productId\":"+product,"\"productId\":"+other)).code());
        long b=createStation("另一站");createInventoryFull(b,product,10,1,"8.00");assertEquals(1,post("/api/orders/create",cus,orderBody("wrong-station",1).replace("\"stationId\":"+station,"\"stationId\":"+b)).code());
        Api pending=post("/api/barrel-rights/purchase",cus,purchaseBody(1,"withdraw"));assertEquals(0,pending.code(),pending.toString());long purchase=pending.data().path("purchase").path("id").asLong();
        assertEquals(0,put("/api/barrel-rights/"+purchase+"/withdraw",cus,"{}").code());assertEquals(1,put("/api/payments/"+pending.data().path("paymentId").asLong()+"/confirm",mgr,null).code());assertEquals(1,ledger.rightQty(customer,station,product));
    }
    @Test void paidPickupFeeCanBeRefundedIndependentlyBeforeWithdrawal() {
        seed();buy(1,"right");long id=order("water",1);pay(id,1);deliver(id,mgr,0,0,false);
        Api r=post("/api/barrels/return",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"pickupMode\":\"PICKUP\",\"idempotencyKey\":\"pickup\"}");assertEquals(0,r.code(),r.toString());long record=r.data().path("recordId").asLong();
        assertEquals(0,put("/api/barrels/records/"+record+"/approve",mgr,"{\"pickupFee\":5}").code());
        assertEquals(1,status(record,2).code(),"新增收费仍须客户授权");
        assertEquals(0,put("/api/barrels/records/"+record+"/customer-confirm",cus,"{}").code());assertEquals(1,status(record,2).code(),"授权不等于已收到服务费");
        long fee=jdbc.queryForObject("select fee_payment_id from barrel_return_detail where record_id=?",Long.class,record);
        Api pending=get("/api/payments/pending",mgr);assertEquals(0,pending.code(),pending.toString());
        assertEquals("上门收桶费",pending.data().get(0).path("purposeText").asText());
        assertEquals(0,put("/api/payments/"+fee+"/confirm",mgr,null).code());
        assertEquals(1,put("/api/barrels/records/"+record+"/withdraw",cus,"{}").code());assertEquals(0,put("/api/payments/"+fee+"/refund",mgr,"{\"scope\":\"SERVICE\",\"note\":\"尚未上门，实际退费\"}").code());
        assertEquals(0,put("/api/barrels/records/"+record+"/withdraw",cus,"{}").code());assertEquals(1,ledger.occupiedQty(customer,station,product));assertEquals(1,ledger.availableRights(customer,station,product));assertBalanced();
    }
    @Test void refusalKeepsReceivableAndDepositThenOldDebtCanBeCollected() {
        seed();buy(2,"right");jdbc.update("update customer_station_config set offline_payment_enabled=1 where customer_id=? and station_id=?",customer,station);
        Api made=post("/api/orders/create",cus,orderBody("cash-debt",1).replace("\"paymentMethod\":1","\"paymentMethod\":2"));assertEquals(0,made.code(),made.toString());long debt=made.data().path("orderId").asLong();deliver(debt,mgr,0,0,false);refusal(debt,mgr);
        assertEquals(1,intOf("select payment_status from orders where id=?",debt));assertEquals(0,intOf("select offline_payment_enabled from customer_station_config where customer_id=? and station_id=?",customer,station));assertEquals(2,ledger.rightQty(customer,station,product));
        assertEquals(new BigDecimal("60.00"),jdbc.queryForObject("select balance from customer_deposit_account where customer_id=? and station_id=?",BigDecimal.class,customer,station));
        long next=order("new-order",1);assertEquals(1,post("/api/payments",cus,"{\"orderId\":"+next+",\"paymentMethod\":1}").code());
        Api p=post("/api/payments",mgr,"{\"orderId\":"+debt+",\"paymentMethod\":2}");assertEquals(0,p.code(),p.toString());assertEquals(0,put("/api/payments/"+p.data().path("id").asLong()+"/confirm",mgr,null).code());
        pay(next,1);assertEquals(0,intOf("select offline_payment_enabled from customer_station_config where customer_id=? and station_id=?",customer,station));
        assertBalanced();
    }
    @Test void unusedPurchasedTicketsRefundOnlyTheRemainingRealBatch() {
        seed();buy(1,"right");Api bought=post("/api/tickets/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":3,\"paymentMethod\":2,\"idempotencyKey\":\"tickets\"}");assertEquals(0,bought.code(),bought.toString());
        long payment=bought.data().path("paymentId").asLong();assertTrue(payment>0,bought.toString());assertEquals(0,put("/api/payments/"+payment+"/confirm",mgr,null).code());
        Api o=post("/api/orders/create",cus,orderBody("ticket-order",1).replace("\"paymentMethod\":1","\"paymentMethod\":3"));assertEquals(0,o.code(),o.toString());long order=o.data().path("orderId").asLong();pay(order,3);deliver(order,mgr,0,0,false);
        Api batches=get("/api/manager/ticket-exit-batches",mgr);assertEquals(0,batches.code(),batches.toString());assertEquals(2,batches.data().get(0).path("remainingQty").asInt());
        assertEquals(1,put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedTicketQty\":3,\"expectedTicketAmount\":24}").code());assertEquals(2,intOf("select sum(remain_qty) from ticket_lot"));
        Api refund=put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedTicketQty\":2,\"expectedTicketAmount\":16}");assertEquals(0,refund.code(),refund.toString());
        assertEquals(2,intOf("select quantity from ticket_exit_refund where original_payment_id=?",payment));assertEquals(0,intOf("select sum(remain_qty) from ticket_lot"));assertEquals(1,put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\"}").code());assertBalanced();
    }
    @Test void crossStationRefusalRequiresOriginReviewBeforeFreezingItsDeposit() {
        seed();buy(1,"asset-at-a");
        jdbc.update("update customer_station_config set offline_payment_enabled=1 where customer_id=? and station_id=?",customer,station);
        Api created=post("/api/orders/create",cus,orderBody("cross-cash",1).replace("\"paymentMethod\":1","\"paymentMethod\":2"));assertEquals(0,created.code(),created.toString());long id=created.data().path("orderId").asLong();
        long b=createStation("债权履约站");long bm=createStaff("乙站长","STATION_MANAGER",b,1);String bt=staffToken(bm,"STATION_MANAGER",b);createInventoryFull(b,product,10,1,"8.00");createInventoryRecord(b,product,10,"STOCK_IN",0);
        assertEquals(0,post("/api/delivery/orders/"+id+"/dispatch",mgr,"{\"targetStationId\":"+b+",\"riskAcknowledged\":true}").code());
        // 接收站沿既有风险确认入口分配给站长本人，再进行原拒付/资产站核实场景。
        assertEquals(0,post("/api/delivery/orders/assign/"+id,bt,"{\"deliveryStaffId\":"+bm+",\"riskAcknowledged\":true}").code());
        deliver(id,bt,0,0,false);refusal(id,bt);
        assertEquals(0,intOf("select asset_freeze_confirmed from customer_refusal_case where order_id=?",id));
        assertEquals(1,put("/api/manager/refusal-cases/"+id+"/confirm-freeze",bt,"{}").code());
        assertEquals(0,put("/api/manager/refusal-cases/"+id+"/confirm-freeze",mgr,"{}").code());
        assertEquals(1,post("/api/barrels/return",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"idempotencyKey\":\"frozen-return\"}").code());
        assertEquals(new BigDecimal("30.00"),jdbc.queryForObject("select balance from customer_deposit_account where customer_id=? and station_id=?",BigDecimal.class,customer,station));
        Api collect=post("/api/payments",bt,"{\"orderId\":"+id+",\"paymentMethod\":2}");assertEquals(0,collect.code(),collect.toString());assertEquals(0,put("/api/payments/"+collect.data().path("id").asLong()+"/confirm",bt,null).code());
        assertEquals(0,post("/api/barrels/return",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":1,\"idempotencyKey\":\"released-return\"}").code());assertBalanced();
    }
    @Test void crossStationQuoteFreezesOnAcceptanceAndAssetsStayAtOrigin() {
        seed();buy(1,"right");long id=order("cross",1);pay(id,1);long b=createStation("履约站");long bm=createStaff("乙站长","STATION_MANAGER",b,1);String bt=staffToken(bm,"STATION_MANAGER",b);createInventoryFull(b,product,10,1,"8.00");createInventoryRecord(b,product,10,"STOCK_IN",0);
        Api sent=post("/api/delivery/orders/"+id+"/dispatch",mgr,"{\"targetStationId\":"+b+",\"riskAcknowledged\":true}");assertEquals(0,sent.code(),sent.toString());
        Api view=get("/api/manager/dispatch-agreements/"+id,bt);assertEquals(0,view.code(),view.toString());assertEquals(0,new BigDecimal("20.00").compareTo(new BigDecimal(view.data().path("serviceAmount").asText())));
        assertEquals(0,put("/api/manager/dispatch-agreements/"+id,mgr,"{\"serviceAmount\":28,\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0,\"note\":\"加价外包\"}").code());
        long ds=createStaff("乙配送","DELIVERY",b,1);Api assigned=post("/api/delivery/orders/assign/"+id,bt,"{\"deliveryStaffId\":"+ds+",\"riskAcknowledged\":true}");assertEquals(0,assigned.code(),assigned.toString());
        assertEquals(1,put("/api/manager/dispatch-agreements/"+id,mgr,"{\"serviceAmount\":99,\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0}").code());
        deliver(id,staffToken(ds,"DELIVERY",b),0,0,false);assertEquals(1,ledger.occupiedQty(customer,station,product));assertEquals(0,ledger.rightQty(customer,b,product));assertEquals(b,intOf("select settle_station_id from orders where id=?",id));
        assertEquals(0,post("/api/manager/station-barrel-balances/"+id+"/received",mgr,"{\"note\":\"甲实际交空桶\"}").code());assertEquals("ACCEPTED",jdbc.queryForObject("select status from dispatch_agreement where order_id=?",String.class,id));
        assertEquals(0,post("/api/manager/station-barrel-balances/"+id+"/received",bt,"{\"note\":\"乙实际收到空桶\"}").code());assertEquals("BARREL_CLOSED",jdbc.queryForObject("select status from dispatch_agreement where order_id=?",String.class,id));assertBalanced();
    }
    @Test void settledMoneyReversalKeepsRecoveryUntilActualTwoStationHandover() {
        seed();long b=createStation("结算乙站"),bm=createStaff("乙站长","STATION_MANAGER",b,1);String bt=staffToken(bm,"STATION_MANAGER",b);
        long order=createOrderFull(customer,address,station,product,4,2,1,"20.00","0.00","20.00",false,1);jdbc.update("update orders set delivery_station_id=?,settle_station_id=? where id=?",b,b,order);createPaymentRecord(order,customer,station,"20.00",1,2);
        assertEquals(0,post("/api/manager/inter-station-settlements/"+order+"/settle",mgr,"{\"note\":\"甲已付乙20\"}").code());assertEquals(1,post("/api/manager/inter-station-settlements/"+order+"/reverse",mgr,"{}").code());
        jdbc.update("update orders set payment_status=3 where id=?",order);assertEquals(0,post("/api/manager/inter-station-settlements/"+order+"/reverse",mgr,"{}").code());
        assertEquals(1,post("/api/manager/inter-station-recoveries/"+order+"/received",mgr,"{}").code());assertEquals(1,post("/api/manager/inter-station-recoveries/"+order+"/sent",mgr,"{\"note\":\"越权\"}").code());
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+order+"/sent",bt,"{\"note\":\"乙已实际返还20\"}").code());assertEquals(0,post("/api/manager/inter-station-recoveries/"+order+"/received",mgr,"{}").code());assertEquals("RECEIVED",jdbc.queryForObject("select status from inter_station_recovery where order_id=?",String.class,order));
    }
    @Test void physicalLossRecordsRealStockAndPreservesAffectedDemand() {
        seed();buy(2,"rights");long a=order("older",1),b=order("newer",1);
        jdbc.update("update orders set create_time=date_sub(now(),interval 1 minute) where id=?",a);
        jdbc.update("update inventory_reservation set need_time=(select create_time from orders where id=?) where order_id=?",a,a);
        Api loss=post("/api/inventory/"+product+"/loss",mgr,"{\"targetQuantity\":1,\"expectedQuantity\":20,\"note\":\"盘点发现仓库破损，逐桶核实\"}");assertEquals(0,loss.code(),loss.toString());
        assertEquals(1,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));assertEquals(1,intOf("select reserved_qty from inventory_reservation where order_id=? and status=1",a));assertEquals(0,intOf("select reserved_qty from inventory_reservation where order_id=? and status=1",b));
        assertEquals(1,intOf("select need_qty from inventory_reservation where order_id=? and status=1",b));assertEquals(1,post("/api/inventory/"+product+"/loss",mgr,"{\"targetQuantity\":0,\"expectedQuantity\":20,\"note\":\"过期盘点请求\"}").code());assertBalanced();
    }
    @Test void actualCrossStationBarrelDifferenceRequiresBothStationsBeforeClosure() {
        seed();buy(1,"right");long first=order("first",1);pay(first,1);deliver(first,mgr,0,0,false);
        long id=order("cross-short-return",1);pay(id,1);long b=createStation("乙桶站"),bm=createStaff("乙站长","STATION_MANAGER",b,1),ds=createStaff("乙配送","DELIVERY",b,1);String bt=staffToken(bm,"STATION_MANAGER",b);createInventoryFull(b,product,10,1,"8.00");createInventoryRecord(b,product,10,"STOCK_IN",0);
        assertEquals(0,post("/api/delivery/orders/"+id+"/dispatch",mgr,"{\"targetStationId\":"+b+",\"riskAcknowledged\":true}").code());assertEquals(0,post("/api/delivery/orders/assign/"+id,bt,"{\"deliveryStaffId\":"+ds+",\"riskAcknowledged\":true}").code());deliver(id,staffToken(ds,"DELIVERY",b),1,0,false);
        assertEquals(1,intOf("select barrel_disputed from dispatch_agreement where order_id=?",id));assertEquals(1,intOf("select actual_net_barrels from dispatch_agreement where order_id=?",id));assertEquals(1,post("/api/manager/station-barrel-balances/"+id+"/received",bt,"{\"note\":\"未协商不能结清\"}").code());
        String proposal="{\"barrelMode\":\"SETTLE_BARREL\",\"barrelAmount\":30,\"note\":\"旧桶客户未交回，甲对乙补桶折款，客户欠桶仍保留\"}";
        assertEquals(1,put("/api/manager/station-barrel-balances/"+id+"/proposal",bt,proposal).code());assertEquals(0,put("/api/manager/station-barrel-balances/"+id+"/proposal",mgr,proposal).code());assertEquals(1,post("/api/manager/station-barrel-balances/"+id+"/agree",mgr,"{}").code());assertEquals(0,post("/api/manager/station-barrel-balances/"+id+"/agree",bt,"{}").code());
        assertEquals(0,post("/api/manager/station-barrel-balances/"+id+"/received",mgr,"{\"note\":\"甲已交付30元桶款\"}").code());assertEquals(0,post("/api/manager/station-barrel-balances/"+id+"/received",bt,"{\"note\":\"乙已收到30元桶款\"}").code());assertEquals(1,ledger.overQty(customer,station,product));assertEquals(1,ledger.rightQty(customer,station,product));assertBalanced();
    }
    @Test void legacyInflightOrderAlsoProtectsExistingRightsDuringCutover() {
        seed();buy(1,"right");long legacy=createOrderFull(customer,address,station,product,1,0,1,"20.00","0.00","20.00",false,1);
        createOrderItem(legacy,product,"历史桶装水",1,"20.00","0.00",0);
        assertEquals(1,post("/api/orders/create",cus,orderBody("new-while-old",1)).code());assertEquals(0,put("/api/orders/"+legacy+"/customer-cancel",cus,null).code());long next=order("after-old-cancel",1);assertTrue(next>0);assertEquals(1,ledger.rightQty(customer,station,product));
    }
    @Test void unknownActivePaymentMustNotBeGuessedUnpaidByTimeout() {
        seed();buy(1,"right");long id=order("unknown-channel",1);createPaymentRecord(id,customer,station,"20.00",1,1);jdbc.update("update orders set create_time=date_sub(now(),interval 20 minute) where id=?",id);
        waiting.expire(id);assertEquals(1,intOf("select status from orders where id=?",id));assertEquals(0,ledger.availableRights(customer,station,product));
    }
}
