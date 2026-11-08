package com.example.aquaflow.integration;

import com.example.aquaflow.dto.HistoricalAssetClaimDTO;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.HistoricalAssetClaimService;
import com.example.aquaflow.service.StationAdjustmentService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.util.AuthContext;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 临时合成MySQL专用。Spy只安排真实SQL的时序，不替换账户、行锁、事务或异常结果。 */
class AssetAdjustmentGapLockIntegrationTest extends AbstractIntegrationTest {
    @Autowired HistoricalAssetClaimService claimService;
    @Autowired StationAdjustmentService adjustments;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SqlSessionTemplate sql;
    @MockitoSpyBean CustomerDepositAccountMapper deposits;
    @MockitoSpyBean DepositRecordMapper records;
    @MockitoSpyBean CustomerBarrelLotMapper lots;
    @MockitoSpyBean HistoricalAssetClaimMapper claims;
    private long station, manager, first, second, product;
    private void seed() {
        station=createStation("合成缺行并发站");manager=createStaff("合成站长","STATION_MANAGER",station,1);
        first=createCustomer("合成甲","gap-first");second=createCustomer("合成乙","gap-second");
        product=createProduct("合成桶装水",1,"20.00","30.00",1,"18.00");
        createInventoryFull(station,product,0,0,"0.00");
    }
    private void manager() { AuthContext.set(new AuthContext.AuthUser(manager,"staff","STATION_MANAGER",station)); }
    @AfterEach void clearContext() { AuthContext.clear(); }
    private long depositAdjustment(long customer,String token,int amount) {
        createCustomerStationConfig(customer,station,0);manager();
        return adjustments.create(customer,"DEPOSIT_GRANT",null,null,BigDecimal.valueOf(amount),null,"合成补入",null,token).getId();
    }
    private long claim(long customer,boolean withProducts) {
        AuthContext.set(new AuthContext.AuthUser(customer,"customer",null,null));
        var items=withProducts?List.of(new HistoricalAssetClaimDTO.Item(product,BigDecimal.valueOf(2),null,BigDecimal.valueOf(3),new BigDecimal("30.00"))):List.<HistoricalAssetClaimDTO.Item>of();
        return claimService.submit(new HistoricalAssetClaimDTO(station,"gap-claim-"+customer,true,new BigDecimal("7.00"),"合成未入账材料",items)).getId();
    }
    private void approve(long id) { claimService.review(id,new HistoricalAssetClaimDTO.Review(true,"已核对合成材料")); }
    private void together(Runnable a,Runnable b) throws Exception {
        var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var x=CompletableFuture.runAsync(()->{await(start);manager();try {a.run();}finally {AuthContext.clear();}},executor);
            var y=CompletableFuture.runAsync(()->{await(start);manager();try {b.run();}finally {AuthContext.clear();}},executor);
            start.countDown();
            // allOf即使一方失败也等另一方结束；不能让下一例清表时还有本例持锁连接。
            CompletableFuture.allOf(x,y).get(300,TimeUnit.SECONDS);
        } finally {
            start.countDown();executor.shutdown();
            assertTrue(executor.awaitTermination(300,TimeUnit.SECONDS),"synthetic SQL workers did not release their connections");
        }
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(15,TimeUnit.SECONDS)) throw new AssertionError("synthetic start timed out"); }
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static void meet(CyclicBarrier barrier) {
        try {barrier.await(15,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError("synthetic SQL barrier failed",e);}
    }
    private void requireBothAccountsExistBeforeTheirFirstCurrentRead() {
        // 旧实现两个缺行FOR UPDATE均返回null并持相容gap锁，然后各自upsert形成死锁。
        // 修复先插中性行，两份当前读均应见到自己的真实0余额账户。
        var real=sql.getMapper(CustomerDepositAccountMapper.class);
        var arrivals=new AtomicInteger();var barrier=new CyclicBarrier(2);
        doAnswer(call -> {
            var account=real.getForUpdate(call.getArgument(0),call.getArgument(1));
            if(arrivals.incrementAndGet()<=2) {meet(barrier);assertNotNull(account,"must upsert before any current read");}
            return account;
        }).when(deposits).getForUpdate(anyLong(),anyLong());
    }
    @Test void differentCustomerDepositAdjustmentsDoNotUpgradeSharedMissingAccountGaps() throws Exception {
        seed();long a=depositAdjustment(first,"gap-adjust-first",5),b=depositAdjustment(second,"gap-adjust-second",7);
        requireBothAccountsExistBeforeTheirFirstCurrentRead();
        together(()->adjustments.execute(a),()->adjustments.execute(b));
        assertEquals(12,decimalOf("select sum(balance) from customer_deposit_account").intValueExact());
        assertEquals(2,intOf("select count(*) from deposit_record"));
        assertEquals(2,intOf("select count(*) from station_adjustment where status='EFFECTIVE'"));
        assertEquals(0,intOf("select count(*) from customer_barrel_over"));
    }
    @Test void depositOnlyClaimsHaveNeutralRowsBeforeLockingAndNoPhysicalBarrels() throws Exception {
        seed();long a=claim(first,false),b=claim(second,false);requireBothAccountsExistBeforeTheirFirstCurrentRead();
        together(()->approve(a),()->approve(b));
        assertEquals(14,decimalOf("select sum(balance) from customer_deposit_account").intValueExact());
        assertEquals(2,intOf("select count(*) from historical_asset_claim where status='APPROVED'"));
        assertEquals(2,intOf("select count(*) from deposit_record"));
        assertEquals(0,intOf("select count(*) from customer_barrel_over"));
    }
    @Test void realDeadlockAfterLedgerWritesRollsBackThenReplaysWholeClaimExactlyOnce() throws Exception {
        seed();long a=claim(first,false),b=claim(second,false);
        var real=sql.getMapper(DepositRecordMapper.class);var inserts=new AtomicInteger();var barrier=new CyclicBarrier(2);
        doAnswer(call -> {
            DepositRecord record=call.getArgument(0);real.insert(record);
            if(inserts.incrementAndGet()<=2) {
                // 两连接均已改余额、写流水和审批CAS，再强制相反的真实行锁，MySQL必须选一个牺牲事务。
                meet(barrier);long other=record.getCustomerId()==first?second:first;
                jdbc.queryForList("select id from customer_deposit_account where customer_id=? and station_id=? for update",other,station);
            }
            return null;
        }).when(records).insert(any(DepositRecord.class));
        together(()->approve(a),()->approve(b));
        assertTrue(inserts.get()>=3,"a deadlock victim must retry after rollback");
        assertEquals(14,decimalOf("select sum(balance) from customer_deposit_account").intValueExact());
        assertEquals(2,intOf("select count(*) from deposit_record"));assertEquals(2,intOf("select count(*) from station_adjustment"));
        assertEquals(2,intOf("select count(*) from customer_station_config"));
        assertEquals(2,intOf("select count(*) from historical_asset_claim where status='APPROVED'"));
        assertEquals(0,intOf("select count(*) from payment_record"));
        manager();approve(a);approve(b);assertEquals(2,intOf("select count(*) from deposit_record"));
    }
    @Test void emptyLotGapDeadlockRetriesMixedClaimsWithoutDuplicatingAnyAsset() throws Exception {
        seed();long a=claim(first,true),b=claim(second,true);
        var real=sql.getMapper(CustomerBarrelLotMapper.class);var reads=new AtomicInteger();var barrier=new CyclicBarrier(2);
        doAnswer(call -> {
            var result=real.listAvailableForUpdate(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            if(reads.incrementAndGet()<=2) meet(barrier);
            return result;
        }).when(lots).listAvailableForUpdate(anyLong(),anyLong(),anyLong());
        together(()->approve(a),()->approve(b));
        verify(claims,atLeast(3)).lock(anyLong());
        assertEquals(4,intOf("select sum(remain_qty) from customer_barrel_lot"));
        assertEquals(6,intOf("select sum(remain_quantity) from ticket_account"));
        assertEquals(14,decimalOf("select sum(balance) from customer_deposit_account").intValueExact());
        assertEquals(2,intOf("select count(*) from barrel_record"));assertEquals(2,intOf("select count(*) from ticket_record"));
        assertEquals(2,intOf("select count(*) from deposit_record"));assertEquals(6,intOf("select count(*) from station_adjustment"));
        assertEquals(0,intOf("select count(*) from customer_barrel_over where over_qty<>0"));
        assertEquals(2,intOf("select count(*) from historical_asset_claim where status='APPROVED'"));
    }
    @Test void independentAdjustmentsForOneCustomerSerializeTheirCurrentBalances() throws Exception {
        seed();long a=depositAdjustment(first,"same-customer-first",5);manager();
        long b=adjustments.create(first,"DEPOSIT_GRANT",null,null,BigDecimal.valueOf(7),null,"合成补入",null,"same-customer-second").getId();
        together(()->adjustments.execute(a),()->adjustments.execute(b));
        assertEquals(12,decimalOf("select balance from customer_deposit_account").intValueExact());
        assertEquals(2,intOf("select count(*) from deposit_record"));
        assertEquals(2,intOf("select count(*) from station_adjustment where status='EFFECTIVE'"));
        assertEquals(0,decimalOf("select min(cast(json_extract(before_snapshot,'$.deposit') as decimal(10,2))) from station_adjustment").intValueExact());
        assertEquals(12,decimalOf("select max(cast(json_extract(after_snapshot,'$.deposit') as decimal(10,2))) from station_adjustment").intValueExact());
    }
    @Test void neutralAccountUpsertsDoNotRewriteExistingAmountsOrTimestamps() {
        seed();createTicketAccount(first,station,product,4);createDepositBalance(first,station,"11.00");
        jdbc.update("update ticket_account set update_time='2026-01-01 12:00:00'");
        jdbc.update("update customer_deposit_account set update_time='2026-01-01 12:00:00'");
        var ticket=jdbc.queryForMap("select * from ticket_account");
        var deposit=jdbc.queryForMap("select * from customer_deposit_account");
        new TransactionTemplate(transactions).execute(status -> {
            sql.getMapper(TicketAccountMapper.class).lockOrCreate(first,station,product);
            deposits.lockOrCreate(first,station);return null;
        });
        assertEquals(ticket,jdbc.queryForMap("select * from ticket_account"));
        assertEquals(deposit,jdbc.queryForMap("select * from customer_deposit_account"));
        assertEquals(0,intOf("select count(*) from deposit_record"));assertEquals(0,intOf("select count(*) from customer_barrel_over"));
    }
}
