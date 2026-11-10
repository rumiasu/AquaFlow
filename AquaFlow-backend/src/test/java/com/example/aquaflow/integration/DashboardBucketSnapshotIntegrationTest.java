package com.example.aquaflow.integration;

import com.example.aquaflow.mapper.DashboardMapper;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.support.TestBusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Real MyBatis/MySQL checks: historical facts never depend on today's product category. */
@Import(TestBusinessClock.Config.class)
class DashboardBucketSnapshotIntegrationTest extends AbstractIntegrationTest {
    @Autowired DashboardMapper dashboard;
    private LocalDateTime start, end;
    @BeforeEach void freezeSharedBusinessWindow() {
        // HTTP and mapper fixtures use one captured business day, including runs across midnight.
        TestBusinessClock.freezeAtDbNow(jdbc);
        start = TestBusinessClock.today().atStartOfDay();
        end = start.plusDays(1);
    }
    private long station, staff, customer, address, barrel;
    private void seed(){
        station=createStation("合成桶数站");staff=createStaff("合成配送员","DELIVERY",station,1);
        customer=createCustomer("合成客户","snapshot-customer");address=createAddress(customer,"合成地址");
        barrel=createProduct("合成桶装水",1,"10","30",0,"0");
    }
    private long order(Integer qty,int status,LocalDateTime time){
        long id=createOrderFull(customer,address,station,barrel,status,1,2,"10","0","10",false,0);
        jdbc.update("update orders set delivery_bucket_qty=?,delivery_staff_id=?,create_time=? where id=?",qty,staff,time,id);
        return id;
    }
    private void quantity(Map<String,Object> row,Long expected,long missing){
        if(expected==null) assertNull(row.get("deliveryBucketQty"));
        else assertEquals(expected.longValue(),((Number)row.get("deliveryBucketQty")).longValue());
        assertEquals(missing,((Number)row.get("deliveryBucketUnknownOrders")).longValue());
    }
    private void both(Long expected,long missing){quantity(dashboard.summary(station,start,end),expected,missing);quantity(dashboard.staffPerformance(station,start,end).get(0),expected,missing);}
    @Test void snapshotsSurviveMixedItemsCategoryChangesAndExplicitZero(){
        seed();long mixed=order(5,4,start.plusHours(2));
        long bottle=createProduct("合成瓶装水",2,"4","0",0,"0");
        createOrderItemFull(mixed,barrel,"合成桶装水",5,0,"10","0");createOrderItemFull(mixed,bottle,"合成瓶装水",9,0,"4","0");
        long zero=order(0,1,start);createOrderItemFull(zero,bottle,"合成瓶装水",7,0,"4","0");
        both(5L,0);
        jdbc.update("update product set category=2 where id=?",barrel);jdbc.update("update product set category=1 where id=?",bottle);
        both(5L,0); // Header snapshot, not item counts, current category, or quantity.
    }
    @Test void missingSnapshotMakesTotalUnknownEvenWhenItemsExist(){
        seed();order(5,4,start);long old=order(null,1,start.plusHours(1));
        createOrderItemFull(old,barrel,"合成旧水",3,0,"10","0");both(null,1);
        jdbc.update("update product set category=3 where id=?",barrel);both(null,1);
        long manager=createStaff("合成站长","STATION_MANAGER",station,1);
        Api response=get("/api/dashboard/report?range=7d",staffToken(manager,"STATION_MANAGER",station));
        assertEquals(0,response.code(),response.toString());
        var summary=response.data().path("summary");
        assertTrue(summary.has("deliveryBucketQty"));assertTrue(summary.path("deliveryBucketQty").isNull());
        assertFalse(summary.path("deliveryBucketQtyKnown").asBoolean());assertEquals(1,summary.path("deliveryBucketUnknownOrders").asInt());
        var performance=response.data().path("staffPerformance").get(0);
        assertTrue(performance.has("deliveryBucketQty"));assertTrue(performance.path("deliveryBucketQty").isNull());
        var compare=response.data().path("compare").path("deliveryBucketQty");
        assertTrue(compare.has("current"));assertTrue(compare.path("current").isNull());
        assertTrue(compare.has("good"));assertTrue(compare.path("good").isNull());assertFalse(compare.path("known").asBoolean());
        jdbc.update("update orders set status=5 where id=?",old);both(5L,0);
    }
    @Test void scopeKeepsCreationWindowCancellationAndSettlementPrecedence(){
        seed();long other=createStation("合成另一站");
        order(2,1,start);order(3,2,end.minusSeconds(1)); // no new status/payment filter
        order(null,5,start.plusHours(2));order(null,4,start.minusSeconds(1));order(null,4,end);
        long excluded=order(null,4,start);jdbc.update("update orders set settle_station_id=? where id=?",other,excluded);
        long delivered=order(7,3,start);jdbc.update("update orders set station_id=?,settle_station_id=null,delivery_station_id=? where id=?",other,station,delivered);
        long owned=order(11,4,start);jdbc.update("update orders set settle_station_id=null,delivery_station_id=null where id=?",owned);
        long settled=order(13,4,start);jdbc.update("update orders set station_id=?,delivery_station_id=?,settle_station_id=? where id=?",other,other,station,settled);
        both(36L,0);quantity(dashboard.summary(other,start,end),null,1);
    }
    @Test void emptyAndOnlyCancelledAreKnownZero(){seed();quantity(dashboard.summary(station,start,end),0L,0);assertTrue(dashboard.staffPerformance(station,start,end).isEmpty());order(null,5,start);both(0L,0);}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realCreationPersistsKnownZeroOrMixedBarrelSnapshot(boolean mixed){
        seed();createInventory(station,barrel,50);long bottle=createProduct("合成瓶装水",2,"4","0",0,"0");createInventory(station,bottle,50);createCustomerStationConfig(customer,station,1);
        String items="{\"productId\":"+bottle+",\"quantity\":7}"+(mixed?",{\"productId\":"+barrel+",\"quantity\":2}":"");
        Api response=post("/api/orders/create",customerToken(customer),"{\"addressId\":"+address+",\"stationId\":"+station+",\"paymentMethod\":2,\"idempotencyKey\":\"bucket-snapshot-create\",\"items\":["+items+"]}");
        assertEquals(0,response.code(),response.toString());assertFalse(response.data().path("needConfirm").asBoolean());
        long id=response.data().path("orderId").asLong();assertTrue(id>0,response.toString());
        assertEquals(mixed?2:0,intOf("select delivery_bucket_qty from orders where id=?",id));
        assertEquals(0,intOf("select count(*) from orders where id=? and delivery_bucket_qty is null",id));
    }
}
