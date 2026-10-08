package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/SQL completion regression; only the explicitly selected isolated test database. */
class DeliveryReturnOptionalReasonIntegrationTest extends AbstractIntegrationTest {
    private long station, product, customer, address, manager, delivery, order, item;

    private void seedDelivery() {
        station = createStation("选填原因站");
        product = createProduct("测试水", 1, "20.00", "30.00", 0, "0.00");
        createInventory(station, product, 50);
        customer = createCustomer("选填原因客户", "optional-return-customer");
        address = createAddress(customer, "隔离测试地址");
        manager = createStaff("选填原因站长", "STATION_MANAGER", station, 1);
        delivery = createStaff("选填原因配送员", "DELIVERY", station, 1);
        createCustomerStationConfig(customer, station, 1);
        // Buy/deliver legacy rights through the real completion/payment/ledger path,
        // so deposit and physical records exist rather than inventing only an asset row.
        long first = createOrderFull(customer, address, station, product, 2, 1, 2,
                "40.00", "60.00", "100.00", true, 2);
        createReservedItem(first, product, "测试水", 2, "20.00", "30.00");
        createBarrelInTransit(customer, station, product, 2, "30.00", first, "PENDING");
        assertTrue(post("/api/delivery/orders/" + first + "/complete",
                staffToken(manager, "STATION_MANAGER", station), "{\"collected\":true}").isSuccess());
        order = createOrderFull(customer, address, station, product, 2, 1, 2,
                "40.00", "0.00", "40.00", false, 2);
        item = createReservedItem(order, product, "测试水", 2, "20.00", "30.00");
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", delivery, order);
    }

    private Api complete(int expected, String actual, String reasonField) {
        return post("/api/delivery/orders/" + order + "/complete",
                staffToken(delivery, "DELIVERY", station),
                "{\"collected\":false,\"itemReturns\":[{\"orderItemId\":" + item
                        + ",\"productName\":\"测试水\",\"expected\":" + expected
                        + ",\"actual\":" + actual + reasonField + "}]}");
    }

    @Test void missingReasonRecordsActualShortageWithoutChargingAndReplayDoesNotRepeat() {
        seedDelivery();
        Api result = complete(999, "1", "");
        assertTrue(result.isSuccess(), result.toString());
        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", order));
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order));
        assertEquals(1, intOf("SELECT return_bucket_qty FROM orders WHERE id=?", order));
        assertEquals(1, intOf("SELECT barrel_discrepancy FROM orders WHERE id=?", order));
        assertEquals("测试水少1桶;", jdbc.queryForObject("SELECT barrel_discrepancy_note FROM orders WHERE id=?", String.class, order));
        assertEquals(46, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", station, product));
        assertEquals(1, intOf("SELECT over_qty FROM customer_barrel_over WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product));
        assertEquals(0, new BigDecimal("60.00").compareTo(decimalOf("SELECT balance FROM customer_deposit_account WHERE customer_id=? AND station_id=?", customer, station)));
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", order));
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id=?", order));
        int earnings = intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=?", order);
        int records = intOf("SELECT COUNT(*) FROM barrel_record WHERE related_order_id=?", order);
        assertFalse(complete(2, "1", "").isSuccess());
        assertEquals(46, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", station, product));
        assertEquals(earnings, intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=?", order));
        assertEquals(records, intOf("SELECT COUNT(*) FROM barrel_record WHERE related_order_id=?", order));
    }

    @Test void knownPartialReasonIsRetainedAndUnknownRemainderIsNotRelabeled() {
        seedDelivery();
        Api result = complete(2, "0", ",\"reasons\":[{\"key\":\"damaged\",\"qty\":1}]");
        assertTrue(result.isSuccess(), result.toString());
        assertEquals("测试水少2桶(破损×1);", jdbc.queryForObject("SELECT barrel_discrepancy_note FROM orders WHERE id=?", String.class, order));
        assertEquals(2, intOf("SELECT barrel_discrepancy FROM orders WHERE id=?", order));
    }

    @Test void forgedReasonAllowanceOrUnknownKeyCannotChangeAnyDeliveryFacts() {
        seedDelivery();
        assertEquals(1, complete(100, "0", ",\"reasons\":[{\"key\":\"lost\",\"qty\":100}]").code());
        assertEquals(1, complete(2, "0", ",\"reasons\":[{\"key\":\"invented\",\"qty\":2}]").code());
        assertEquals(1, complete(2, "0", ",\"reasons\":[{\"key\":\"damaged\",\"qty\":1.5}]").code());
        assertEquals(1, complete(2, "0", ",\"reasons\":[{\"key\":\"damaged\",\"qty\":2147483648}]").code());
        assertUnchanged();
    }

    @Test void optionalReasonDoesNotBypassWholeQuantityOrPhysicalReturnLimit() {
        seedDelivery();
        assertEquals(1, complete(2, "1.5", "").code());
        assertEquals(1, complete(2, "-1", "").code());
        assertEquals(1, complete(2, "2147483648", "").code());
        assertEquals(1, complete(2, "99", "").code());
        assertEquals(1, post("/api/delivery/orders/" + order + "/complete",
                staffToken(delivery, "DELIVERY", station),
                "{\"collected\":false,\"returnBucketQty\":1.5}").code());
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order));
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order));
        assertEquals(48, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", station, product));
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id=?", order));
        assertEquals(0, intOf("SELECT COUNT(*) FROM barrel_record WHERE related_order_id=?", order));
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=?", order));
    }
}
