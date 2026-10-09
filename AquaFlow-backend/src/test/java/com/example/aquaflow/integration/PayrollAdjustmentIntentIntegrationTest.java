package com.example.aquaflow.integration;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.service.StaffEarningService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 同一工资录入意图只记一次；新意图仍可另记，内容改变和无编号旧请求明确拒绝。 */
class PayrollAdjustmentIntentIntegrationTest extends AbstractIntegrationTest {
    @Autowired StaffEarningService earnings;
    long station, manager, staff, other;
    String mgr;
    static final String ADJUST="/api/manager/payroll/adjust";
    void seed() {
        station=createStation("工资幂等站");manager=createStaff("站长","STATION_MANAGER",station,1);
        staff=createStaff("配送甲","DELIVERY",station,1);other=createStaff("配送乙","DELIVERY",station,1);
        mgr=staffToken(manager,"STATION_MANAGER",station);
    }
    String body(long employee,Long item,String amount,String note,String key) {
        return "{\"staffId\":"+employee+(item==null?"":",\"itemId\":"+item)+",\"amount\":"+amount
                +",\"note\":\""+note+"\""+(key==null?"":",\"idempotencyKey\":\""+key+"\"")+"}";
    }
    void amount(String expected) {
        assertEquals(0,new BigDecimal(expected).compareTo(decimalOf("select coalesce(sum(amount),0) from staff_earning where station_id=?",station)));
    }

    @Test void oldMissingKeyAndExcessPrecisionRequestsCannotWriteWages() {
        seed();assertEquals(1,post(ADJUST,mgr,body(staff,null,"10","补录",null)).code());
        assertEquals(1,post(ADJUST,mgr,body(staff,null,"10.001","补录","precision")).code());
        assertEquals(1,post(ADJUST,mgr,body(staff,null,"100000000.00","补录","overflow")).code());
        assertThrows(BusinessException.class,()->earnings.adjustEarning(station,staff,null,BigDecimal.TEN,"旧调用"));
        assertEquals(0,intOf("select count(*) from staff_earning"));
    }

    @Test void sameKeySameContentReturnsSuccessAndDifferentKeyCreatesSeparateRealEntry() {
        seed();String b=body(staff,null,"10.00","高温补录","same-intent");
        assertEquals(0,post(ADJUST,mgr,b).code());assertEquals(0,post(ADJUST,mgr,b).code());
        assertEquals(1,intOf("select count(*) from staff_earning"));amount("10.00");
        assertEquals(0,post(ADJUST,mgr,body(staff,null,"10","高温补录","second-intent")).code());
        assertEquals(2,intOf("select count(*) from staff_earning"));amount("20.00");
    }

    @Test void sameKeyCannotChangeStaffItemAmountOrNote() {
        seed();long item=post("/api/manager/earning-items",mgr,"{\"name\":\"补贴\",\"direction\":1}").data().asLong();
        String key="locked-content";assertEquals(0,post(ADJUST,mgr,body(staff,null,"10","原因",key)).code());
        for(String changed:List.of(body(other,null,"10","原因",key),body(staff,item,"10","原因",key),
                body(staff,null,"11","原因",key),body(staff,null,"10","另一个原因",key))) {
            Api r=post(ADJUST,mgr,changed);assertEquals(1,r.code(),r.toString());
        }
        assertEquals(1,intOf("select count(*) from staff_earning"));amount("10.00");
    }

    @Test void confirmedEntryCanReplayAfterItsItemIsRenamedAndDisabledWithoutChangingSnapshot() {
        seed();long item=post("/api/manager/earning-items",mgr,"{\"name\":\"高温补贴\",\"direction\":1}").data().asLong();
        String b=body(staff,item,"10","","item-replay");assertEquals(0,post(ADJUST,mgr,b).code());
        assertEquals(0,put("/api/manager/earning-items/"+item,mgr,"{\"name\":\"旧补贴\",\"direction\":2}").code());
        assertEquals(0,post("/api/manager/earning-items/"+item+"/status",mgr,"{\"status\":0}").code());
        assertEquals(0,post(ADJUST,mgr,b).code());assertEquals(1,intOf("select count(*) from staff_earning"));amount("10.00");
        assertEquals("高温补贴",jdbc.queryForObject("select item_name from staff_earning where idempotency_key='item-replay'",String.class));
    }

    @Test void concurrentIdenticalIntentHasOneEntryAndBothCallersCanRecoverTheResult() throws Exception {
        seed();String b=body(staff,null,"12.50","弱网重试","parallel");
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Api> a=pool.submit(()->{start.await();return post(ADJUST,mgr,b);});
            Future<Api> c=pool.submit(()->{start.await();return post(ADJUST,mgr,b);});start.countDown();
            for(Api r:List.of(a.get(30,TimeUnit.SECONDS),c.get(30,TimeUnit.SECONDS))) assertEquals(0,r.code(),r.toString());
            assertEquals(1,intOf("select count(*) from staff_earning"));amount("12.50");
        } finally {pool.shutdownNow();}
    }

    @Test void paidPayrollAndTransferDoNotTurnReplayIntoNewUnsettledWages() {
        seed();String b=body(staff,null,"15","补录","paid-replay");assertEquals(0,post(ADJUST,mgr,b).code());
        java.time.LocalDate date=jdbc.queryForObject("select current_date()",java.time.LocalDate.class);
        Api generated=post("/api/manager/payroll",mgr,"{\"staffId\":"+staff+",\"periodStart\":\""+date.minusDays(1)+"\",\"periodEnd\":\""+date.plusDays(1)+"\"}");
        assertEquals(0,generated.code(),generated.toString());long payroll=generated.data().path("payrollId").asLong();
        assertEquals(0,post("/api/manager/payroll/"+payroll+"/confirm",mgr,"{}").code());
        assertEquals(0,post("/api/manager/payroll/"+payroll+"/pay",mgr,"{}").code());
        long next=createStation("转入站");jdbc.update("update staff set station_id=?,status=0 where id=?",next,staff);
        assertEquals(0,post(ADJUST,mgr,b).code());amount("15.00");
        assertEquals(0,intOf("select count(*) from staff_earning where payroll_id is null"));
        assertEquals(0,new BigDecimal("15.00").compareTo(decimalOf("select total_amount from staff_payroll where id=?",payroll)));
    }
}
