package com.example.aquaflow.integration;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Unknown WeChat provenance remains protected; whole-component refunds retain atomicity. */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.test-mode=true"})
class ReviewLegacyPaymentAndRefundIntegrationTest extends AbstractIntegrationTest {
    long station,manager,customer,address,product;String cus,mgr;
    void seed(){station=createStation("Legacy review");manager=createStaff("Manager","STATION_MANAGER",station,1);customer=createCustomer("Legacy customer","legacy-review");address=createAddress(customer,"Legacy address");product=createProduct("Bottle",2,"20","0",0,"0");createInventoryFull(station,product,20,0,"0");cus=customerToken(customer);mgr=staffToken(manager,"STATION_MANAGER",station);}
    @Test void unknownPaidWechatCannotBeFakedAsRefundButCanStillBeDelivered(){
        seed();long order=createOrderFull(customer,address,station,product,1,2,1,"20","0","20",false,1);
        createReservedItem(order,product,"Bottle",1,"20","0");long payment=createPaymentRecord(order,customer,station,"20",1,2);
        jdbc.update("update payment_record set water_amount=20,barrel_deposit=0,transaction_no='LEGACY-UNVERIFIED' where id=?",payment);
        Api canceled=put("/api/orders/"+order+"/customer-cancel",cus,null);assertEquals(1,canceled.code(),canceled.toString());
        assertEquals(1,intOf("select status from orders where id=?",order));assertEquals(2,intOf("select status from payment_record where id=?",payment));
        assertEquals(0,intOf("select count(*) from payment_record where order_id=? and amount<0",order));assertEquals(1,intOf("select count(*) from inventory_reservation where order_id=? and status=1",order));
        assertEquals(0,post("/api/delivery/orders/"+order+"/accept",mgr,"{}").code());
        Api delivered=post("/api/delivery/orders/"+order+"/complete",mgr,"{}");assertEquals(0,delivered.code(),delivered.toString());assertEquals(4,intOf("select status from orders where id=?",order));
    }
    @Test void unknownPendingWechatIsVisibleButCannotBeConfirmedAsSimulated(){
        seed();long order=createOrderFull(customer,address,station,product,1,0,1,"20","0","20",false,1);long payment=createPaymentRecord(order,customer,station,"20",1,1);
        Api pending=get("/api/payments/pending",mgr);assertEquals(0,pending.code(),pending.toString());assertTrue(pending.data().size()>0,pending.toString());
        Api staff=put("/api/payments/"+payment+"/confirm",mgr,null);assertEquals(1,staff.code(),staff.toString());
        Api self=put("/api/payments/"+payment+"/confirm",cus,null);assertNotEquals(0,self.code(),self.toString());
        assertEquals(1,intOf("select status from payment_record where id=?",payment));assertEquals(0,intOf("select payment_status from orders where id=?",order));
        System.out.println("SYNTHETIC_LEGACY_PENDING_VIEW "+pending.data());
    }
    @Test void partialWaterAmountIsRejectedAndConcurrentWholeWaterRefundHasOneReceipt() throws Exception {
        seed();long second=createProduct("Second bottle",2,"30","0",0,"0");long order=createOrderFull(customer,address,station,product,4,2,2,"100","0","105",false,0);
        createOrderItemFull(order,product,"Bottle",2,0,"20","0");createOrderItemFull(order,second,"Second bottle",2,0,"30","0");
        jdbc.update("update orders set delivery_fee=5,settle_station_id=? where id=?",station,order);
        long payment=insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount,barrel_deposit,delivery_fee) values(?,?,?,2,2,105,100,0,5)",order,customer,station);
        String route="/api/payments/"+payment+"/refund";
        Api partial=put(route,mgr,"{\"scope\":\"WATER\",\"expectedRefundAmount\":60}");assertEquals(1,partial.code(),partial.toString());assertEquals(0,intOf("select count(*) from payment_record where amount<0"));
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{Callable<Api> action=()->{start.await();return put(route,mgr,"{\"scope\":\"WATER\",\"expectedRefundAmount\":100}");};Future<Api>a=pool.submit(action),b=pool.submit(action);start.countDown();List<Api> rs=List.of(a.get(45,TimeUnit.SECONDS),b.get(45,TimeUnit.SECONDS));assertEquals(1,rs.stream().filter(r->r.code()==0).count(),rs.toString());assertTrue(rs.stream().allMatch(r->r.code()==0||r.code()==1),rs.toString());}finally{pool.shutdownNow();}
        assertEquals(1,intOf("select count(*) from payment_record where order_id=? and amount<0",order));assertEquals(-100,decimalOf("select amount from payment_record where order_id=? and amount<0",order).intValueExact());assertEquals(2,intOf("select payment_status from orders where id=?",order));
    }
}
