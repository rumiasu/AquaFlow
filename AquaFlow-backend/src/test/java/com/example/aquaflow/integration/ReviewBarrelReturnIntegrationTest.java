package com.example.aquaflow.integration;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.service.BarrelLedgerService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** New independent rights distinguish first pickup from the exchange return requirement. */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false","app.payment.test-mode=true"})
class ReviewBarrelReturnIntegrationTest extends AbstractIntegrationTest {
    @Autowired BarrelLedgerService ledger;
    @ParameterizedTest @ValueSource(ints={1,3,5})
    void pickupBucketsAreExcludedFromShortAndExtraReturnDifference(int actual) {
        long station=createStation("Return review");long manager=createStaff("Manager","STATION_MANAGER",station,1);
        long customer=createCustomer("Return customer","return-review");long address=createAddress(customer,"Return address");
        long product=createProduct("Return bucket",1,"20","30",1,"8");createInventoryFull(station,product,20,1,"8");createInventoryRecord(station,product,20,"STOCK_IN",0);
        String cus=customerToken(customer),mgr=staffToken(manager,"STATION_MANAGER",station);
        Api buy=post("/api/barrel-rights/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":7,\"paymentMethod\":2,\"idempotencyKey\":\"return-review-rights\"}");
        assertEquals(0,buy.code(),buy.toString());assertEquals(0,put("/api/payments/"+buy.data().path("paymentId").asLong()+"/confirm",mgr,null).code());
        long first=place(cus,station,address,product,5,"review-first-five");pay(cus,first);
        assertEquals(0,post("/api/delivery/orders/"+first+"/accept",mgr,"{}").code());
        Api firstDone=post("/api/delivery/orders/"+first+"/complete",mgr,"{\"returnBucketQty\":0}");assertEquals(0,firstDone.code(),firstDone.toString());
        assertEquals(5,ledger.occupiedQty(customer,station,product));
        long next=place(cus,station,address,product,5,"review-five-with-two-pickup");pay(cus,next);
        assertEquals(2,intOf("select pickup_qty from barrel_right_reservation where owner_id=? and owner_type='ORDER'",next));
        assertEquals(0,post("/api/delivery/orders/"+next+"/accept",mgr,"{}").code());
        Api done=post("/api/delivery/orders/"+next+"/complete",mgr,"{\"returnBucketQty\":"+actual+"}");
        assertEquals(0,done.code(),done.toString());
        assertEquals(4,intOf("select status from orders where id=?",next));
        assertEquals(10-actual,ledger.occupiedQty(customer,station,product));
        int diff=3-actual;
        if(diff==0)assertEquals(0,intOf("select count(*) from order_barrel_exception where order_id=?",next));
        else {assertEquals(1,intOf("select count(*) from order_barrel_exception where order_id=?",next));assertEquals(diff,intOf("select discrepancy from order_barrel_exception where order_id=?",next));}
    }
    long place(String token,long station,long address,long product,int qty,String key){
        Api r=post("/api/orders/create",token,"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":1,\"idempotencyKey\":\""+key+"\",\"items\":[{\"productId\":"+product+",\"quantity\":"+qty+"}]}");assertEquals(0,r.code(),r.toString());return r.data().path("orderId").asLong();
    }
    void pay(String token,long id){Api r=post("/api/payments",token,"{\"orderId\":"+id+",\"paymentMethod\":1}");assertEquals(0,r.code(),r.toString());}
}
