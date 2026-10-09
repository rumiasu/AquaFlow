package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/** 退款扣回原批次收入，不搬动成本和工钱，也不新建按退款日的现金收支口径。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true", "aquaflow.barrel.maintenance-enabled=false"})
class GrossProfitRefundRepairIntegrationTest extends AbstractIntegrationTest {
    long station,manager,staff,customer,address,product,payment;
    String mgr,cus;
    LocalDate oldDay;
    void seed() {
        station=createStation("净收入站");manager=createStaff("站长","STATION_MANAGER",station,1);staff=createStaff("配送员","DELIVERY",station,1);
        customer=createCustomer("客户","profit-refund-repair");address=createAddress(customer,"一楼");
        product=createProduct("水",1,"15.00","30.00",1,"8.00");createInventoryFull(station,product,20,1,"8.00");
        jdbc.update("update inventory set cost_price=8 where station_id=? and product_id=?",station,product);
        createCustomerStationConfig(customer,station,1);mgr=staffToken(manager,"STATION_MANAGER",station);cus=customerToken(customer);
        oldDay=jdbc.queryForObject("select date_sub(current_date(),interval 3 day)",LocalDate.class);
    }
    void soldOrder() {
        long id=createOrderFull(customer,address,station,product,4,2,2,"15.00","0.00","20.00",false,1);
        jdbc.update("update orders set settle_station_id=?,delivery_fee=4,floor_fee=1,create_time=? where id=?",station,oldDay.atTime(12,0),id);
        createOrderItemFull(id,product,"水",1,0,"15.00","0.00");
        payment=insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount,barrel_deposit,delivery_fee,floor_fee) values(?,?,?,2,2,20,15,0,4,1)",id,customer,station);
        jdbc.update("insert into staff_earning(station_id,staff_id,order_id,kind,product_id,amount,create_time) values(?,?,?,'DELIVERY_BUCKET',?,3,now())",station,staff,id,product);
    }
    JsonNode report(LocalDate day) {
        Api r=get("/api/manager/gross-profit?from="+day+"&to="+day,mgr);assertEquals(0,r.code(),r.toString());return r.data();
    }
    void money(String expected,JsonNode report,String field) { assertEquals(0,new BigDecimal(expected).compareTo(report.path(field).decimalValue()),field+": "+report); }
    void refund(String scope) {
        Api r=put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\""+scope+"\",\"note\":\"客户已实际收到退款\"}");assertEquals(0,r.code(),r.toString());
    }

    @Test void waterThenServiceRefundReducesOriginalCohortWhileActualCostAndWagesRemain() {
        seed();soldOrder();JsonNode before=report(oldDay);money("20.00",before,"totalIncome");money("9.00",before,"netProfit");
        refund("WATER");JsonNode water=report(oldDay);money("0.00",water,"orderRevenue");money("5.00",water,"totalIncome");
        money("8.00",water,"totalCost");money("3.00",water,"wage");money("-6.00",water,"netProfit");
        refund("SERVICE");JsonNode all=report(oldDay);money("0.00",all,"totalIncome");money("0.00",all,"deliveryFee");money("0.00",all,"floorFee");
        money("8.00",all,"totalCost");money("-11.00",all,"netProfit");
        assertEquals(20,intOf("select quantity from inventory where station_id=? and product_id=?",station,product));
    }

    @Test void serviceOnlyRefundKeepsWaterRevenueAndLaterRefundDoesNotBecomeRefundDayExpense() {
        seed();soldOrder();refund("SERVICE");JsonNode r=report(oldDay);money("15.00",r,"orderRevenue");money("15.00",r,"totalIncome");money("4.00",r,"netProfit");
        LocalDate today=jdbc.queryForObject("select current_date()",LocalDate.class);money("0.00",report(today),"totalIncome");
        assertTrue(r.path("profitBasisNote").asText().contains("原批次"));
    }

    @Test void allConsumptionRefundLeavesFulfilledCostsRatherThanReportingOriginalSales() {
        seed();soldOrder();refund("ALL_CONSUMPTION");JsonNode r=report(oldDay);
        money("0.00",r,"totalIncome");money("8.00",r,"totalCost");money("3.00",r,"wage");money("-11.00",r,"netProfit");
    }

    @Test void partiallyUsedPurchasedBatchExitReducesOriginalTicketRevenueOnlyByActualRefund() {
        seed();createBarrelLot("TICKET-RIGHT",customer,station,product,"30.00",1,1);createBarrelAsset(customer,station,product,1,"30.00");
        Api purchased=post("/api/tickets/purchase",cus,"{\"stationId\":"+station+",\"productId\":"+product+",\"quantity\":3,\"paymentMethod\":2,\"idempotencyKey\":\"old-ticket-batch\"}");
        assertEquals(0,purchased.code(),purchased.toString());payment=purchased.data().path("paymentId").asLong();
        assertEquals(0,put("/api/payments/"+payment+"/confirm",mgr,null).code());
        jdbc.update("update payment_record set update_time=? where id=?",oldDay.atTime(12,0),payment);
        money("24.00",report(oldDay),"ticketRevenue");
        assertEquals(0,post("/api/tickets/consume",mgr,"{\"customerId\":"+customer+",\"productId\":"+product+",\"quantity\":1,\"idempotencyKey\":\"used-one\"}").code());
        Api returned=put("/api/payments/"+payment+"/refund",mgr,"{\"scope\":\"WATER\",\"expectedTicketQty\":2,\"expectedTicketAmount\":16,\"note\":\"实际退剩余两张\"}");
        assertEquals(0,returned.code(),returned.toString());money("8.00",report(oldDay),"ticketRevenue");
        assertEquals(0,intOf("select sum(remain_qty) from ticket_lot where customer_id=?",customer));
        assertEquals(0,new BigDecimal("16.00").compareTo(decimalOf("select amount from ticket_exit_refund where original_payment_id=?",payment)));
    }
}
