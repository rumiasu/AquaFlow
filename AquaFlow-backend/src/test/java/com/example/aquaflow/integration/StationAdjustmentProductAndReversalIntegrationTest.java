package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Asset corrections must stay in product scope and undo the actual source lots. */
class StationAdjustmentProductAndReversalIntegrationTest extends AbstractIntegrationTest {
    private long station;
    private long product;
    private long customer;
    private String manager;

    @BeforeEach void seed() {
        station = createStation("Adjustment provenance station");
        product = createProduct("Returnable barrel", 1, "20.00", "30.00", 1, "18.00");
        customer = createCustomer("Synthetic adjustment customer", "adjustment-provenance-customer");
        createCustomerStationConfig(customer, station, 1);
        manager = staffToken(createStaff("Adjustment manager", "STATION_MANAGER", station, 1),
                "STATION_MANAGER", station);
    }

    private String payload(String type, long productId, int qty, String price, String token) {
        return "{\"customerId\":" + customer + ",\"adjustType\":\"" + type + "\",\"productId\":"
                + productId + ",\"qty\":" + qty + ",\"amount\":10"
                + (price == null ? "" : ",\"unitPrice\":" + price)
                + ",\"reason\":\"synthetic correction\",\"clientToken\":\"" + token + "\"}";
    }

    private long create(String type, int qty, String price, String token) {
        Api result = post("/api/manager/adjustments", manager, payload(type, product, qty, price, token));
        assertTrue(result.isSuccess(), result.toString());
        return result.data().path("id").asLong();
    }

    private long execute(String type, int qty, String price, String token) {
        long id = create(type, qty, price, token);
        Api result = post("/api/manager/adjustments/" + id + "/execute", manager, "{}");
        assertTrue(result.isSuccess(), result.toString());
        return id;
    }

    private Api reverse(long id, String token) {
        return post("/api/manager/adjustments/" + id + "/reverse", manager,
                "{\"reason\":\"undo synthetic correction\",\"clientToken\":\"" + token + "\"}");
    }

    private void assertValue(String amount) {
        assertEquals(0, new BigDecimal(amount).compareTo(decimalOf(
                "select coalesce(sum(remain_qty*unit_price),0) from customer_barrel_lot where status=1")));
        assertEquals(0, new BigDecimal(amount).compareTo(decimalOf(
                "select right_amount from customer_barrel_asset where customer_id=? and station_id=? and product_id=?",
                customer, station, product)));
    }

    @Test void foreignPrivateProductsAreRejectedBeforePreviewOrCreationForEveryAssetType() {
        long foreign = createStation("Other station");
        jdbc.update("update product set owner_station_id=? where id=?", foreign, product);
        for (String type : List.of("BARREL_GRANT", "BARREL_REVOKE", "OVER_ADJUST", "TICKET_GRANT",
                "TICKET_DEDUCT", "DEPOSIT_GRANT", "DEPOSIT_DEDUCT")) {
            String body = payload(type, product, 1, "30.00", "foreign-" + type);
            assertEquals(1, post("/api/manager/adjustments/preview", manager, body).code(), type + " preview");
            assertEquals(1, post("/api/manager/adjustments", manager, body).code(), type + " create");
        }
        assertEquals(0, intOf("select count(*) from station_adjustment"));
        assertEquals(0, intOf("select count(*) from customer_barrel_lot"));
    }

    @Test void nonBarrelProductsCannotEnterBarrelOrProductDepositAdjustments() {
        jdbc.update("update product set category=2 where id=?", product);
        for (String type : List.of("BARREL_GRANT", "BARREL_REVOKE", "OVER_ADJUST", "DEPOSIT_GRANT", "DEPOSIT_DEDUCT")) {
            String body = payload(type, product, 1, "30.00", "nonbarrel-" + type);
            assertEquals(1, post("/api/manager/adjustments/preview", manager, body).code(), type + " preview");
            assertEquals(1, post("/api/manager/adjustments", manager, body).code(), type + " create");
        }
        assertEquals(0, intOf("select count(*) from station_adjustment"));
    }

    @Test void missingProductsAreRejectedBeforeDraftCreation() {
        assertEquals(1, post("/api/manager/adjustments", manager,
                payload("BARREL_GRANT", 9999999L, 1, "30.00", "missing-product")).code());
        assertEquals(0, intOf("select count(*) from station_adjustment"));
    }

    @Test void executionRevalidatesProductScopeAndCategoryWithoutMutatingAssets() {
        long id = create("BARREL_GRANT", 1, "30.00", "changed-product");
        long foreign = createStation("Changed owner station");
        jdbc.update("update product set owner_station_id=? where id=?", foreign, product);
        assertEquals(1, post("/api/manager/adjustments/" + id + "/execute", manager, "{}").code());
        jdbc.update("update product set owner_station_id=null, category=2 where id=?", product);
        assertEquals(1, post("/api/manager/adjustments/" + id + "/execute", manager, "{}").code());
        assertEquals("PENDING", jdbc.queryForObject("select status from station_adjustment where id=?", String.class, id));
        assertEquals(0, intOf("select count(*) from customer_barrel_lot"));
        assertEquals(0, intOf("select count(*) from barrel_record"));
    }

    @Test void ownPrivateBarrelAndStationWideDepositAdjustmentsRemainAvailable() {
        jdbc.update("update product set owner_station_id=?, status=0 where id=?", station, product);
        execute("BARREL_GRANT", 1, "30.00", "own-unsold-history");
        assertValue("30.00");
        Api deposit = post("/api/manager/adjustments", manager,
                "{\"customerId\":" + customer + ",\"adjustType\":\"DEPOSIT_GRANT\",\"amount\":30,"
                        + "\"reason\":\"synthetic historical deposit\",\"clientToken\":\"station-deposit\"}");
        assertTrue(deposit.isSuccess(), deposit.toString());
        long id = deposit.data().path("id").asLong();
        assertEquals(0, post("/api/manager/adjustments/" + id + "/execute", manager, "{}").code());
        assertEquals(0, reverse(id, "reverse-station-deposit").code());
        assertEquals(0, decimalOf("select balance from customer_deposit_account").compareTo(BigDecimal.ZERO));
    }

    @Test void laterBarrelGrantWithoutExactSourceProofCannotConsumeAnEarlierPriceLot() {
        execute("BARREL_GRANT", 1, "30.00", "old-low-price");
        long oldLot = jdbc.queryForObject("select min(id) from customer_barrel_lot", Long.class);
        long laterGrant = execute("BARREL_GRANT", 1, "50.00", "new-high-price");
        Api reversed = reverse(laterGrant, "reverse-high-price");
        assertEquals(1, reversed.code(), reversed.toString());
        assertEquals(1, intOf("select remain_qty from customer_barrel_lot where id=?", oldLot));
        assertValue("80.00");
        assertEquals(0, intOf("select count(*) from station_adjustment where reverses=?", laterGrant));
    }

    @Test void mixedPriceBarrelRevokeWithoutSourceProofCannotInventRightsAtCurrentPrice() {
        execute("BARREL_GRANT", 1, "30.00", "mixed-low-price");
        execute("BARREL_GRANT", 2, "50.00", "mixed-high-price");
        long revoke = execute("BARREL_REVOKE", 2, null, "mixed-revoke");
        assertValue("50.00");
        jdbc.update("update product set deposit=99 where id=?", product);
        Api result = reverse(revoke, "mixed-revoke-reverse");
        assertEquals(1, result.code(), result.toString());
        assertValue("50.00");
        assertEquals(2, intOf("select count(*) from customer_barrel_lot"), "must not synthesize a new price");
        assertEquals(0, intOf("select remain_qty from customer_barrel_lot where unit_price=30"));
        assertEquals(1, intOf("select remain_qty from customer_barrel_lot where unit_price=50"));
        assertEquals(0, intOf("select count(*) from station_adjustment where reverses=?", revoke));
    }

    @Test void consumedGrantCannotReverseByTakingAnUnrelatedReplacementLot() {
        long original = execute("BARREL_GRANT", 1, "30.00", "consumed-original");
        execute("BARREL_REVOKE", 1, null, "consume-original");
        execute("BARREL_GRANT", 1, "50.00", "replacement");
        assertEquals(1, reverse(original, "reverse-consumed").code());
        assertValue("50.00");
        assertEquals("EFFECTIVE", jdbc.queryForObject("select status from station_adjustment where id=?", String.class, original));
        assertEquals(0, intOf("select count(*) from station_adjustment where reverses=?", original));
    }

    @Test void laterTicketGrantCannotConsumeEarlierTicketsWithADifferentValue() {
        execute("TICKET_GRANT", 1, null, "old-ticket");
        long oldLot = jdbc.queryForObject("select min(id) from ticket_lot", Long.class);
        jdbc.update("update product set ticket_price=30 where id=?", product);
        long later = execute("TICKET_GRANT", 1, null, "later-ticket");
        assertEquals(1, reverse(later, "reverse-later-ticket").code());
        assertEquals(1, intOf("select remain_qty from ticket_lot where id=?", oldLot));
        assertEquals(0, new BigDecimal("48.00").compareTo(decimalOf("select right_amount from ticket_account")));
        assertEquals(0, intOf("select count(*) from station_adjustment where reverses=?", later));
    }

    @Test void mixedPriceTicketDeductCannotBeReversedAsANewInferredPriceGrant() {
        execute("TICKET_GRANT", 1, null, "deduct-ticket-one");
        jdbc.update("update product set ticket_price=30 where id=?", product);
        execute("TICKET_GRANT", 1, null, "deduct-ticket-two");
        long deducted = execute("TICKET_DEDUCT", 2, null, "mixed-ticket-deduct");
        jdbc.update("update product set ticket_price=99 where id=?", product);
        assertEquals(1, reverse(deducted, "reverse-mixed-ticket-deduct").code());
        assertEquals(0, intOf("select remain_quantity from ticket_account"));
        assertEquals(2, intOf("select count(*) from ticket_lot"));
        assertEquals(0, intOf("select count(*) from station_adjustment where reverses=?", deducted));
    }

    @Test void consumedTicketGrantCannotBeReversedUsingReplacementTickets() {
        long original = execute("TICKET_GRANT", 1, null, "ticket-source");
        execute("TICKET_DEDUCT", 1, null, "consume-ticket-source");
        jdbc.update("update product set ticket_price=30 where id=?", product);
        execute("TICKET_GRANT", 1, null, "ticket-replacement");
        assertEquals(1, reverse(original, "reverse-consumed-ticket").code());
        assertEquals(1, intOf("select remain_quantity from ticket_account"));
        assertEquals(0, new BigDecimal("30.00").compareTo(decimalOf("select right_amount from ticket_account")));
    }

    @Test void untouchedFirstTicketGrantCanReverseUsingItsRecordedExactLotAndReplaySafely() {
        long first = execute("TICKET_GRANT", 1, null, "provable-ticket-source");
        jdbc.update("update product set ticket_price=30 where id=?", product);
        execute("TICKET_GRANT", 1, null, "provable-ticket-later");
        Api reversed = reverse(first, "reverse-provable-ticket");
        assertTrue(reversed.isSuccess(), reversed.toString());
        Api retry = reverse(first, "reverse-provable-ticket");
        assertTrue(retry.isSuccess(), retry.toString());
        assertEquals(reversed.data().path("id").asLong(), retry.data().path("id").asLong());
        assertEquals(1, intOf("select remain_quantity from ticket_account"));
        assertEquals(0, new BigDecimal("30.00").compareTo(decimalOf("select right_amount from ticket_account")));
    }

    @Test void reservedTicketGrantCannotReverseByTakingUnreservedReplacementTickets() {
        long original = execute("TICKET_GRANT", 1, null, "reserved-source");
        long lot = jdbc.queryForObject("select min(id) from ticket_lot", Long.class);
        long address = createAddress(customer, "Synthetic reservation address");
        long order = createOrderFull(customer, address, station, product, 1, 0, 3,
                "18.00", "0.00", "18.00", false, 1);
        jdbc.update("insert into order_ticket_hold(order_id,lot_id,product_id,quantity,unit_price,state) values(?,?,?,?,?,'HELD')",
                order, lot, product, 1, new BigDecimal("18.00"));
        execute("TICKET_GRANT", 1, null, "unreserved-replacement");
        assertEquals(1, reverse(original, "reverse-reserved-source").code());
        assertEquals(2, intOf("select remain_quantity from ticket_account"));
        assertEquals(1, intOf("select remain_qty from ticket_lot where id=?", lot));
        assertEquals(0, intOf("select count(*) from station_adjustment where reverses=?", original));
    }
}
