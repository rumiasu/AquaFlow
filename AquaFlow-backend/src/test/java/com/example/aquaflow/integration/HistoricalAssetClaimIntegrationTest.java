package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.config.HistoricalAssetClaimSchemaGuard;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

/** 独立合成库：本人已确认清单→本站核对→绑定/资产追加同事务，无第三次确认。 */
class HistoricalAssetClaimIntegrationTest extends AbstractIntegrationTest {
    private long station, product, customer, manager;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.aquaflow.service.HistoricalAssetClaimService claimService;
    private void seed() {
        station=createStation("历史认领测试站");
        product=createProduct("合成桶装水",1,"20.00","30.00",1,"18.00");
        createInventoryFull(station,product,0,0,"0.00");
        customer=createCustomer("合成申请人","synthetic-claim-customer");
        manager=createStaff("合成站长","STATION_MANAGER",station,1);
    }
    private String managerToken() { return staffToken(manager,"STATION_MANAGER",station); }
    private String body(String key) {
        return "{\"stationId\":"+station+",\"clientToken\":\""+key+"\",\"confirmed\":true,"
            +"\"depositBalance\":75.00,\"note\":\"本人核对：未入账旧押金75元，桶资产2，票3\",\"items\":[{"
            +"\"productId\":"+product+",\"rights\":2,\"tickets\":3,\"unitPrice\":30.00}]}";
    }
    private Api submit(String key) { return post("/api/asset-claims",customerToken(customer),body(key)); }
    private long claim() { var r=submit("claim-original"); assertTrue(r.isSuccess(),r.toString()); return r.data().path("id").asLong(); }
    private Api review(long id,boolean approve) {
        return post("/api/manager/asset-claims/"+id+"/review",managerToken(),
            "{\"approve\":"+approve+",\"note\":\"已核对合成历史台账\"}");
    }
    private int rows(String table) { return intOf("select count(*) from "+table); }

    @Test
    void unboundCustomerSubmitsWithoutBindingOrAssetWrites() {
        seed(); long id=claim();
        assertEquals(0,intOf("select json_contains_path(request_json, 'one', '$.items[0].held') from historical_asset_claim where id="+id));
        assertEquals("PENDING",get("/api/asset-claims/"+id,customerToken(customer)).data().path("status").asText());
        var mine=get("/api/asset-claims?stationId="+station,customerToken(customer));
        assertTrue(mine.isSuccess(),mine.toString());assertEquals(1,mine.data().size());
        assertEquals(0,rows("customer_station_config")); assertEquals(0,rows("station_adjustment"));
        assertEquals(0,rows("payment_record")); assertEquals(0,rows("customer_barrel_lot"));
        var pending=get("/api/manager/asset-claims",managerToken());
        assertTrue(pending.isSuccess(),pending.toString()); assertEquals(1,pending.data().size());
        assertFalse(pending.body().toString().contains("openid"));
    }
    @Test
    void approvalAppendsAssetsWithoutInventingDifferencesOrNewReceipts() {
        seed(); long id=claim(); var r=review(id,true); assertTrue(r.isSuccess(),r.toString());
        assertEquals("APPROVED",r.data().path("status").asText());
        assertEquals(1,rows("customer_station_config"));
        assertEquals(0,intOf("select offline_payment_enabled from customer_station_config"));
        assertEquals(2,intOf("select sum(remain_qty) from customer_barrel_lot"));
        assertEquals(0,intOf("select over_qty from customer_barrel_over"));
        assertEquals(0,intOf("select count(*) from station_adjustment where adjust_type='OVER_ADJUST'"));
        assertEquals(75,decimalOf("select balance from customer_deposit_account").intValueExact());
        assertEquals(60,decimalOf("select right_amount from customer_barrel_asset").intValueExact());
        assertEquals(3,intOf("select remain_quantity from ticket_account"));
        assertEquals(3,intOf("select source_type from ticket_lot"));
        assertEquals(3,intOf("select price_source from ticket_lot"));
        assertEquals(1,intOf("select is_migrated from ticket_lot"));
        assertEquals(0,rows("payment_record")); assertEquals(0,rows("orders"));
        assertEquals(0,rows("barrel_right_reservation"));
        assertEquals("APPROVED",get("/api/manager/asset-claims/"+id,managerToken()).data().path("status").asText());
        assertTrue(get("/api/manager/asset-claims",managerToken()).data().isEmpty());
    }
    @Test
    void originalKeyReplaysButChangedSnapshotAndNewPendingKeyAreRejected() {
        seed(); long id=claim(); assertEquals(id,submit("claim-original").data().path("id").asLong());
        assertFalse(post("/api/asset-claims",customerToken(customer),body("claim-original").replace("75.00","76.00")).isSuccess());
        assertFalse(submit("another-key").isSuccess()); assertEquals(1,rows("historical_asset_claim"));
    }
    @Test
    void rejectionDoesNotBindOrPostAndAllowsNewApplication() {
        seed(); long id=claim(); assertTrue(review(id,false).isSuccess());
        assertTrue(review(id,false).isSuccess()); assertFalse(review(id,true).isSuccess());
        assertEquals(0,rows("customer_station_config")); assertEquals(0,rows("station_adjustment"));
        assertTrue(submit("corrected-claim").isSuccess()); assertEquals(2,rows("historical_asset_claim"));
    }
    @Test
    void approvedClaimCannotBeImportedAgainEvenAfterAssetsWereUsed() {
        seed(); long id=claim(); assertTrue(review(id,true).isSuccess()); int count=rows("station_adjustment");
        assertTrue(review(id,true).isSuccess()); assertEquals(count,rows("station_adjustment"));
        assertFalse(review(id,false).isSuccess()); assertFalse(submit("second-import").isSuccess());
        assertEquals(id,submit("claim-original").data().path("id").asLong());
    }
    @Test
    void crossCustomerCrossStationAndDeliveryAreRejectedWithoutDisclosure() {
        seed(); long id=claim(); long otherCustomer=createCustomer("另一个合成人","synthetic-other");
        long otherStation=createStation("另一个合成站"), otherManager=createStaff("异站站长","STATION_MANAGER",otherStation,1);
        assertFalse(get("/api/asset-claims/"+id,customerToken(otherCustomer)).isSuccess());
        assertFalse(get("/api/asset-claims/"+id,managerToken()).isSuccess());
        assertFalse(post("/api/manager/asset-claims/"+id+"/review",staffToken(otherManager,"STATION_MANAGER",otherStation),"{\"approve\":true}").isSuccess());
        long delivery=createStaff("合成配送","DELIVERY",station,1);
        assertFalse(get("/api/manager/asset-claims",staffToken(delivery,"DELIVERY",station)).isSuccess());
        assertEquals(0,rows("station_adjustment"));
    }
    @Test
    void missingCustomerConfirmationAndMalformedFiguresDoNotCreateRequests() {
        seed();
        for(String b:new String[]{body("a").replace("true","false"),body("b").replace("75.00","75.001"),
            body("c").replace("\"rights\":2","\"rights\":2,\"held\":-1"),body("d").replace("\"rights\":2","\"rights\":1.5")})
            assertFalse(post("/api/asset-claims",customerToken(customer),b).isSuccess(),b);
        assertEquals(0,rows("historical_asset_claim"));
    }
    @Test
    void existingPositiveDifferenceAndAllAssetBalancesArePreservedAndAppended() { appendToExisting(2); }
    @Test
    void existingNegativeDifferenceIsNotRecalculated() { appendToExisting(-2); }
    private void appendToExisting(int over) {
        seed();long id=claim();
        long lot=createBarrelLot("synthetic-existing",customer,station,product,"30.00",4,4);
        createBarrelAsset(customer,station,product,4,"120.00");createBarrelOver(customer,station,product,over);
        if(over>0) jdbc.update("update customer_barrel_over set owed_since='2026-01-01 12:00:00'");
        createTicketAccount(customer,station,product,5);createDepositBalance(customer,station,"10.00");
        var oldLot=jdbc.queryForMap("select * from customer_barrel_lot where id=?",lot);
        var oldOver=jdbc.queryForMap("select * from customer_barrel_over");
        var oldTicket=jdbc.queryForMap("select * from ticket_lot");
        jdbc.update("insert into barrel_right_reservation(customer_id,station_id,product_id,owner_type,owner_id,quantity,pickup_qty) values(?,?,?,'ORDER',987654,1,0)",customer,station,product);
        var reservation=jdbc.queryForMap("select * from barrel_right_reservation");
        assertTrue(review(id,true).isSuccess());
        assertEquals(6,intOf("select sum(remain_qty) from customer_barrel_lot"));
        assertEquals(6,intOf("select quantity from customer_barrel_asset"));
        assertEquals(180,decimalOf("select right_amount from customer_barrel_asset").intValueExact());
        assertEquals(8,intOf("select remain_quantity from ticket_account"));
        assertEquals(85,decimalOf("select balance from customer_deposit_account").intValueExact());
        assertEquals(oldLot,jdbc.queryForMap("select * from customer_barrel_lot where id=?",lot));
        assertEquals(oldOver,jdbc.queryForMap("select * from customer_barrel_over"));
        assertEquals(oldTicket,jdbc.queryForMap("select * from ticket_lot where id=?",oldTicket.get("id")));
        assertEquals(reservation,jdbc.queryForMap("select * from barrel_right_reservation"));
        assertEquals(0,intOf("select count(*) from station_adjustment where adjust_type='OVER_ADJUST'"));
    }
    @Test
    void downstreamFailureRollsBackBindingEarlierLedgerWritesAndDecision() {
        seed(); long id=claim();
        jdbc.execute("create trigger synthetic_claim_fail before insert on deposit_record for each row signal sqlstate '45000' set message_text='synthetic rollback probe'");
        try {
            assertFalse(review(id,true).isSuccess()); assertEquals(0,rows("customer_station_config"));
            assertEquals(0,rows("station_adjustment")); assertEquals(0,rows("customer_barrel_lot"));
            assertEquals(0,rows("barrel_record"));
            assertEquals("PENDING",get("/api/asset-claims/"+id,customerToken(customer)).data().path("status").asText());
        } finally { jdbc.execute("drop trigger synthetic_claim_fail"); }
        assertTrue(review(id,true).isSuccess());
    }
    @Test
    void simultaneousApprovalsHaveOneLedgerEffect() {
        seed(); long id=claim(); var start=new CountDownLatch(1);
        var a=CompletableFuture.supplyAsync(()->{await(start);return review(id,true);});
        var b=CompletableFuture.supplyAsync(()->{await(start);return review(id,true);}); start.countDown();
        assertTrue(a.join().isSuccess()); assertTrue(b.join().isSuccess());
        assertEquals(2,intOf("select sum(remain_qty) from customer_barrel_lot")); assertEquals(1,rows("deposit_record"));
        assertEquals(1,rows("ticket_lot"));
    }
    @Test
    void simultaneousOriginalKeySubmissionsReturnOneDurableApplication() {
        seed(); var start=new CountDownLatch(1);
        var a=CompletableFuture.supplyAsync(()->{await(start);return submit("same-key");});
        var b=CompletableFuture.supplyAsync(()->{await(start);return submit("same-key");}); start.countDown();
        var first=a.join();var second=b.join();assertTrue(first.isSuccess());assertTrue(second.isSuccess());
        assertEquals(first.data().path("id"),second.data().path("id"));assertEquals(1,rows("historical_asset_claim"));
    }
    @Test
    void simultaneousDifferentKeysCannotOpenTwoPendingClaims() {
        seed();var start=new CountDownLatch(1);
        var a=CompletableFuture.supplyAsync(()->{await(start);return submit("first-key");});
        var b=CompletableFuture.supplyAsync(()->{await(start);return submit("second-key");});start.countDown();
        assertNotEquals(a.join().isSuccess(),b.join().isSuccess());assertEquals(1,rows("historical_asset_claim"));
    }
    @Test
    void zeroAssetExistingBindingKeepsItsOfflinePermission() {
        seed();createCustomerStationConfig(customer,station,1);long id=claim();assertTrue(review(id,true).isSuccess());
        assertEquals(1,intOf("select offline_payment_enabled from customer_station_config"));
    }
    @Test
    void depositOnlyClaimDoesNotInventBarrelOrTicketAssets() {
        seed();String payload=body("cash-only");payload=payload.substring(0,payload.indexOf("\"items\""))+"\"items\":[]}";
        var submitted=post("/api/asset-claims",customerToken(customer),payload);assertTrue(submitted.isSuccess(),submitted.toString());
        assertTrue(review(submitted.data().path("id").asLong(),true).isSuccess());
        assertEquals(75,decimalOf("select balance from customer_deposit_account").intValueExact());
        assertEquals(0,rows("customer_barrel_lot"));assertEquals(0,rows("ticket_lot"));assertEquals(0,rows("payment_record"));
    }
    @Test
    void inferredPriceRemainsManualAndRetryUsesOriginalResultAfterProductRemoval() {
        seed();String payload=body("inferred").replace(",\"unitPrice\":30.00","");
        var submitted=post("/api/asset-claims",customerToken(customer),payload);assertTrue(submitted.isSuccess());
        long id=submitted.data().path("id").asLong();assertTrue(review(id,true).isSuccess());
        assertEquals(3,intOf("select source_type from customer_barrel_lot"));assertEquals(1,intOf("select is_migrated from customer_barrel_lot"));
        jdbc.update("delete from inventory where station_id=? and product_id=?",station,product);
        assertTrue(post("/api/asset-claims",customerToken(customer),payload).isSuccess());assertTrue(review(id,true).isSuccess());
    }
    @Test
    void foreignProductDuplicateRowsAndOutOfRangeIntegerAreRejected() {
        seed();long other=createProduct("非本站商品",1,"20.00","30.00",1,"18.00");
        assertFalse(post("/api/asset-claims",customerToken(customer),body("foreign").replace("\"productId\":"+product,"\"productId\":"+other)).isSuccess());
        String payload=body("duplicate"),item=payload.substring(payload.indexOf("[{" )+1,payload.lastIndexOf(']'));
        assertFalse(post("/api/asset-claims",customerToken(customer),payload.replace("["+item+"]","["+item+","+item+"]")).isSuccess());
        assertFalse(post("/api/asset-claims",customerToken(customer),body("large").replace("\"rights\":2","\"rights\":2147483648")).isSuccess());
        assertEquals(0,rows("historical_asset_claim"));
    }
    @Test
    void missingDecisionOrRejectionReasonDoesNotChangePendingClaim() {
        seed();long id=claim();
        for(String payload:new String[]{"{}","{\"approve\":false}","{\"approve\":false,\"note\":\"   \"}"})
            assertFalse(post("/api/manager/asset-claims/"+id+"/review",managerToken(),payload).isSuccess());
        assertEquals("PENDING",get("/api/asset-claims/"+id,customerToken(customer)).data().path("status").asText());
    }
    @Test
    void equivalentIntegralQuantitiesAndMoneyRestoreOriginalApplication() {
        seed();long id=claim();String payload=body("claim-original").replace("75.00","75.0").replace("\"rights\":2","\"rights\":2.0");
        var replay=post("/api/asset-claims",customerToken(customer),payload);assertTrue(replay.isSuccess(),replay.toString());
        assertEquals(id,replay.data().path("id").asLong());
    }
    @Test
    void requestKeyIsCaseSensitiveAndCustomerScoped() {
        seed();var first=submit("CaseKey");assertTrue(first.isSuccess());assertTrue(review(first.data().path("id").asLong(),false).isSuccess());
        assertTrue(submit("casekey").isSuccess());
        long other=createCustomer("合成第二人","synthetic-claim-other");
        var otherClaim=post("/api/asset-claims",customerToken(other),body("casekey"));assertTrue(otherClaim.isSuccess());
        assertEquals(other,otherClaim.data().path("customerId").asLong());assertEquals(3,rows("historical_asset_claim"));
    }
    @Test
    void databaseFenceRetainsApprovedCustomerEvenWithoutServiceLock() {
        seed();long id=claim();assertTrue(review(id,true).isSuccess());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update(
            "insert into historical_asset_claim(customer_id,station_id,client_token,request_digest,request_json,status) select customer_id,station_id,'bypass',request_digest,request_json,'PENDING' from historical_asset_claim where id=?",id));
        assertEquals(1,rows("historical_asset_claim"));
    }
    @Test
    void pendingPaymentDoesNotBlockOrBecomePartOfTheAssetAppend() {
        seed();long id=claim();long payment=createPaymentRecord(null,customer,station,"30.00",1,1);
        var original=jdbc.queryForMap("select * from payment_record where id=?",payment);
        assertTrue(review(id,true).isSuccess());assertEquals(1,rows("customer_station_config"));
        assertEquals(original,jdbc.queryForMap("select * from payment_record where id=?",payment));
        assertEquals(1,rows("payment_record"));
    }
    @Test
    void spentHistoryIsRetainedAlongsideNewAssets() {
        seed();long id=claim();long lot=createBarrelLot("synthetic-spent",customer,station,product,"30.00",2,0);
        var original=jdbc.queryForMap("select * from customer_barrel_lot where id=?",lot);
        assertTrue(review(id,true).isSuccess());assertEquals(2,rows("customer_barrel_lot"));
        assertEquals(original,jdbc.queryForMap("select * from customer_barrel_lot where id=?",lot));
        assertEquals(2,intOf("select sum(remain_qty) from customer_barrel_lot"));
    }
    @Test
    void legacyHeldIsEvidenceOnlyAndNewRequestsDoNotStoreIt() {
        seed();String payload=body("legacy").replace("\"rights\":2","\"rights\":2,\"held\":999");
        var submitted=post("/api/asset-claims",customerToken(customer),payload);assertTrue(submitted.isSuccess());
        long id=submitted.data().path("id").asLong();
        String snapshot=jdbc.queryForObject("select request_json from historical_asset_claim where id=?",String.class,id);
        String digest=jdbc.queryForObject("select request_digest from historical_asset_claim where id=?",String.class,id);
        assertTrue(review(id,true).isSuccess());assertEquals(0,intOf("select over_qty from customer_barrel_over"));
        assertEquals(snapshot,jdbc.queryForObject("select request_json from historical_asset_claim where id=?",String.class,id));
        assertEquals(digest,jdbc.queryForObject("select request_digest from historical_asset_claim where id=?",String.class,id));
        assertTrue(post("/api/asset-claims",customerToken(customer),payload).isSuccess());
        assertFalse(post("/api/asset-claims",customerToken(customer),body("legacy")).isSuccess());
    }
    @Test
    void approveAndRejectRaceLeavesExactlyOneTerminalAndMatchingLedger() {
        seed();long id=claim();var start=new CountDownLatch(1);
        var approve=CompletableFuture.supplyAsync(()->{await(start);return review(id,true);});
        var reject=CompletableFuture.supplyAsync(()->{await(start);return review(id,false);});start.countDown();
        assertNotEquals(approve.join().isSuccess(),reject.join().isSuccess());
        String status=get("/api/asset-claims/"+id,customerToken(customer)).data().path("status").asText();
        assertTrue(java.util.Set.of("APPROVED","REJECTED").contains(status));
        assertEquals("APPROVED".equals(status)?3:0,rows("station_adjustment"));
        assertEquals("APPROVED".equals(status)?1:0,rows("customer_station_config"));
    }
    @Test
    void approvalUsesCurrentBalancesWhenAnotherTransactionCreatesAssetsAfterItsSnapshot() {
        seed();long id=claim();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.execute(status -> {
            // 明确先建立旧RR视图，再让另一连接提交首次账户/桶资产；无sleep与调度猜测。
            assertEquals(0,rows("ticket_account"));assertEquals(0,rows("customer_barrel_lot"));
            CompletableFuture.runAsync(() -> {
                createBarrelLot("synthetic-concurrent",customer,station,product,"30.00",4,4);
                createBarrelAsset(customer,station,product,4,"120.00");createBarrelOver(customer,station,product,2);
                createTicketAccount(customer,station,product,5);createDepositBalance(customer,station,"10.00");
            }).join();
            com.example.aquaflow.util.AuthContext.set(new com.example.aquaflow.util.AuthContext.AuthUser(manager,"staff","STATION_MANAGER",station));
            try { assertEquals("APPROVED",claimService.review(id,new com.example.aquaflow.dto.HistoricalAssetClaimDTO.Review(true,"并发合成账核对")).getStatus()); }
            finally { com.example.aquaflow.util.AuthContext.clear(); }
            return null;
        });
        assertEquals(6,intOf("select sum(remain_qty) from customer_barrel_lot"));
        assertEquals(2,intOf("select over_qty from customer_barrel_over"));
        assertEquals(8,intOf("select remain_quantity from ticket_account"));
        assertEquals(54,decimalOf("select right_amount from ticket_account").intValueExact());
        assertEquals(85,decimalOf("select balance from customer_deposit_account").intValueExact());
        assertEquals(4,intOf("select json_extract(before_snapshot,'$.right') from station_adjustment where adjust_type='BARREL_GRANT'"));
        assertEquals(2,intOf("select over_before from barrel_record where adjustment_id is not null"));
        assertEquals(2,intOf("select over_after from barrel_record where adjustment_id is not null"));
        assertEquals(5,intOf("select json_extract(before_snapshot,'$.ticket') from station_adjustment where adjust_type='TICKET_GRANT'"));
        assertEquals(10,decimalOf("select json_extract(before_snapshot,'$.deposit') from station_adjustment where adjust_type='DEPOSIT_GRANT'").intValueExact());
    }
    @Test
    void schemaGuardRejectsPrefixOnlyIdempotencyIndex() {
        jdbc.execute("alter table historical_asset_claim drop index uk_asset_claim_token, add unique key uk_asset_claim_token(customer_id,client_token(8))");
        try { assertThrows(IllegalStateException.class,()->new HistoricalAssetClaimSchemaGuard(jdbc).verifySchema()); }
        finally { jdbc.execute("alter table historical_asset_claim drop index uk_asset_claim_token, add unique key uk_asset_claim_token(customer_id,client_token)"); }
        new HistoricalAssetClaimSchemaGuard(jdbc).verifySchema();
    }
    @Test
    void schemaGuardRejectsPendingOnlyGeneratedFence() {
        jdbc.execute("alter table historical_asset_claim modify active_customer_id bigint generated always as (case when status='PENDING' then customer_id else null end) stored");
        try {
            assertThrows(IllegalStateException.class,()->new HistoricalAssetClaimSchemaGuard(jdbc).verifySchema());
            jdbc.execute("alter table historical_asset_claim modify active_customer_id bigint generated always as (case when status in ('PENDING_utf8mb4','APPROVED') then customer_id else null end) stored");
            assertThrows(IllegalStateException.class,()->new HistoricalAssetClaimSchemaGuard(jdbc).verifySchema());
            for(String wrong:new String[]{"pending","PEN DING","PEN`DING"}) {
                jdbc.execute("alter table historical_asset_claim modify active_customer_id bigint generated always as (case when status in ('"+wrong+"','APPROVED') then customer_id else null end) stored");
                assertThrows(IllegalStateException.class,()->new HistoricalAssetClaimSchemaGuard(jdbc).verifySchema());
            }
        }
        finally { jdbc.execute("alter table historical_asset_claim modify active_customer_id bigint generated always as (case when status in ('PENDING','APPROVED') then customer_id else null end) stored"); }
        new HistoricalAssetClaimSchemaGuard(jdbc).verifySchema();
    }
    @Test
    void schemaGuardRejectsCaseInsensitiveRequestKeys() {
        jdbc.execute("alter table historical_asset_claim modify client_token varchar(64) character set utf8mb4 collate utf8mb4_general_ci not null");
        try { assertThrows(IllegalStateException.class,()->new HistoricalAssetClaimSchemaGuard(jdbc).verifySchema()); }
        finally { jdbc.execute("alter table historical_asset_claim modify client_token varchar(64) character set utf8mb4 collate utf8mb4_bin not null"); }
        new HistoricalAssetClaimSchemaGuard(jdbc).verifySchema();
    }
    private static void await(CountDownLatch latch) { try {latch.await();} catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);} }

    @Test
    void customerClaimCatalogIncludesOffShelfOwnProductsWithoutInventoryOrPriceFields() {
        seed();jdbc.update("update inventory set enabled=0 where station_id=?",station);
        jdbc.update("update product set status=0 where id=?",product);
        long foreign=createProduct("异站合成私有商品",1,"20.00","30.00",1,"18.00");
        long other=createStation("异站合成站");jdbc.update("update product set owner_station_id=? where id=?",other,foreign);
        createInventoryFull(station,foreign,0,0,"0.00");
        createProduct("未选用合成通用商品",1,"20.00","30.00",1,"18.00");
        var catalog=get("/api/asset-claims/products?stationId="+station,customerToken(customer));
        assertTrue(catalog.isSuccess(),catalog.toString());assertEquals(1,catalog.data().size());
        assertEquals(product,catalog.data().get(0).path("id").asLong());
        var fields=new java.util.HashSet<String>();catalog.data().get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(java.util.Set.of("id","name","spec","category","imageObjectName","imageUrl"),fields);
        assertTrue(catalog.data().get(0).path("imageObjectName").isNull(),"private storage key must not be exposed");
        assertFalse(post("/api/asset-claims",customerToken(customer),body("foreign-own").replace("\"productId\":"+product,"\"productId\":"+foreign)).isSuccess());
        assertEquals(0,rows("historical_asset_claim"));
        var submitted=submit("off-shelf-own");assertTrue(submitted.isSuccess(),submitted.toString());
        assertEquals("待本站核对",submitted.data().path("statusText").asText());
        var approved=review(submitted.data().path("id").asLong(),true);assertTrue(approved.isSuccess(),approved.toString());
        assertEquals("已生效",approved.data().path("statusText").asText());
    }
    @Test
    void customerClaimCatalogRejectsEmployeesAndAnonymousReaders() {
        seed();String path="/api/asset-claims/products?stationId="+station;
        assertFalse(get(path,managerToken()).isSuccess());assertFalse(get(path,null).isSuccess());
        assertEquals(0,rows("historical_asset_claim"));
    }
    @Test
    void customerClaimCatalogRequiresAnExistingStationAndReturnsTrueEmptyCatalog() {
        seed();long empty=createStation("空目录合成站");
        var response=get("/api/asset-claims/products?stationId="+empty,customerToken(customer));
        assertTrue(response.isSuccess(),response.toString());assertTrue(response.data().isEmpty());
        assertFalse(get("/api/asset-claims/products?stationId=999999999",customerToken(customer)).isSuccess());
    }
}
