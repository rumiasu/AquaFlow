package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only own-account facts; a successful read is never account deletion. */
class AccountClosureCheckIntegrationTest extends AbstractIntegrationTest {
    private static final String PATH="/api/customer/account/closure-check";
    long customer,other,station,product;
    String token;
    void seed() {
        customer=createCustomer("检查本人","closure-check-own");other=createCustomer("检查他人","closure-check-other");
        station=createStation("本人资产站");product=createProduct("桶装水",1,"12.00","30.00",1,"8.00");token=customerToken(customer);
    }
    Map<String,Object> facts() {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String table:List.of("customer","user_token","orders","payment_record","customer_deposit_account","deposit_record","customer_barrel_asset","customer_barrel_lot","customer_barrel_over","ticket_account","ticket_lot","barrel_record","barrel_right_purchase","barrel_return_detail","customer_refusal_case","order_barrel_exception"))
            result.put(table,jdbc.queryForList("select * from "+table));
        return result;
    }
    boolean category(Api response,String code) {
        for(var item:response.data().path("blockingItems"))if(code.equals(item.path("category").asText()))return true;
        return false;
    }
    @Test void emptyCompleteReadDoesNotDeleteOrAuthorizeDeletion() {
        seed();var before=facts();Api r=get(PATH,token);assertEquals(0,r.code(),r.toString());
        assertTrue(r.data().path("complete").asBoolean());assertTrue(r.data().path("clear").asBoolean());
        assertEquals("仅为注销前检查，不会注销账户",r.data().path("notice").asText());
        assertFalse(r.data().has("canDelete"));assertFalse(r.data().has("openid"));assertFalse(r.data().has("customerId"));assertEquals(before,facts());
    }
    @Test void closedUnboundDepositOnlyStationIsIncludedAndNotOffsetAgainstAnotherStation() {
        seed();jdbc.update("update station set status=2 where id=?",station);createDepositBalance(customer,station,"60.00");
        long second=createStation("另一个资产站");createDepositBalance(customer,second,"-60.00");var before=facts();Api r=get(PATH,token);
        assertEquals(0,r.code(),r.toString());assertFalse(r.data().path("complete").asBoolean());assertFalse(r.data().path("clear").asBoolean());
        assertTrue(category(r,"DEPOSIT"));assertTrue(category(r,"MANUAL_REVIEW"));assertEquals(2,r.data().path("stations").size());
        assertEquals(0,intOf("select count(*) from customer_station_config where customer_id=?",customer));assertEquals(before,facts());
    }
    @Test void employeeAndForgedIdentityCannotQueryAnotherAccount() {
        seed();createDepositBalance(other,station,"100.00");
        long manager=createStaff("站长","STATION_MANAGER",station,1),delivery=createStaff("配送员","DELIVERY",station,1);
        assertEquals(1,get(PATH,staffToken(manager,"STATION_MANAGER",station)).code());assertEquals(1,get(PATH,staffToken(delivery,"DELIVERY",station)).code());
        assertEquals(1,get(PATH+"?customerId="+other,token).code());assertEquals(1,get(PATH+"?stationId="+station,token).code());
        Api own=get(PATH,token);assertEquals(0,own.code(),own.toString());assertTrue(own.data().path("clear").asBoolean());assertEquals(0,own.data().path("stations").size());
    }
    @Test void aggregateIncludesOlderItemsBeyondDisplayLimitsAndAllHiddenStations() {
        seed();for(int i=0;i<205;i++) {long s=createStation("停业事实站"+i);jdbc.update("update station set status=2 where id=?",s);createDepositBalance(customer,s,"1.00");}
        for(int i=0;i<501;i++)createPaymentRecord(null,customer,station,"8.00",2,1);
        Api r=get(PATH,token);assertEquals(0,r.code(),r.toString());assertEquals(206,r.data().path("stations").size());
        long pending=0;for(var item:r.data().path("blockingItems"))if("PAYMENT_PENDING".equals(item.path("category").asText()))pending+=item.path("count").asLong();
        assertEquals(501,pending);assertFalse(r.data().path("clear").asBoolean());
    }
    @Test void lotOnlyTicketsAndRightsAndBothSignsOfPhysicalOverAreNotLost() {
        seed();createBarrelLot("DP_CHECK",customer,station,product,"30.00",2,2);
        insert("insert into ticket_lot(lot_no,customer_id,station_id,product_id,unit_price,qty,remain_qty) values('TM_CHECK',?,?,?,8,2,2)",customer,station,product);
        createBarrelOver(customer,station,product,2);long otherProduct=createProduct("另一商品",1,"12.00","30.00",1,"8.00");createBarrelOver(customer,station,otherProduct,-2);
        Api r=get(PATH,token);assertEquals(0,r.code(),r.toString());assertTrue(category(r,"BARREL_RIGHTS"));assertTrue(category(r,"TICKETS"));assertTrue(category(r,"BARREL_OWED"));assertTrue(category(r,"MANUAL_REVIEW"));
    }
    @Test void independentPurchasePendingAndReturnReceivedRemainBlockingAfterStationClosure() {
        seed();long payment=createPaymentRecord(null,customer,station,"30.00",2,1);
        insert("insert into barrel_right_purchase(customer_id,station_id,product_id,quantity,unit_price,amount,payment_id,idempotency_key,status) values(?,?,?,1,30,30,?,'pending-check','PENDING')",customer,station,product,payment);
        long record=insert("insert into barrel_record(customer_id,station_id,product_id,type,quantity,status) values(?,?,?,2,1,2)",customer,station,product);
        jdbc.update("insert into barrel_return_detail(record_id,customer_id,station_id,idempotency_key,pickup_mode,status) values(?,?,?,'return-check','STORE','RECEIVED')",record,customer,station);
        jdbc.update("update station set status=2 where id=?",station);var before=facts();Api r=get(PATH,token);
        assertEquals(0,r.code(),r.toString());assertTrue(category(r,"RIGHTS_PURCHASE_PENDING"));assertTrue(category(r,"RETURN_PENDING"));assertEquals(before,facts());
    }
    @Test void completedCashRefundLedgerIsHistoryNotNewDebt() {
        seed();long address=createAddress(customer,"合成地址");long order=createOrderFull(customer,address,station,product,4,2,2,"12.00","0.00","12.00",false,1);
        long original=createPaymentRecord(order,customer,station,"12.00",2,3);createPaymentRecord(order,customer,station,"-12.00",2,3);
        jdbc.update("update orders set payment_status=3 where id=?",order);var before=facts();Api r=get(PATH,token);
        assertEquals(0,r.code(),r.toString());assertTrue(r.data().path("clear").asBoolean());assertFalse(category(r,"DEBT"));assertEquals(before,facts());
    }

    @Test void refusalDebtUsesSettlementStationAndFreezeStopsBeingActiveAfterCollection() {
        seed();long moneyStation=createStation("收款责任站"),address=createAddress(customer,"合成地址");
        long order=createOrderFull(customer,address,station,product,4,1,2,"12.00","0.00","12.00",false,1);
        jdbc.update("update orders set settle_station_id=? where id=?",moneyStation,order);
        jdbc.update("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,create_time) values(?,777,?,?,?,1,888,now())",order,customer,station,moneyStation);
        var before=facts();Api pending=get(PATH,token);assertFalse(pending.data().path("complete").asBoolean());
        assertTrue(category(pending,"DEBT"));assertTrue(category(pending,"MANUAL_REVIEW"));
        for(var item:pending.data().path("blockingItems"))if("DEBT".equals(item.path("category").asText())) {
            assertEquals(moneyStation,item.path("stationId").asLong());assertEquals("12.00",item.path("amount").decimalValue().setScale(2).toPlainString());
        }
        assertEquals(before,facts());
        // A synthetic fixture state change models the existing collection result; the GET never performs it.
        jdbc.update("update orders set payment_status=2 where id=?",order);
        createPaymentRecord(order,customer,moneyStation,"12.00",2,2);
        before=facts();Api collected=get(PATH,token);assertTrue(collected.data().path("clear").asBoolean());assertEquals(before,facts());
    }
    @Test void outgoingRefundProvesOwnOrphanReceiptNeedsManualReviewWithoutLeakingOtherAccount() {
        seed();long outgoing=createPaymentRecord(null,customer,station,"-8.00",2,3);
        jdbc.update("insert into ticket_exit_refund(original_payment_id,refund_payment_id,quantity,amount,operator_id,create_time) values(999999,?,1,8,888,now())",outgoing);
        var before=facts();Api own=get(PATH,token);assertFalse(own.data().path("complete").asBoolean());assertFalse(own.data().path("clear").asBoolean());
        assertTrue(category(own,"MANUAL_REVIEW"));assertEquals(before,facts());
        Api unrelated=get(PATH,customerToken(other));assertTrue(unrelated.data().path("clear").asBoolean());assertEquals(0,unrelated.data().path("stations").size());
    }
    @Test void exhaustedPaidPurchaseWithoutOriginalMoneyAndUnknownPaymentStateCannotAppearClear() {
        seed();long lot=createBarrelLot("DP_MISSING_MONEY",customer,station,product,"30.00",1,0);
        insert("insert into barrel_right_purchase(customer_id,station_id,product_id,quantity,unit_price,amount,payment_id,lot_id,idempotency_key,status) values(?,?,?,1,30,30,999998,?,'missing-money','PAID')",customer,station,product,lot);
        createPaymentRecord(null,customer,station,"8.00",2,99);
        var before=facts();Api own=get(PATH,token);assertFalse(own.data().path("complete").asBoolean());assertFalse(own.data().path("clear").asBoolean());
        assertTrue(category(own,"MANUAL_REVIEW"));assertFalse(category(own,"DEBT"));assertEquals(before,facts());
    }

    @Test void paidMoneyWithoutAnyOwnedBusinessVoucherIsManualReviewRatherThanClear() {
        seed();createPaymentRecord(null,customer,station,"30.00",2,2);
        var before=facts();Api own=get(PATH,token);assertEquals(0,own.code(),own.toString());
        assertFalse(own.data().path("complete").asBoolean());assertFalse(own.data().path("clear").asBoolean());
        assertTrue(category(own,"MANUAL_REVIEW"));assertFalse(category(own,"DEBT"));assertEquals(before,facts());
    }

    @Test void exhaustedTicketLotWithRealOwnOriginalReceiptIsNormalHistory() {
        seed();long original=createPaymentRecord(null,customer,station,"8.00",2,2);
        jdbc.update("update payment_record set ticket_qty=1,ticket_water_type_id=? where id=?",product,original);
        insert("insert into ticket_lot(lot_no,customer_id,station_id,product_id,unit_price,qty,remain_qty,payment_record_id,status) values('TM_SPENT',?,?,?,8,1,0,?,2)",customer,station,product,original);
        var before=facts();Api own=get(PATH,token);assertEquals(0,own.code(),own.toString());
        assertTrue(own.data().path("complete").asBoolean());assertTrue(own.data().path("clear").asBoolean());assertEquals(before,facts());
    }
}
