package com.example.aquaflow.integration;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@TestPropertySource(properties={"app.payment.mock-wechat-pay=false"})
class CollectionCompletionIntegrationTest extends AbstractIntegrationTest {
 long station,customer,order,payment;String owner,manager,foreign;
 void seed(int status){
  station=createStation("合成收款站");long other=createStation("合成他站");
  customer=createCustomer("合成收款顾客","synthetic-collection");owner=customerToken(customer);
  manager=staffToken(createStaff("合成站长","STATION_MANAGER",station,1),"STATION_MANAGER",station);
  foreign=staffToken(createStaff("合成他站长","STATION_MANAGER",other,1),"STATION_MANAGER",other);
  createCustomerStationConfig(customer,station,1);long address=createAddress(customer,"合成地址"),product=createProduct("合成饮水",1,"20","0",0,"0");
  order=createOrderFull(customer,address,station,product,status,1,2,"20","0","20",false,0);
  jdbc.update("update orders set due_date=curdate(),settlement_status=1 where id=?",order);
  var p=post("/api/payments",owner,"{\"orderId\":"+order+",\"paymentMethod\":2}");assertEquals(0,p.code(),p.toString());payment=p.data().path("id").asLong();assertTrue(payment>0);
 }
 Api confirm(String who){return put("/api/payments/"+payment+"/cash-confirm",who,null);}
 Api settle(){return post("/api/manager/receivables/settle",manager,"{\"customerId\":"+customer+",\"orderIds\":["+order+"]}");}
 int status(){return intOf("select status from orders where id=?",order);}
 void once(){assertEquals(2,intOf("select payment_status from orders where id=?",order));assertEquals(1,intOf("select count(*) from payment_record where order_id=? and status=2",order));}
 @Test void pendingCashConfirmationCompletesOnlyDeliveredOrder(){seed(3);assertNotEquals(0,confirm(foreign).code());assertEquals(3,status());assertEquals(0,confirm(manager).code());assertEquals(4,status());once();assertNotEquals(0,confirm(manager).code());once();}
 @Test void receivableSettlementCompletesDeliveredOrderAndReplaysSafely(){seed(3);assertEquals(0,settle().code());assertEquals(4,status());once();assertEquals(0,settle().code());assertEquals(4,status());once();}
 @Test void advanceCollectionDoesNotPretendDeliveryCompleted(){seed(1);assertEquals(0,confirm(manager).code());assertEquals(1,status());once();assertEquals(0,settle().code());assertEquals(1,status());once();}
 @Test void settlementBeforeDeliveryDoesNotCompleteOrder(){seed(2);assertEquals(0,settle().code());assertEquals(2,status());once();}
 @Test void completionFailureRollsBackPaidReceiptAndOrderPayment(){
  seed(3);jdbc.execute("CREATE TRIGGER argon_completion_fail BEFORE UPDATE ON orders FOR EACH ROW BEGIN IF OLD.status=3 AND NEW.status=4 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic completion failure'; END IF; END");
  try{assertNotEquals(0,confirm(manager).code());assertEquals(3,status());assertEquals(1,intOf("select payment_status from orders where id=?",order));assertEquals(1,intOf("select status from payment_record where id=?",payment));assertEquals(0,intOf("select count(*) from payment_record where order_id=? and status=2",order));}
  finally{jdbc.execute("DROP TRIGGER IF EXISTS argon_completion_fail");}
 }
 @Test void simultaneousReceiptConfirmationAndSettlementNeverDoubleCollect()throws Exception{
  seed(3);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{Future<Api>a=pool.submit(()->{start.await();return confirm(manager);});Future<Api>b=pool.submit(()->{start.await();return settle();});start.countDown();Api x=a.get(20,TimeUnit.SECONDS),y=b.get(20,TimeUnit.SECONDS);assertTrue(x.code()==0||y.code()==0);assertEquals(4,status());once();assertEquals(2,intOf("select settlement_status from orders where id=?",order));}
  finally{pool.shutdownNow();}
 }
 @Test void stationChangeWhileConfirmationWaitsMustRecheckCollectionAuthority() throws Exception {
  seed(1);
  long target=createStation("迁入收款站");
  String targetManager=staffToken(createStaff("迁入站长","STATION_MANAGER",target,1),"STATION_MANAGER",target);
  ExecutorService pool=Executors.newSingleThreadExecutor();
  try(var blocker=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
   blocker.setAutoCommit(false);
   try(var update=blocker.prepareStatement("update orders set delivery_station_id=?,settle_station_id=? where id=?")) {
    update.setLong(1,target);update.setLong(2,target);update.setLong(3,order);assertEquals(1,update.executeUpdate());
   }
   try(var update=blocker.prepareStatement("update payment_record set station_id=? where id=? and status=1")) {
    update.setLong(1,target);update.setLong(2,payment);assertEquals(1,update.executeUpdate());
   }
   Future<Api> pending=pool.submit(()->confirm(manager));
   try {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);int waiting=0;
    while(System.nanoTime()<deadline) {
     waiting=intOf("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id and l.engine=w.engine where l.object_schema=database() and l.object_name='orders' and l.index_name='PRIMARY'");
     if(waiting>0)break;Thread.sleep(25);
    }
    assertTrue(waiting>0,"confirm must pass old controller check and wait for current order");
   } finally {blocker.commit();}
   Api rejected=pending.get(20,TimeUnit.SECONDS);assertEquals(1,rejected.code(),rejected.toString());
   assertEquals(1,intOf("select status from payment_record where id=?",payment));
   assertEquals(1,intOf("select payment_status from orders where id=?",order));
   assertEquals(0,confirm(targetManager).code());once();assertEquals(1,status());
  } finally {pool.shutdownNow();}
 }
}
