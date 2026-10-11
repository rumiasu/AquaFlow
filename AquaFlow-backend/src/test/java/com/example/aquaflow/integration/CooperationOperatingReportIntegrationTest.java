package com.example.aquaflow.integration;

import com.example.aquaflow.support.TestBusinessClock;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** 同一批合作单的报价、待结与实款分别核对，退款不抹已登记交款。 */
@Import(TestBusinessClock.Config.class)
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true", "aquaflow.barrel.maintenance-enabled=false"})
class CooperationOperatingReportIntegrationTest extends AbstractIntegrationTest {
    static final String LEDGER="/api/manager/inter-station-settlements";
    long a,b,am,bm,customer,address,product;
    String at,bt;
    void seed() {
        a=createStation("报价甲站");b=createStation("履约乙站");am=createStaff("甲长","STATION_MANAGER",a,1);bm=createStaff("乙长","STATION_MANAGER",b,1);
        customer=createCustomer("客户","cooperation-report");address=createAddress(customer,"一楼");
        product=createProduct("水",1,"15.00","30.00",0,"0.00");
        at=staffToken(am,"STATION_MANAGER",a);bt=staffToken(bm,"STATION_MANAGER",b);
    }
    long paidOrder(long holder,String agreed) {
        long id=createOrderCrossStation(customer,address,a,b,product,4,2,2,"15.00","0.00","20.00");
        jdbc.update("update orders set settle_station_id=?,delivery_fee=4,floor_fee=1 where id=?",b,id);
        insert("insert into payment_record(order_id,customer_id,station_id,payment_method,status,amount,water_amount,barrel_deposit,delivery_fee,floor_fee) values(?,?,?,2,2,20,15,0,4,1)",id,customer,holder);
        jdbc.update("insert into dispatch_agreement(order_id,source_station_id,target_station_id,service_amount,net_barrels,barrel_mode,barrel_amount,barrel_items,status,accepted_by,accepted_time,create_time) values(?,?,?, ?,0,'RETURN_EMPTY',0,'','ACCEPTED',?,now(),now())",id,a,b,new BigDecimal(agreed),bm);
        return id;
    }
    void money(String expected,JsonNode amount) { assertEquals(0,new BigDecimal(expected).compareTo(amount.decimalValue())); }
    void settle(long id,String payer) { Api r=post(LEDGER+"/"+id+"/settle",payer,"{\"note\":\"双方核实并已实际交款\"}");assertEquals(0,r.code(),r.toString()); }
    void refund(long id,String scope) {
        long p=longOf("select id from payment_record where order_id=? and amount>0",id);
        Api r=put("/api/payments/"+p+"/refund",bt,"{\"scope\":\""+scope+"\",\"note\":\"已实际退给客户\"}");assertEquals(0,r.code(),r.toString());
    }
    @BeforeEach void freezeReportClock() { TestBusinessClock.freezeAtDbNow(jdbc); }
    JsonNode report(String token) {
        Api r=get("/api/manager/gross-profit",token); assertEquals(0,r.code(),r.toString());
        return r.data();
    }
    @Test void sourceHeldTwentyMustShowNegativeFiveCommitmentBeforePayment() {
        seed(); paidOrder(a,"25.00");
        JsonNode aReport=report(at), bReport=report(bt);
        assertTrue(aReport.has("cooperation"), "A收20、应付B25：经营报表必须列外包补差，不能只给A销售净利0");
        JsonNode ac=aReport.path("cooperation"),bc=bReport.path("cooperation");
        money("20.00",ac.path("customerReceivedAmount"));
        money("25.00",ac.path("agreedPayAmount"));
        money("-5.00",ac.path("quoteDifferenceAmount"));
        money("25.00",ac.path("pendingPayAmount"));
        money("0.00",ac.path("settledPayAmount"));
        money("25.00",bc.path("agreedReceiveAmount"));
        money("25.00",bc.path("pendingReceiveAmount"));
        money("0.00",bc.path("customerReceivedAmount"));
    }
    @Test void fullRefundAndReturnPreserveActualPaymentHistoryUntilBothSidesClose() {
        seed();long id=paidOrder(a,"25.00");settle(id,at);
        JsonNode ac=report(at).path("cooperation"),bc=report(bt).path("cooperation");
        money("0.00",ac.path("pendingPayAmount"));money("25.00",ac.path("settledPayAmount"));
        money("-5.00",ac.path("recordedCashNetAmount"));money("25.00",bc.path("recordedCashNetAmount"));
        refund(id,"ALL_CONSUMPTION");
        assertEquals(0,post(LEDGER+"/"+id+"/reverse",at,"{}").code());
        ac=report(at).path("cooperation");bc=report(bt).path("cooperation");
        money("25.00",ac.path("settledPayAmount"));money("0.00",ac.path("pendingPayAmount"));
        money("5.00",ac.path("recoveryPendingReceiveAmount"));money("5.00",bc.path("recoveryPendingPayAmount"));
        money("20.00",bc.path("customerRefundedAmount"));money("5.00",bc.path("recordedCashNetAmount"));
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+id+"/sent",bt,"{\"note\":\"实际交回5元\"}").code());
        ac=report(at).path("cooperation");bc=report(bt).path("cooperation");
        money("5.00",ac.path("recoveryAwaitingConfirmationAmount"));money("0.00",ac.path("recoveryReceivedAmount"));
        money("5.00",bc.path("recoverySentPayAmount"));money("0.00",bc.path("recoveryPendingPayAmount"));
        money("-5.00",ac.path("recordedCashNetAmount"));money("0.00",bc.path("recordedCashNetAmount"));
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+id+"/received",at,"{}").code());
        ac=report(at).path("cooperation");bc=report(bt).path("cooperation");
        money("5.00",ac.path("recoveryReceivedAmount"));money("0.00",ac.path("recoveryPendingReceiveAmount"));
        money("0.00",ac.path("recordedCashNetAmount"));money("0.00",bc.path("recordedCashNetAmount"));
        money("-5.00",ac.path("quoteDifferenceAmount"));money("25.00",ac.path("settledPayAmount"));
        assertEquals(3,intOf("select status from inter_station_settlement where order_id=?",id));
        assertEquals("RECEIVED",jdbc.queryForObject("select status from inter_station_recovery where order_id=?",String.class,id));
    }
    @Test void receiverHeldCustomerCashUsesOnlyFiveDifferenceAndNeverReceivesCustomerCashTwice() {
        seed();long id=paidOrder(b,"25.00");
        money("5.00",report(at).path("cooperation").path("pendingPayAmount"));
        money("20.00",report(bt).path("cooperation").path("customerReceivedAmount"));
        settle(id,at);money("-5.00",report(at).path("cooperation").path("recordedCashNetAmount"));
        money("25.00",report(bt).path("cooperation").path("recordedCashNetAmount"));
        refund(id,"ALL_CONSUMPTION");money("5.00",report(bt).path("cooperation").path("recordedCashNetAmount"));
        money("5.00",report(at).path("cooperation").path("recoveryPendingReceiveAmount"));
    }
    @Test void reportRolesTenantAndOrderPeriodCannotExposeOtherStationCooperation() {
        seed();paidOrder(a,"25.00");
        long c=createStation("无关丙站"),cm=createStaff("丙长","STATION_MANAGER",c,1);
        String ct=staffToken(cm,"STATION_MANAGER",c);
        JsonNode unrelated=get("/api/manager/gross-profit?stationId="+a,ct).data().path("cooperation");
        money("0.00",unrelated.path("agreedPayAmount"));money("0.00",unrelated.path("customerReceivedAmount"));
        money("0.00",unrelated.path("pendingPayAmount"));
        JsonNode outside=get("/api/manager/gross-profit?from=2000-01-01&to=2000-01-31",at).data().path("cooperation");
        money("0.00",outside.path("agreedPayAmount"));money("0.00",outside.path("pendingPayAmount"));
        assertEquals(1,get("/api/manager/gross-profit",customerToken(customer)).code());
        long d=createStaff("配送员","DELIVERY",a,1);
        assertEquals(1,get("/api/manager/gross-profit",staffToken(d,"DELIVERY",a)).code());
        assertEquals(401,get("/api/manager/gross-profit",null).status());
    }
    @Test void periodSummaryHasNoTwoHundredRowListTruncation() {
        seed();for(int i=0;i<205;i++) {
            long id=paidOrder(a,"25.00");
            jdbc.update("insert into inter_station_settlement(order_id,from_station_id,to_station_id,amount,basis,status,snapshot_time,settled_time) values(?,?,?,25,1,3,now(),now())",id,a,b);
            jdbc.update("insert into inter_station_recovery(order_id,from_station_id,to_station_id,amount,status,sender_confirmed_by,sender_confirmed_time,receiver_confirmed_by,receiver_confirmed_time,create_time) values(?,?,?,5,'RECEIVED',?,now(),?,now(),now())",id,b,a,bm,am);
            jdbc.update("update orders set payment_status=3 where id=?",id);
        }
        JsonNode ac=report(at).path("cooperation");
        money("5125.00",ac.path("settledPayAmount"));money("1025.00",ac.path("recoveryReceivedAmount"));
        money("0.00",ac.path("pendingPayAmount"));
    }

    @Test
    void receiverHeldThirtyReturnsFiveToSourceWithoutRepricingAgreement() {
        seed();long id=paidOrder(b,"25.00");
        jdbc.update("update orders set water_amount=25,total_amount=30 where id=?",id);
        jdbc.update("update payment_record set amount=30,water_amount=25 where order_id=?",id);
        JsonNode ac=report(at).path("cooperation"),bc=report(bt).path("cooperation");
        money("5.00",ac.path("quoteDifferenceAmount"));money("5.00",ac.path("pendingReceiveAmount"));
        money("0.00",ac.path("pendingPayAmount"));money("30.00",bc.path("customerReceivedAmount"));
        money("5.00",bc.path("pendingPayAmount"));money("25.00",bc.path("agreedReceiveAmount"));
        assertEquals(1,post(LEDGER+"/"+id+"/settle",at,"{}").code());
        settle(id,bt);
        ac=report(at).path("cooperation");bc=report(bt).path("cooperation");
        money("5.00",ac.path("settledReceiveAmount"));money("5.00",ac.path("recordedCashNetAmount"));
        money("0.00",ac.path("pendingReceiveAmount"));money("0.00",ac.path("customerReceivedAmount"));
        money("5.00",bc.path("settledPayAmount"));money("25.00",bc.path("recordedCashNetAmount"));
        assertEquals(b,longOf("select station_id from payment_record where order_id=? and amount>0",id));
        assertEquals(0,new BigDecimal("25.00").compareTo(jdbc.queryForObject("select service_amount from dispatch_agreement where order_id=?",BigDecimal.class,id)));
    }
    @Test
    void decimalConsumptionExcludesOrderDepositFromReceiptsAndWaterRefund() {
        seed();long id=paidOrder(a,"25.10");
        jdbc.update("update orders set water_amount=15.20,delivery_fee=4.10,floor_fee=1.05,deposit_amount=30.60,total_amount=50.95 where id=?",id);
        jdbc.update("update payment_record set amount=50.95,water_amount=15.20,delivery_fee=4.10,floor_fee=1.05,barrel_deposit=30.60 where order_id=?",id);
        JsonNode ac=report(at).path("cooperation");
        money("20.35",ac.path("customerReceivedAmount"));money("-4.75",ac.path("quoteDifferenceAmount"));
        money("25.10",ac.path("pendingPayAmount"));
        settle(id,at);refund(id,"WATER");
        ac=report(at).path("cooperation");JsonNode bc=report(bt).path("cooperation");
        money("20.35",ac.path("customerReceivedAmount"));money("-4.75",ac.path("recordedCashNetAmount"));
        money("25.10",ac.path("settledPayAmount"));money("25.10",bc.path("settledReceiveAmount"));
        money("15.20",bc.path("customerRefundedAmount"));money("9.90",bc.path("recordedCashNetAmount"));
        money("0.00",ac.path("customerRefundedAmount"));money("0.00",bc.path("customerReceivedAmount"));
        assertEquals(0,new BigDecimal("30.60").compareTo(jdbc.queryForObject("select barrel_deposit from payment_record where order_id=? and amount>0",BigDecimal.class,id)));
        assertEquals(0,BigDecimal.ZERO.compareTo(jdbc.queryForObject("select barrel_deposit from payment_record where order_id=? and amount<0",BigDecimal.class,id)));
        assertEquals(0,new BigDecimal("25.10").compareTo(jdbc.queryForObject("select service_amount from dispatch_agreement where order_id=?",BigDecimal.class,id)));
    }
    @Test
    void laterMonthCashFactsStayInOriginalOrderPeriod() {
        seed();long id=paidOrder(a,"25.00");
        java.time.LocalDate current=jdbc.queryForObject("select curdate()",java.time.LocalDate.class);
        java.time.LocalDate previous=current.withDayOfMonth(1).minusMonths(1),last=previous.plusMonths(1).minusDays(1);
        java.sql.Timestamp created=java.sql.Timestamp.valueOf(last.atTime(23,59,59));
        jdbc.update("update orders set create_time=? where id=?",created,id);
        jdbc.update("update payment_record set create_time=? where order_id=?",created,id);
        jdbc.update("update dispatch_agreement set create_time=?,accepted_time=? where order_id=?",created,created,id);
        String prior="/api/manager/gross-profit?from="+previous+"&to="+last;
        String now="/api/manager/gross-profit?from="+current.withDayOfMonth(1)+"&to="+current.withDayOfMonth(current.lengthOfMonth());
        money("25.00",get(prior,at).data().path("cooperation").path("pendingPayAmount"));
        settle(id,at);refund(id,"ALL_CONSUMPTION");
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+id+"/sent",bt,"{\"note\":\"Synthetic actual five returned\"}").code());
        assertEquals(0,post("/api/manager/inter-station-recoveries/"+id+"/received",at,"{}").code());
        assertTrue(jdbc.queryForObject("select settled_time from inter_station_settlement where order_id=?",java.time.LocalDateTime.class,id).toLocalDate().isAfter(last));
        assertTrue(jdbc.queryForObject("select create_time from payment_record where order_id=? and amount<0",java.time.LocalDateTime.class,id).toLocalDate().isAfter(last));
        assertTrue(jdbc.queryForObject("select receiver_confirmed_time from inter_station_recovery where order_id=?",java.time.LocalDateTime.class,id).toLocalDate().isAfter(last));
        JsonNode ac=get(prior,at).data().path("cooperation"),bc=get(prior,bt).data().path("cooperation");
        money("20.00",ac.path("customerReceivedAmount"));money("25.00",ac.path("settledPayAmount"));
        money("5.00",ac.path("recoveryReceivedAmount"));money("0.00",ac.path("recordedCashNetAmount"));
        money("20.00",bc.path("customerRefundedAmount"));money("25.00",bc.path("settledReceiveAmount"));
        money("5.00",bc.path("recoverySentPayAmount"));money("0.00",bc.path("recordedCashNetAmount"));
        for(String token:new String[]{at,bt}) {
            JsonNode outside=get(now,token).data().path("cooperation");
            for(String field:new String[]{"customerReceivedAmount","customerRefundedAmount","settledPayAmount","settledReceiveAmount","recoverySentPayAmount","recoveryReceivedAmount","pendingPayAmount","pendingReceiveAmount"}) money("0.00",outside.path(field));
        }
    }
}
