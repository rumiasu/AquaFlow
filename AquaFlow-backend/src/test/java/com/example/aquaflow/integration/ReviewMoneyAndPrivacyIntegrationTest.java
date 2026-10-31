package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.support.TestBusinessClock;
import com.example.aquaflow.service.ReceivableService;
import com.example.aquaflow.service.InventoryReservationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/** Independent review counterexamples exercise actual HTTP, SQL and transaction state. */
@Import(TestBusinessClock.Config.class)
class ReviewMoneyAndPrivacyIntegrationTest extends AbstractIntegrationTest {
    @Autowired ReceivableService receivables;
    @Autowired InventoryReservationService reservations;
    long station, manager, customer, address, product;
    String token;
    @BeforeEach void seed() {
        TestBusinessClock.freezeAtDbNow(jdbc);
        station=createStation("Review station");manager=createStaff("Manager","STATION_MANAGER",station,1);
        customer=createCustomer("Owner profile","review-customer");address=createAddress(customer,"Review address");
        jdbc.update("update customer set phone='13900001111' where id=?",customer);
        product=createProduct("Review bottle",2,"20","0",1,"18");
        createInventoryFull(station,product,20,1,"18");token=staffToken(manager,"STATION_MANAGER",station);
    }
    void ticketReceipt(long pid) {
        long pay=createPaymentRecord(null,customer,station,"36",1,2);
        jdbc.update("update payment_record set ticket_water_type_id=?,ticket_qty=2,update_time=? where id=?",pid,TestBusinessClock.now(),pay);
    }
    JsonNode report() {
        String day=TestBusinessClock.today().toString();Api r=get("/api/manager/gross-profit?from="+day+"&to="+day,token);
        assertEquals(0,r.code(),r.toString());return r.data();
    }
    void unknownProfit(JsonNode r,String income) {
        assertEquals(0,new BigDecimal(income).compareTo(r.path("totalIncome").decimalValue()),r.toString());
        assertFalse(r.path("costComplete").asBoolean(),r.toString());
        for(String field:new String[]{"totalCost","totalProfit","netProfit"})assertTrue(r.path(field).isNull(),field+": "+r);
        assertTrue(r.path("missingCostKinds").asInt()>0);
        for(JsonNode row:r.path("items"))assertTrue(row.path("profit").isNull(),row.toString());
    }
    @Test void ticketOnlyIncomeHasNoFulfilmentCostAndMustNotBecomeProfit() {
        ticketReceipt(product);unknownProfit(report(),"36");
    }
    @Test void unrelatedKnownCostSaleDoesNotMakeTicketOnlyIncomeCostComplete() {
        jdbc.update("update inventory set cost_amount=100 where station_id=? and product_id=?",station,product);
        long order=createOrderFull(customer,address,station,product,2,2,1,"20","0","20",false,1);
        createReservedItem(order,product,"Review bottle",1,"20","0");reservations.shipForOrder(order,station);
        jdbc.update("update orders set status=4 where id=?",order);
        long ticketProduct=createProduct("Other tickets",2,"20","0",1,"18");ticketReceipt(ticketProduct);
        unknownProfit(report(),"56");
    }
    @ParameterizedTest @ValueSource(ints={2,4})
    void stationStatusListsMaskForeignProfilesButKeepReceiverSnapshots(int status) {
        long owner=createStation("Foreign owner");
        long cross=createOrderCrossStation(customer,address,owner,station,product,status,2,1,"20","0","20");
        long same=createOrderFull(customer,address,station,product,status,2,1,"20","0","20",false,1);
        jdbc.update("update orders set settle_station_id=?,receiver_name='Receiver snapshot',receiver_phone='13800002222' where id in (?,?)",station,cross,same);
        String route=status==2?"station-delivering":"station-completed";
        Api r=get("/api/delivery/orders/"+route,token);assertEquals(0,r.code(),r.toString());
        JsonNode crossRow=null,sameRow=null;
        for(JsonNode row:r.data()){if(row.path("id").asLong()==cross)crossRow=row;if(row.path("id").asLong()==same)sameRow=row;}
        assertNotNull(crossRow,r.toString());assertNotNull(sameRow,r.toString());
        assertTrue(crossRow.path("customerName").isNull()||crossRow.path("customerName").asText().isEmpty(),crossRow.toString());
        assertTrue(crossRow.path("customerPhone").isNull()||crossRow.path("customerPhone").asText().isEmpty(),crossRow.toString());
        assertEquals("Receiver snapshot",crossRow.path("receiverName").asText());assertEquals("13800002222",crossRow.path("receiverPhone").asText());
        assertEquals("Owner profile",sameRow.path("customerName").asText());assertEquals("13900001111",sameRow.path("customerPhone").asText());
    }
    @Test void unconfirmedPricingFlagUsesReferencePriceWithoutBlockingQuoteOrCreate() {
        createCustomerStationConfig(customer,station,1);
        jdbc.update("update inventory set pricing_confirmed=0 where station_id=? and product_id=?",station,product);
        String body="{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":2,\"idempotencyKey\":\"review-pricing-reference\",\"items\":[{\"productId\":"+product+",\"quantity\":1}]}";
        Api q=post("/api/payments/quote",customerToken(customer),body);assertEquals(0,q.code(),q.toString());
        Api c=post("/api/orders/create",customerToken(customer),body);assertEquals(0,c.code(),c.toString());
        assertEquals(20,decimalOf("select water_amount from orders where id=?",c.data().path("orderId").asLong()).intValueExact());
    }
    @Test void settlementStationRecalculationMustNotChangeAnotherStationsReceivable() {
        long owner=createStation("Receivable owner");
        createCustomerStationConfig(customer,station,1);createCustomerStationConfig(customer,owner,1);
        jdbc.update("update customer_station_config set settlement_cycle='MONTHLY',due_days=15 where customer_id=? and station_id=?",customer,station);
        jdbc.update("update customer_station_config set settlement_cycle='MONTHLY',due_days=30 where customer_id=? and station_id=?",customer,owner);
        long incoming=createOrderCrossStation(customer,address,owner,station,product,4,1,2,"20","0","20");
        long outgoing=createOrderCrossStation(customer,address,station,owner,product,4,1,2,"20","0","20");
        LocalDate old=LocalDate.of(2026,12,30);jdbc.update("update orders set due_date=?,create_time='2026-10-05 12:00:00' where id in (?,?)",old,incoming,outgoing);
        Api recalculated=post("/api/manager/customers/"+customer+"/credit-terms/recalculate",token,"{}");
        assertEquals(0,recalculated.code(),recalculated.toString());assertEquals(1,recalculated.data().path("changedCount").asInt());
        assertEquals(LocalDate.of(2026,11,15),jdbc.queryForObject("select due_date from orders where id=?",LocalDate.class,incoming));
        assertEquals(old,jdbc.queryForObject("select due_date from orders where id=?",LocalDate.class,outgoing));
    }
}
