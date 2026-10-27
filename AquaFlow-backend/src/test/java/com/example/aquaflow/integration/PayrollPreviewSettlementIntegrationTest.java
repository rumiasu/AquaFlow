package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 专属合成测试库上的真实HTTP；预览不能建单，登记必须守金额、身份、并发及已发历史。 */
class PayrollPreviewSettlementIntegrationTest extends AbstractIntegrationTest {
    long station, manager, staff;
    String token;
    @BeforeEach void seedPayroll() {
        station = createStation("合成工资站"); manager = createStaff("合成站长", "STATION_MANAGER", station, 1);
        staff = createStaff("合成员工", "DELIVERY", station, 1); token = staffToken(manager, "STATION_MANAGER", station);
    }
    void rate(String value) { assertEquals(0, put("/api/manager/piece-rate", token, "{\"perBucketAmount\":" + value + "}").code()); }
    long earning(String amount, String time) {
        return insert("insert into staff_earning(station_id,staff_id,kind,product_id,amount,create_time) values(?,?,'ADJUST',0,?,?)",
                station, staff, new BigDecimal(amount), time);
    }
    Api preview() { return get("/api/manager/payroll/preview?staffId=" + staff + "&periodStart=2026-09-01&periodEnd=2026-09-30", token); }
    String settleBody(Api preview) {
        return "{\"staffId\":" + staff + ",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\",\"paidOffline\":true,\"previewToken\":\""
                + preview.data().path("previewToken").asText() + "\"}";
    }
    String historicalBody(Api preview) { return "{\"paidOffline\":true,\"previewToken\":\"" + preview.data().path("previewToken").asText() + "\"}"; }
    @Test void readOnlyPreviewIncludesWholeEndDayAndSignedAmounts() {
        rate("1.25"); earning("12.25", "2026-09-01 00:00:00"); earning("-2.10", "2026-09-30 23:59:59");
        earning("900", "2026-08-31 23:59:59"); earning("800", "2026-10-01 00:00:00");
        Api p = preview(); assertEquals(0, p.code(), p.toString()); assertTrue(p.data().path("complete").asBoolean());
        assertEquals(0, new BigDecimal("10.15").compareTo(p.data().path("payableAmount").decimalValue()));
        assertEquals(2, p.data().path("earnings").size()); assertEquals(0, intOf("select count(*) from staff_payroll"));
        assertEquals(4, intOf("select count(*) from staff_earning where payroll_id is null"));
    }
    @Test void missingRuleIsUnknownAndCannotBecomeZeroOrCreatePayroll() {
        earning("20", "2026-09-15 12:00:00"); Api p = preview(); assertEquals(0, p.code(), p.toString());
        assertFalse(p.data().path("complete").asBoolean()); assertTrue(p.data().path("payableAmount").isNull());
        assertEquals(20, p.data().path("unpaidAmount").asInt()); assertFalse(p.data().path("canSettle").asBoolean());
        assertEquals(1, post("/api/manager/payroll/settle", token, settleBody(p)).code());
        assertEquals(0, intOf("select count(*) from staff_payroll"));
    }
    @Test void explicitlyConfiguredZeroIsKnownZeroButNoPaymentRegistration() {
        rate("0"); Api p = preview(); assertEquals(0, p.code(), p.toString());
        assertTrue(p.data().path("complete").asBoolean()); assertEquals(0, p.data().path("payableAmount").asInt());
        assertFalse(p.data().path("canSettle").asBoolean());
    }
    @Test void productSpecificRatesAreRealConfigurationWithoutDefaultRate() {
        long product = createProduct("合成专属计价商品", 1, "10", "0", 0, "0"); createInventory(station, product, 10);
        assertEquals(0, put("/api/manager/piece-rate", token, "{\"productId\":" + product + ",\"perBucketAmount\":2}").code());
        earning("13", "2026-09-12 12:00:00"); Api p = preview();
        assertEquals(0, p.code(), p.toString()); assertTrue(p.data().path("complete").asBoolean());
        assertEquals(13, p.data().path("payableAmount").asInt());
        Api guide = get("/api/manager/setup-guide", token);
        assertEquals(0, guide.code(), guide.toString());
        for (var item : guide.data().path("items")) if (item.path("key").asText().equals("staffPayroll")) assertTrue(item.path("done").asBoolean());
    }
    @Test void unknownHistoricalStateRetainsRecordedAmountWithoutInventingPaidOrUnpaidClassification() {
        rate("1"); earning("8", "2026-09-12 12:00:00");
        long id = post("/api/manager/payroll", token, "{\"staffId\":" + staff + ",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}").data().path("payrollId").asLong();
        jdbc.update("update staff_payroll set status=99 where id=?",id); Api p=preview();
        assertEquals(0,p.code(),p.toString()); assertEquals(8,p.data().path("recordedAmount").asInt());
        assertFalse(p.data().path("statusKnown").asBoolean()); assertFalse(p.data().path("complete").asBoolean());
        assertTrue(p.data().path("payableAmount").isNull());
    }
    @Test void deliveredWithoutHistoricalEarningIsFlaggedAndNeverRepriced() {
        rate("2"); long customer = createCustomer("合成客户", "payroll-preview-customer");
        long address = createAddress(customer, "合成地址"); long product = createProduct("合成水", 1, "10", "0", 0, "0");
        long order = createOrder(customer, address, station, product, 4, 2);
        jdbc.update("update orders set delivery_staff_id=?,create_time='2026-09-10 12:00:00' where id=?", staff, order);
        jdbc.update("insert into order_item(order_id,product_id,product_name_snapshot,price,quantity,subtotal) values(?,?,'合成水',10,2,20)", order, product);
        Api p = preview(); assertEquals(0, p.code(), p.toString()); assertEquals(1, p.data().path("unrecordedCount").asInt());
        assertFalse(p.data().path("complete").asBoolean()); assertTrue(p.data().path("warning").asText().contains("尚无计件流水"));
        assertEquals(0, intOf("select count(*) from staff_earning"));
    }
    @Test void settlementRequiresOfflineAcknowledgementAndPersistsAuditOnce() {
        rate("2"); earning("35.12", "2026-09-12 12:00:00"); Api p = preview();
        assertEquals(1, post("/api/manager/payroll/settle", token, settleBody(p).replace("true", "false")).code());
        Api settled = post("/api/manager/payroll/settle", token, settleBody(p)); assertEquals(0, settled.code(), settled.toString());
        long id = settled.data().path("id").asLong(); assertEquals(3, intOf("select status from staff_payroll where id=?", id));
        assertEquals(manager, jdbc.queryForObject("select operator_id from staff_payroll where id=?", Long.class, id));
        assertNotNull(jdbc.queryForObject("select paid_time from staff_payroll where id=?", java.sql.Timestamp.class, id));
        assertEquals(1, post("/api/manager/payroll/settle", token, settleBody(p)).code());
        assertEquals(1, intOf("select count(*) from staff_payroll")); Api after = preview();
        assertEquals(0, after.data().path("unpaidAmount").asInt()); assertEquals(0, new BigDecimal("35.12").compareTo(after.data().path("paidAmount").decimalValue()));
    }
    @Test void newAdjustmentAfterPreviewRejectsWholeTransaction() {
        rate("1"); earning("10", "2026-09-12 12:00:00"); Api p = preview(); earning("3", "2026-09-13 12:00:00");
        assertEquals(1, post("/api/manager/payroll/settle", token, settleBody(p)).code());
        assertEquals(0, intOf("select count(*) from staff_payroll")); assertEquals(2, intOf("select count(*) from staff_earning where payroll_id is null"));
    }
    @Test void simultaneousSettlementRegistersOnlyOnePayment() throws Exception {
        rate("1"); earning("10", "2026-09-12 12:00:00"); String body = settleBody(preview());
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Api> call = () -> { go.await(); return post("/api/manager/payroll/settle", token, body); };
            Future<Api> a = pool.submit(call), b = pool.submit(call); go.countDown();
            Api one = a.get(20, TimeUnit.SECONDS), two = b.get(20, TimeUnit.SECONDS);
            assertEquals(1, (one.code() == 0 ? 1 : 0) + (two.code() == 0 ? 1 : 0), one + " / " + two);
            assertTrue(one.code() == 1 || two.code() == 1); assertEquals(1, intOf("select count(*) from staff_payroll where status=3"));
        } finally { pool.shutdownNow(); }
    }
    @Test void crossStationAndDeliveryCannotReadOrSettleOthersWages() {
        rate("1"); earning("10", "2026-09-12 12:00:00"); Api p = preview();
        long otherStation = createStation("合成别站"), other = createStaff("合成别站长", "STATION_MANAGER", otherStation, 1);
        String otherToken = staffToken(other, "STATION_MANAGER", otherStation);
        String path = "/api/manager/payroll/preview?staffId=" + staff + "&periodStart=2026-09-01&periodEnd=2026-09-30";
        assertEquals(1, get(path, otherToken).code()); assertEquals(1, post("/api/manager/payroll/settle", otherToken, settleBody(p)).code());
        assertEquals(1, get(path, staffToken(staff, "DELIVERY", station)).code()); assertEquals(0, intOf("select count(*) from staff_payroll"));
    }
    @Test void historicalDraftSettlementRetainsPaidHistoryAndRejectsChangedOrRepeatedState() {
        rate("1"); earning("8", "2026-09-12 12:00:00");
        Api generated = post("/api/manager/payroll", token, "{\"staffId\":" + staff + ",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}");
        assertEquals(0, generated.code(), generated.toString()); long id = generated.data().path("payrollId").asLong();
        Api p = get("/api/manager/payroll/" + id + "/settlement-preview", token); assertTrue(p.data().path("canSettle").asBoolean());
        assertEquals(0, post("/api/manager/payroll/" + id + "/settle", token, historicalBody(p)).code());
        var before = jdbc.queryForMap("select * from staff_payroll where id=?", id);
        assertEquals(1, post("/api/manager/payroll/" + id + "/settle", token, historicalBody(p)).code());
        rate("9"); earning("4", "2026-10-02 12:00:00"); preview();
        assertEquals(before, jdbc.queryForMap("select * from staff_payroll where id=?", id));
    }
    @Test void confirmedHistoricalPayrollCanSettleAndCrossStationCannotInspect() {
        rate("1"); earning("8", "2026-09-12 12:00:00");
        Api generated = post("/api/manager/payroll", token, "{\"staffId\":" + staff + ",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}");
        long id = generated.data().path("payrollId").asLong(); assertEquals(0, post("/api/manager/payroll/" + id + "/confirm", token, "{}").code());
        long elsewhere = createStation("合成别站"), mgr = createStaff("别站长", "STATION_MANAGER", elsewhere, 1);
        assertEquals(1, get("/api/manager/payroll/" + id + "/settlement-preview", staffToken(mgr,"STATION_MANAGER",elsewhere)).code());
        Api p = get("/api/manager/payroll/" + id + "/settlement-preview", token);
        assertEquals(0, post("/api/manager/payroll/" + id + "/settle", token, historicalBody(p)).code());
        assertEquals(3, intOf("select status from staff_payroll where id=?", id));
    }
    @Test void invalidPeriodAndStaleHistoricalAmountsAreRejected() {
        assertEquals(1, get("/api/manager/payroll/preview?staffId=" + staff + "&periodStart=2026-09-30&periodEnd=2026-09-01",token).code());
        rate("1"); earning("8", "2026-09-12 12:00:00");
        long id = post("/api/manager/payroll", token, "{\"staffId\":" + staff + ",\"periodStart\":\"2026-09-01\",\"periodEnd\":\"2026-09-30\"}").data().path("payrollId").asLong();
        Api p = get("/api/manager/payroll/" + id + "/settlement-preview", token);
        jdbc.update("update staff_payroll set total_amount=9 where id=?",id);
        assertEquals(1, post("/api/manager/payroll/" + id + "/settle",token,historicalBody(p)).code());
        assertEquals(1, intOf("select status from staff_payroll where id=?",id));
    }
    @Test void configurationRequiresExplicitAmountsAndConditionalFreeFloor() {
        for (String body : new String[]{"{}", "{\"perBucketAmount\":-1}", "{\"perBucketAmount\":1.001}",
                "{\"perBucketAmount\":1,\"floorBonusPerLevel\":2}"}) assertEquals(1, put("/api/manager/piece-rate", token, body).code(), body);
        assertEquals(0, intOf("select count(*) from staff_piece_rate"));
        assertEquals(0, put("/api/manager/piece-rate",token,"{\"perBucketAmount\":0,\"floorBonusPerLevel\":2,\"floorFreeLevel\":0}").code());
    }
}
