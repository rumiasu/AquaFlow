package com.example.aquaflow.integration;

import com.example.aquaflow.service.ConfirmedRefusalService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP/MySQL冻结CAS回归；这里只确认信用事实，不办理收款、免债或退款。 */
@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true"})
class ConfirmedRefusalFreezeIntegrationTest extends AbstractIntegrationTest {
    @Autowired ConfirmedRefusalService refusals;
    @Autowired DataSource dataSource;
    long assetStation, debtStation, customer, order;
    String assetManager, debtManager, stranger, delivery, owner;

    void seed() {
        assetStation=createStation("资产站"); debtStation=createStation("债权站");
        long third=createStation("无关站");
        assetManager=staffToken(createStaff("资产站长","STATION_MANAGER",assetStation,1),"STATION_MANAGER",assetStation);
        debtManager=staffToken(createStaff("债权站长","STATION_MANAGER",debtStation,1),"STATION_MANAGER",debtStation);
        stranger=staffToken(createStaff("他站站长","STATION_MANAGER",third,1),"STATION_MANAGER",third);
        delivery=staffToken(createStaff("资产站配送员","DELIVERY",assetStation,1),"DELIVERY",assetStation);
        customer=createCustomer("客户","refusal-freeze-test"); owner=customerToken(customer);
        long product=createProduct("水",1,"20.00","30.00",1,"8.00");
        long address=createAddress(customer,"地址");
        order=createOrderCrossStation(customer,address,assetStation,debtStation,product,3,1,2,"20.00","0.00","20.00");
        jdbc.update("update orders set settle_station_id=? where id=?",debtStation,order);
        jdbc.update("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,note,create_time) values(?,1,?,?,?,0,1,'原拒付确认',now())",order,customer,assetStation,debtStation);
        createCustomerStationConfig(customer,assetStation,1);
        createCustomerStationConfig(customer,debtStation,0);
    }

    Api confirm(String token) {return put("/api/manager/refusal-cases/"+order+"/confirm-freeze",token,"{}");}

    Map<String,Object> businessFacts() {
        Map<String,Object> facts=new LinkedHashMap<>();
        for (String table:List.of("orders","payment_record","deposit_record","customer_deposit_account",
                "customer_barrel_lot","customer_barrel_asset","customer_barrel_over","ticket_lot","ticket_record",
                "customer_station_config")) facts.put(table,jdbc.queryForList("select * from "+table));
        return facts;
    }

    @Test void onlyAssetManagerConfirmsOnceAndMoneyAndCreditSettingsStayUnchanged() {
        seed(); var before=businessFacts();
        for(String token:List.of(debtManager,stranger,delivery,owner)) assertEquals(1,confirm(token).code());
        assertEquals(0,intOf("select asset_freeze_confirmed from customer_refusal_case where order_id=?",order));
        assertEquals(0,confirm(assetManager).code()); assertTrue(refusals.frozen(customer,assetStation));
        var evidence=jdbc.queryForMap("select * from customer_refusal_case where order_id=?",order);
        assertNotNull(evidence.get("asset_confirmed_by")); assertNotNull(evidence.get("asset_confirmed_time"));
        assertEquals("原拒付确认",evidence.get("note"));
        assertEquals(1,confirm(assetManager).code());
        assertEquals(evidence,jdbc.queryForMap("select * from customer_refusal_case where order_id=?",order));
        assertEquals(before,businessFacts());
    }

    @ParameterizedTest @ValueSource(ints={0,2,3,4})
    void paymentOutsidePendingCannotCreateLateFreezeEvidence(int paymentStatus) {
        seed(); jdbc.update("update orders set payment_status=? where id=?",paymentStatus,order);
        var before=businessFacts(); var evidence=jdbc.queryForMap("select * from customer_refusal_case where order_id=?",order);
        assertEquals(1,confirm(assetManager).code()); assertFalse(refusals.frozen(customer,assetStation));
        assertEquals(evidence,jdbc.queryForMap("select * from customer_refusal_case where order_id=?",order));
        assertEquals(before,businessFacts());
    }

    @Test void cancelledOrderCannotCreateLateFreezeEvidence() {
        seed(); jdbc.update("update orders set status=5 where id=?",order);
        var before=businessFacts();
        assertEquals(1,confirm(assetManager).code());
        assertEquals(0,intOf("select asset_freeze_confirmed from customer_refusal_case where order_id=?",order));
        assertFalse(refusals.frozen(customer,assetStation)); assertEquals(before,businessFacts());
    }

    @Test void freezeRechecksPaymentAfterAConcurrentOrderLockCommits() throws Exception {
        seed(); var pool=Executors.newSingleThreadExecutor();
        try(Connection con=dataSource.getConnection()) {
            con.setAutoCommit(false);
            try {
                try(var update=con.prepareStatement("update orders set payment_status=2 where id=?")) {
                    update.setLong(1,order); assertEquals(1,update.executeUpdate());
                }
                var started=new CountDownLatch(1);
                var attempt=pool.submit(()->{started.countDown();return confirm(assetManager);});
                assertTrue(started.await(5,TimeUnit.SECONDS));
                con.commit();
                Api result=attempt.get(30,TimeUnit.SECONDS); assertEquals(1,result.code(),result.toString());
                assertEquals(0,intOf("select asset_freeze_confirmed from customer_refusal_case where order_id=?",order));
                assertEquals(2,intOf("select payment_status from orders where id=?",order));
                assertFalse(refusals.frozen(customer,assetStation));
                assertEquals(0,intOf("select count(*) from payment_record"));
                assertEquals(0,intOf("select count(*) from deposit_record"));
            } finally {con.rollback();}
        } finally {pool.shutdownNow();}
    }
}
