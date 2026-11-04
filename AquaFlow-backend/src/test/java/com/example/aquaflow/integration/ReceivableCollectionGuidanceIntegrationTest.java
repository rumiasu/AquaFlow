package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** 应收展示资格取原款与票单余款渠道；读列表不确认收到任何钱。 */
class ReceivableCollectionGuidanceIntegrationTest extends AbstractIntegrationTest {
    private long station, customer, address, product;
    private String managerToken;

    @BeforeEach
    void seed() {
        station = createStation("应收展示站");
        long manager = createStaff("应收展示站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("应收展示客户", "receivable-guidance");
        address = createAddress(customer, "应收展示地址");
        product = createProduct("应收展示水", 1, "20.00", "30.00", 1, "18.00");
        createCustomerStationConfig(customer, station, 1);
        managerToken = staffToken(manager, "STATION_MANAGER", station);
    }

    private long order(int method) {
        return createOrderFull(customer, address, station, product, 1, 1, method,
                "20.00", "0.00", "20.00", false, 1);
    }

    private long split(int method, String state) {
        long order = order(3);
        jdbc.update("update orders set total_amount=27 where id=?", order);
        jdbc.update("insert into order_ticket_payment(order_id,customer_id,station_id,water_amount,delivery_fee,"
                        + "floor_fee,cash_due,cash_method,state) values(?,?,?,20,3,4,7,?,?)",
                order, customer, station, method, state);
        createPaymentRecord(order, customer, station, "7.00", method, 1);
        return order;
    }

    private com.fasterxml.jackson.databind.JsonNode row(long order) {
        Api response = get("/api/manager/receivables/orders?customerId=" + customer, managerToken);
        assertTrue(response.isSuccess(), response.toString());
        for (var row : response.data()) {
            if (row.path("orderId").asLong() == order) {
                assertTrue(row.path("canRegisterCash").isBoolean(), row.toString());
                assertFalse(row.path("collectionMethodText").asText().isBlank(), row.toString());
                assertFalse(row.path("collectionNextStep").asText().isBlank(), row.toString());
                row.fieldNames().forEachRemaining(name ->
                        assertFalse(name.startsWith("collectionEvidence"), "原款核验资料不应直接回传"));
                return row;
            }
        }
        fail("待收订单必须仍在列表中：" + order);
        return null;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cashOrderUsesExplicitCashEligibilityWithoutConfirmingPayment(boolean hasOriginal) {
        long order = order(2);
        if (hasOriginal) createPaymentRecord(order, customer, station, "20.00", 2, 1);
        var row = row(order);
        assertTrue(row.path("canRegisterCash").asBoolean());
        assertTrue(row.path("collectionMethodText").asText().contains("现金"));
        assertTrue(row.path("collectionBlockReason").asText().isEmpty());
        assertEquals(1, intOf("select payment_status from orders where id=?", order));
        assertEquals(0, intOf("select count(*) from payment_record where order_id=? and status=2", order));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unpaidWechatRemainsVisibleButCannotBeRegisteredAsCash(boolean hasOriginal) {
        long order = order(1);
        if (hasOriginal) createPaymentRecord(order, customer, station, "20.00", 1, 1);
        var row = row(order);
        assertFalse(row.path("canRegisterCash").asBoolean());
        assertTrue(row.path("collectionMethodText").asText().contains("微信"));
        assertTrue(row.path("collectionBlockReason").asText().contains("微信"));
        assertTrue(row.path("collectionNextStep").asText().contains("原"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HELD", "CONSUMED", "REFUNDED"})
    void ticketCashFeeEligibilityUsesSplitChannelIncludingWaterAlreadyRefunded(String state) {
        var row = row(split(2, state));
        assertEquals(3, row.path("paymentMethod").asInt());
        assertTrue(row.path("canRegisterCash").asBoolean());
        assertTrue(row.path("collectionMethodText").asText().contains("现金"));
        assertTrue(row.path("collectionMethodText").asText().contains("水票"));
        assertEquals(7, row.path("totalAmount").asInt(), "应收只含实际现金余款");
    }

    @ParameterizedTest
    @CsvSource({"1, HELD", "2, CANCELLED"})
    void onlineOrCancelledTicketFeeCannotBeRegisteredAsCash(int method, String state) {
        var row = row(split(method, state));
        assertFalse(row.path("canRegisterCash").asBoolean());
        assertFalse(row.path("collectionBlockReason").asText().isBlank());
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 20.00", "2, 1, 21.00", "2, 2, 20.00", "2, 3, 20.00"})
    void inconsistentOriginalReceiptIsShownForVerificationInsteadOfCashRegistration(int method, int status, String amount) {
        long order = order(2);
        createPaymentRecord(order, customer, station, amount, method, status);
        var row = row(order);
        assertFalse(row.path("canRegisterCash").asBoolean());
        assertFalse(row.path("collectionBlockReason").asText().isBlank());
        assertTrue(row.path("collectionNextStep").asText().contains("核对"));
    }

    @Test
    void legacyTicketWithoutSplitDoesNotBecomeCashCollectable() {
        var row = row(order(3));
        assertFalse(row.path("canRegisterCash").asBoolean());
        assertFalse(row.path("collectionBlockReason").asText().isBlank());
    }

    @Test
    void activeOriginalTakesPrecedenceOverLaterClosedHistoricalReceipt() {
        long order = order(2);
        long active = createPaymentRecord(order, customer, station, "20.00", 2, 1);
        long closed = createPaymentRecord(order, customer, station, "20.00", 1, 4);
        jdbc.update("update payment_record set create_time=date_add(now(),interval 1 minute) where id=?", closed);
        assertTrue(row(order).path("canRegisterCash").asBoolean());
        assertEquals(1, intOf("select status from payment_record where id=?", active));
    }

    @Test
    void cancelledCashRequestCanBeRetriedWithoutReopeningItsHistoricalReceipt() {
        long order = order(2);
        long closed = createPaymentRecord(order, customer, station, "20.00", 2, 4);
        boolean displayedAsCollectable = row(order).path("canRegisterCash").asBoolean();

        Api collected = post("/api/manager/receivables/settle", managerToken,
                "{\"customerId\":" + customer + ",\"orderIds\":[" + order + "]}");
        assertTrue(collected.isSuccess(), collected.toString());
        assertEquals(4, intOf("select status from payment_record where id=?", closed));
        assertEquals(1, intOf("select count(*) from payment_record where order_id=? and status=2 and payment_method=2", order));
        assertTrue(displayedAsCollectable, "合法新现金收款不能被已关闭的历史请求挡在应收页之外");
    }

    @Test
    void actualDispatchMovesPendingReceiptAndKeepsCashRegistrationAvailableToNewStation() {
        long receivingStation = createStation("应收接收站");
        long receivingManager = createStaff("应收接收站长", "STATION_MANAGER", receivingStation, 1);
        long goods = createProduct("应收饮水机", 2, "20.00", "0.00", 0, "0.00");
        createInventoryFull(station, goods, 10, 0, "0.00");
        createInventoryFull(receivingStation, goods, 10, 0, "0.00");
        Api created = post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + station + ",\"addressId\":" + address
                        + ",\"paymentMethod\":2,\"idempotencyKey\":\"receivable-dispatch\","
                        + "\"items\":[{\"productId\":" + goods + ",\"quantity\":1}]}");
        assertTrue(created.isSuccess(), created.toString());
        long order = created.data().path("orderId").asLong();
        Api requested = post("/api/payments", customerToken(customer),
                "{\"orderId\":" + order + ",\"paymentMethod\":2}");
        assertTrue(requested.isSuccess(), requested.toString());
        long original = longOf("select id from payment_record where order_id=? and status=1", order);
        Api dispatched = post("/api/delivery/orders/" + order + "/dispatch", managerToken,
                "{\"targetStationId\":" + receivingStation + "}");
        assertTrue(dispatched.isSuccess(), dispatched.toString());
        assertEquals(receivingStation, longOf("select station_id from payment_record where id=?", original));
        assertEquals(0, get("/api/manager/receivables/orders?customerId=" + customer, managerToken).data().size());
        managerToken = staffToken(receivingManager, "STATION_MANAGER", receivingStation);
        assertTrue(row(order).path("canRegisterCash").asBoolean());
    }
}
