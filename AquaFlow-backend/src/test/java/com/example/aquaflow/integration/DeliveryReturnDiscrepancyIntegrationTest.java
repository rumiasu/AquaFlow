package com.example.aquaflow.integration;

import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.service.ReconciliationService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen new-barrel quote plus a later, legitimate historical asset correction. */
@TestPropertySource(properties = {"aquaflow.barrel.independent-rights-enabled=true",
        "aquaflow.barrel.maintenance-enabled=false", "app.payment.mock-wechat-pay=false"})
class DeliveryReturnDiscrepancyIntegrationTest extends AbstractIntegrationTest {
    @Autowired BarrelLedgerService ledger;
    @Autowired ReconciliationService reconciliation;
    long station, customer, product, order, item;
    String manager;

    void prepare() {
        station = createStation("SYNTH C23 regression");
        long staff = createStaff("SYNTH manager", "STATION_MANAGER", station, 1);
        customer = createCustomer("SYNTH customer", "synthetic-c23-regression");
        product = createProduct("SYNTH barrel", 1, "20.00", "50.00", 1, "8.00");
        createInventoryFull(station, product, 20, 1, "8.00");
        jdbc.update("update inventory set deposit_price=50 where station_id=? and product_id=?", station, product);
        createInventoryRecord(station, product, 20, "STOCK_IN", 0);
        createCustomerStationConfig(customer, station, 1);
        long address = createAddress(customer, "SYNTH address");
        manager = staffToken(staff, "STATION_MANAGER", station);
        String customerAuth = customerToken(customer);
        String items = "[{\"productId\":" + product + ",\"quantity\":2}]";
        String common = "\"stationId\":" + station + ",\"addressId\":" + address
                + ",\"paymentMethod\":2,\"items\":" + items;
        Api quote = post("/api/payments/quote", customerAuth, "{" + common + "}");
        assertEquals(0, quote.code(), quote.toString());
        assertEquals(100, quote.data().path("extraDeposit").asInt());
        Api created = post("/api/orders/create", customerAuth, "{" + common
                + ",\"idempotencyKey\":\"synthetic-c23-new-barrels\",\"barrelPurchases\":"
                + quote.data().path("barrelPurchases") + "}");
        assertEquals(0, created.code(), created.toString());
        order = created.data().path("orderId").asLong();
        item = longOf("select id from order_item where order_id=?", order);
        // Correct old physical assets after the quote; do not rewrite the order's deposit snapshot.
        Api adjustment = post("/api/manager/adjustments", manager, "{\"customerId\":" + customer
                + ",\"adjustType\":\"BARREL_GRANT\",\"productId\":" + product
                + ",\"qty\":2,\"unitPrice\":50,\"reason\":\"SYNTH historical correction after quote\","
                + "\"clientToken\":\"synthetic-c23-historical-grant\"}");
        assertEquals(0, adjustment.code(), adjustment.toString());
        Api executed = post("/api/manager/adjustments/" + adjustment.data().path("id").asLong()
                + "/execute", manager, "{}");
        assertEquals(0, executed.code(), executed.toString());
        assertEquals(0, post("/api/delivery/orders/" + order + "/accept", manager, "{}").code());
        Api detail = get("/api/delivery/orders/" + order, manager);
        assertEquals(0, detail.code(), detail.toString());
        assertEquals(0, detail.data().path("items").get(0).path("suggestedReturnQty").asInt(-1));
        assertEquals(2, ledger.occupiedQty(customer, station, product));
    }

    Api complete(int actual) {
        return post("/api/delivery/orders/" + order + "/complete", manager,
                "{\"collected\":true,\"itemReturns\":[{\"orderItemId\":" + item
                        + ",\"expected\":0,\"actual\":" + actual + ",\"reasons\":[]}]}");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void newPickupWithOldPhysicalReturnsRecordsExcessAndCompletesExactlyOnce(int actual) {
        prepare();
        Api done = complete(actual);
        // Before the fix actual=2 returned code500; actual=1 wrongly recorded RETURN_SHORT.
        assertEquals(0, done.code(), done.toString());
        assertEquals(4, intOf("select status from orders where id=?", order));
        assertEquals(2, intOf("select payment_status from orders where id=?", order));
        assertEquals(-actual, intOf("select barrel_discrepancy from orders where id=?", order));
        assertEquals(actual, intOf("select return_bucket_qty from orders where id=?", order));
        assertEquals(1, intOf("select count(*) from order_barrel_exception where order_id=?", order));
        assertEquals("RETURN_OVER", jdbc.queryForObject("select category from order_barrel_exception where order_id=?", String.class, order));
        assertEquals(0, intOf("select delivery_qty from order_barrel_exception where order_id=?", order));
        assertEquals(actual, intOf("select return_qty from order_barrel_exception where order_id=?", order));
        assertEquals(-actual, intOf("select discrepancy from order_barrel_exception where order_id=?", order));
        assertEquals(1, intOf("select count(*) from payment_record where order_id=? and status=2", order));
        assertEquals(140, intOf("select sum(amount) from payment_record where order_id=? and status=2", order));
        assertEquals(100, intOf("select sum(barrel_deposit) from payment_record where order_id=? and status=2", order));
        assertEquals(4, ledger.rightQty(customer, station, product));
        assertEquals(4 - actual, ledger.occupiedQty(customer, station, product));
        // BARREL_GRANT corrects historical rights; only this order's new cash deposit moves money.
        assertEquals(100, intOf("select balance from customer_deposit_account where customer_id=? and station_id=?", customer, station));
        assertEquals(18, intOf("select quantity from inventory where station_id=? and product_id=?", station, product));
        assertEquals(1, intOf("select count(*) from inventory_record where ref_id=? and type='CONSUME'", order));
        assertEquals(0, intOf("select count(*) from ticket_record"));
        var after = facts();
        assertEquals(1, complete(actual).code(), "A second completion must be rejected");
        assertEquals(after, facts(), "Repeated completion must not duplicate any business effect");
        assertEquals(0, reconciliation.runReconcile().values().stream().mapToInt(Integer::intValue).sum());
        reconciliation.runReconcileV2().entrySet().stream().filter(e -> e.getKey().matches("E(17|18|19|20|21|22)_.*"))
                .forEach(e -> assertEquals(0, e.getValue(), e.toString()));
    }

    @Test void returningMoreThanPhysicalHoldingsRollsBackCashAndRights() {
        prepare();
        var before = facts();
        Api denied = complete(3);
        assertEquals(1, denied.code(), denied.toString());
        assertTrue(denied.body().path("message").asText().contains("客户手上的桶数"), denied.toString());
        assertEquals(before, facts(), "Cash/deposit activation before the physical check must roll back");
    }

    @Test void shipmentFailureRollsBackCashRightsAndBarrelsBeforeRetry() {
        prepare();
        jdbc.update("update inventory set quantity=0 where station_id=? and product_id=?", station, product);
        var before = facts();
        assertEquals(1, complete(2).code());
        assertEquals(before, facts(), "Shipment refusal must roll back every earlier business effect");
        jdbc.update("update inventory set quantity=20 where station_id=? and product_id=?", station, product);
        assertEquals(0, complete(2).code(), "The same legal request must succeed after stock is restored");
    }

    Map<String, Object> facts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        // Exclude clocks/auto-increment counters; compare all persisted money, tickets, barrels and stock facts.
        for (String table : new String[]{"payment_record", "deposit_record", "customer_deposit_account",
                "customer_barrel_asset", "customer_barrel_lot", "customer_barrel_over", "barrel_right_reservation",
                "order_barrel_purchase", "barrel_record", "inventory", "inventory_record", "inventory_reservation",
                "ticket_record", "ticket_account", "order_barrel_exception", "staff_earning"}) {
            facts.put(table, jdbc.queryForList("select * from " + table + " order by id"));
        }
        facts.put("order", jdbc.queryForList("select * from orders where id=?", order));
        return facts;
    }
}
