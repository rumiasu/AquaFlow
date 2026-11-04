package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Adjustment reversals must consume or restore the persisted original lots, never substitute FIFO. */
class StationAdjustmentExactBarrelReversalIntegrationTest extends AbstractIntegrationTest {
    private long station, customer, product;
    private String manager;

    @BeforeEach void seed() {
        station = createStation("Exact adjustment station");
        customer = createCustomer("Exact adjustment customer", "exact-adjustment-customer");
        product = createProduct("Exact barrel", 1, "20", "30", 1, "18");
        createCustomerStationConfig(customer, station, 1);
        manager = staffToken(createStaff("Exact manager", "STATION_MANAGER", station, 1), "STATION_MANAGER", station);
    }

    private long execute(String type, int qty, String price, String key) {
        Api created = post("/api/manager/adjustments", manager, "{\"customerId\":" + customer
                + ",\"productId\":" + product + ",\"adjustType\":\"" + type + "\",\"qty\":" + qty
                + (price == null ? "" : ",\"unitPrice\":" + price)
                + ",\"reason\":\"synthetic correction\",\"clientToken\":\"" + key + "\"}");
        assertTrue(created.isSuccess(), created.toString());
        long id = created.data().path("id").asLong();
        Api done = post("/api/manager/adjustments/" + id + "/execute", manager, "{}");
        assertTrue(done.isSuccess(), done.toString());
        return id;
    }

    private Api reverse(long id, String key) {
        return post("/api/manager/adjustments/" + id + "/reverse", manager,
                "{\"reason\":\"synthetic reversal\",\"clientToken\":\"" + key + "\"}");
    }

    private long record(long adjustment) {
        return jdbc.queryForObject("select id from barrel_record where adjustment_id=?", Long.class, adjustment);
    }

    private long lot(String price) {
        return jdbc.queryForObject("select id from customer_barrel_lot where unit_price=?", Long.class, new BigDecimal(price));
    }

    private void value(String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(decimalOf("select right_amount from customer_barrel_asset")));
        assertEquals(0, new BigDecimal(expected).compareTo(decimalOf("select coalesce(sum(remain_qty*unit_price),0) from customer_barrel_lot where status=1")));
    }

    private void unavailable(long id, String reason) {
        Api detail = get("/api/manager/adjustments/" + id, manager);
        assertTrue(detail.isSuccess(), detail.toString());
        assertFalse(detail.data().path("canReverse").asBoolean());
        assertTrue(detail.data().path("reverseUnavailableReason").asText().contains(reason), detail.toString());
        assertEquals(1, reverse(id, "blocked-" + id).code());
        assertEquals(0, intOf("select count(*) from station_adjustment where reverses=?", id));
        assertEquals("EFFECTIVE", jdbc.queryForObject("select status from station_adjustment where id=?", String.class, id));
    }

    @Test void laterGrantReversesOnlyItsOwnLotAndLeavesEarlierValue() {
        execute("BARREL_GRANT", 1, "30", "earlier");
        long later = execute("BARREL_GRANT", 1, "50", "later");
        assertEquals(1, intOf("select count(*) from barrel_record_lot where record_id=? and lot_id=?", record(later), lot("50")));
        assertTrue(get("/api/manager/adjustments/" + later, manager).data().path("canReverse").asBoolean());
        Api done = reverse(later, "undo-later");
        assertTrue(done.isSuccess(), done.toString());
        value("30");
        assertEquals(1, intOf("select remain_qty from customer_barrel_lot where id=?", lot("30")));
        assertEquals(0, intOf("select remain_qty from customer_barrel_lot where id=?", lot("50")));
        assertEquals(1, intOf("select count(*) from barrel_record_lot where record_id=? and lot_id=?",
                record(done.data().path("id").asLong()), lot("50")));
        Api retry = reverse(later, "undo-later");
        assertTrue(retry.isSuccess(), retry.toString());
        assertEquals(done.data().path("id"), retry.data().path("id"));
        value("30");
    }

    @Test void multiPriceRevokeRestoresOriginalLotsAndExhaustedStatusWithoutRepricing() {
        execute("BARREL_GRANT", 1, "30", "low");
        execute("BARREL_GRANT", 2, "50", "high");
        long revoke = execute("BARREL_REVOKE", 2, null, "revoke");
        assertEquals(2, intOf("select count(*) from barrel_record_lot where record_id=?", record(revoke)));
        value("50");
        jdbc.update("update product set deposit=99 where id=?", product);
        Api done = reverse(revoke, "undo-revoke");
        assertTrue(done.isSuccess(), done.toString());
        value("130");
        assertEquals(2, intOf("select count(*) from customer_barrel_lot"));
        assertEquals(1, intOf("select status from customer_barrel_lot where id=?", lot("30")));
        assertEquals(1, intOf("select remain_qty from customer_barrel_lot where id=?", lot("30")));
        assertEquals(2, intOf("select remain_qty from customer_barrel_lot where id=?", lot("50")));
        Api undoAgain = reverse(done.data().path("id").asLong(), "undo-the-restoration");
        assertTrue(undoAgain.isSuccess(), undoAgain.toString());
        value("50");
        assertEquals(2, intOf("select count(*) from customer_barrel_lot"));
    }

    @Test void consumedOriginalCannotBeReplacedEvenWhenOtherLotsAreAvailable() {
        long original = execute("BARREL_GRANT", 1, "30", "consumed");
        execute("BARREL_REVOKE", 1, null, "consume");
        execute("BARREL_GRANT", 1, "50", "replacement");
        unavailable(original, "批次");
        value("50");
    }

    @Test void sourceLotsMissingReceiptsRemainUnavailableWithoutBackfillingHistory() {
        long original = execute("BARREL_GRANT", 1, "30", "legacy");
        jdbc.update("delete from barrel_record_lot where record_id=?", record(original));
        unavailable(original, "批次");
        value("30");
        assertEquals(0, intOf("select count(*) from barrel_record_lot"));
    }

    @Test void mismatchedReceiptAmountNeverChangesTheOriginalOrAssets() {
        long original = execute("BARREL_GRANT", 1, "30", "bad-price");
        jdbc.update("update barrel_record_lot set amount=31 where record_id=?", record(original));
        unavailable(original, "批次");
        value("30");
    }

    @Test void mismatchedReceiptLotOwnerNeverChangesEitherCustomersAssets() {
        long original = execute("BARREL_GRANT", 1, "30", "bad-owner");
        long other = createCustomer("Foreign synthetic customer", "foreign-exact-adjustment");
        jdbc.update("update customer_barrel_lot set customer_id=? where id=?", other, lot("30"));
        unavailable(original, "批次");
        assertEquals(1, intOf("select remain_qty from customer_barrel_lot"));
    }

    @Test void restorationCannotExceedOriginalQuantityOrReviveVoidLots() {
        execute("BARREL_GRANT", 1, "30", "restore-limit-source");
        long revoke = execute("BARREL_REVOKE", 1, null, "restore-limit-revoke");
        jdbc.update("update customer_barrel_lot set remain_qty=1,status=1 where id=?", lot("30"));
        unavailable(revoke, "批次");
        jdbc.update("update customer_barrel_lot set remain_qty=0,status=3 where id=?", lot("30"));
        unavailable(revoke, "批次");
        assertEquals(3, intOf("select status from customer_barrel_lot"));
    }

    @Test void sourceLotReservedForReturnCannotBeReplacedByAnotherFreeLot() {
        long original = execute("BARREL_GRANT", 1, "30", "held-source");
        execute("BARREL_GRANT", 1, "50", "free-source");
        long record = insert("insert into barrel_record(customer_id,station_id,product_id,type,quantity,status) values(?,?,?,2,1,1)", customer, station, product);
        jdbc.update("insert into barrel_return_detail(record_id,customer_id,station_id,idempotency_key,pickup_mode,required_barrels,status) values(?,?,?,'synthetic-hold','STORE',1,'APPLIED')", record, customer, station);
        jdbc.update("insert into barrel_return_lot_hold(record_id,lot_id,quantity,unit_price,amount) values(?,?,1,30,30)", record, lot("30"));
        unavailable(original, "占用");
        value("80");
    }

    @Test void reversalDoesNotMakePhysicalBarrelsNegativeAndReadChecksDoNotCreateRows() {
        long original = execute("BARREL_GRANT", 1, "30", "physical-source");
        jdbc.update("delete from customer_barrel_over");
        Api detail = get("/api/manager/adjustments/" + original, manager);
        assertTrue(detail.data().path("canReverse").asBoolean(), detail.toString());
        assertEquals(0, intOf("select count(*) from customer_barrel_over"));
        jdbc.update("insert into customer_barrel_over(customer_id,station_id,product_id,over_qty) values(?,?,?,-1)", customer, station, product);
        unavailable(original, "实物");
        value("30");
    }

    @Test void concurrentDifferentKeysCanReverseAnExactBarrelLotOnlyOnce() throws Exception {
        long original = execute("BARREL_GRANT", 1, "30", "racing-lot-source");
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> {
                ready.countDown(); assertTrue(go.await(20, TimeUnit.SECONDS));
                return reverse(original, "racing-lot-one");
            });
            var second = workers.submit(() -> {
                ready.countDown(); assertTrue(go.await(20, TimeUnit.SECONDS));
                return reverse(original, "racing-lot-two");
            });
            assertTrue(ready.await(20, TimeUnit.SECONDS)); go.countDown();
            List<Api> results = List.of(first.get(40, TimeUnit.SECONDS), second.get(40, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Api::isSuccess).count(), results.toString());
            assertEquals(1, results.stream().filter(r -> r.code() == 1).count(), results.toString());
        } finally {
            go.countDown(); workers.shutdownNow();
        }
        value("0");
        assertEquals(2, intOf("select count(*) from station_adjustment"));
        assertEquals(1, intOf("select count(*) from station_adjustment where reverses=? and status='EFFECTIVE'", original));
        assertEquals(2, intOf("select count(*) from barrel_record_lot"));
        assertEquals(0, intOf("select remain_qty from customer_barrel_lot"));
    }

    @Test void receiptInsertFailureRollsBackExactLotSummaryAndBothAdjustmentStates() {
        long original = execute("BARREL_GRANT", 1, "30", "proof-conflict-source");
        long originalLot = lot("30");
        long nextRecord = jdbc.queryForObject("select coalesce(max(id),0)+1 from barrel_record", Long.class);
        // 无外键的合成冲突行只用于本隔离库：实际扣减和桶流水插入后，凭据唯一键才失败。
        jdbc.update("insert into barrel_record_lot(record_id,lot_id,qty,unit_price,amount) values(?,?,1,30,30)", nextRecord, originalLot);
        Api failure = reverse(original, "proof-conflict-reverse");
        assertFalse(failure.isSuccess(), failure.toString());
        value("30");
        assertEquals(1, intOf("select remain_qty from customer_barrel_lot where id=?", originalLot));
        assertEquals(1, intOf("select status from customer_barrel_lot where id=?", originalLot));
        assertEquals("EFFECTIVE", jdbc.queryForObject("select status from station_adjustment where id=?", String.class, original));
        assertNull(jdbc.queryForObject("select reversed_by from station_adjustment where id=?", Long.class, original));
        assertEquals(1, intOf("select count(*) from station_adjustment"));
        assertEquals(1, intOf("select count(*) from barrel_record"));
        assertEquals(2, intOf("select count(*) from barrel_record_lot"), "only the original proof and preexisting conflict remain");
    }
}
