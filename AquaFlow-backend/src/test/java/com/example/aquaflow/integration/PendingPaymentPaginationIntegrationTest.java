package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PendingPaymentPaginationIntegrationTest extends AbstractIntegrationTest {
    private long station;
    private long customer;
    private long address;
    private long product;
    private String token;

    @BeforeEach
    void seed() {
        station = createStation("分页合成水站");
        customer = createCustomer("分页合成客户", "pending-pagination");
        address = createAddress(customer, "分页合成地址");
        product = createProduct("分页合成商品", 2, "12", "0", 0, "0");
        token = staffToken(createStaff("分页站长", "STATION_MANAGER", station, 1), "STATION_MANAGER", station);
    }

    private long pending(Long orderId, long targetStation) {
        long id = createPaymentRecord(orderId, customer, targetStation, "12", 2, 1);
        jdbc.update("update payment_record set create_time='2026-11-10 09:00:00' where id=?", id);
        return id;
    }

    private JsonNode page(int size, String cursor, String reader) {
        String path = "/api/payments/pending?pageSize=" + size;
        if (cursor != null) path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        Api response = get(path, reader);
        assertEquals(0, response.code(), response.toString());
        return response.data();
    }

    private List<Long> ids(JsonNode rows) {
        List<Long> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.path("id").asLong()));
        return result;
    }

    @Test
    void legacyArrayStaysLimitedAndStableWhilePagesReachMoreThanTwoHundred() {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 225; i++) expected.add(pending(null, station));
        Api legacy = get("/api/payments/pending", token);
        assertEquals(0, legacy.code());
        assertTrue(legacy.data().isArray());
        assertEquals(expected.subList(0, 200), ids(legacy.data()));
        assertFalse(legacy.data().get(0).has("pendingCursor"));
        assertEquals(10, get("/api/payments/pending?limit=10", token).data().size());
        List<Long> actual = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode data = page(50, cursor, token);
            actual.addAll(ids(data.path("items")));
            cursor = data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText();
        } while (cursor != null);
        assertEquals(expected, actual);
    }

    @Test
    void confirmingAndRemovingEarlierRowsDoesNotSkipRemainingRowsOrNewReceipts() {
        long order = createOrderFull(customer, address, station, product, 3, 1, 2, "12", "0", "12", false, 0);
        List<Long> expected = new ArrayList<>();
        expected.add(pending(order, station));
        for (int i = 0; i < 210; i++) expected.add(pending(null, station));
        JsonNode first = page(50, null, token);
        String cursor = first.path("nextCursor").asText();
        assertEquals(expected.subList(0, 50), ids(first.path("items")));
        Api confirmed = put("/api/payments/" + expected.get(0) + "/confirm", token, "{}");
        assertEquals(0, confirmed.code(), confirmed.toString());
        assertEquals(2, intOf("select payment_status from orders where id=?", order));
        // The cursor's row can leave the pending set, as can unseen rows changed by another operator.
        jdbc.update("update payment_record set status=4 where id in (?,?)", expected.get(49), expected.get(51));
        long added = pending(null, station);
        List<Long> remaining = new ArrayList<>(expected.subList(50, expected.size()));
        remaining.remove(expected.get(51));
        remaining.add(added);
        List<Long> actual = new ArrayList<>();
        do {
            JsonNode data = page(50, cursor, token);
            actual.addAll(ids(data.path("items")));
            cursor = data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText();
        } while (cursor != null);
        assertEquals(remaining, actual);
        assertTrue(actual.contains(added));
    }

    @Test
    void pageAndCursorAlwaysUseTheLoggedInSettlementStationAndKeepMasking() {
        long other = createStation("分页另一站");
        String otherToken = staffToken(createStaff("另一站长", "STATION_MANAGER", other, 1), "STATION_MANAGER", other);
        long foreign = pending(null, other);
        long order = createOrderCrossStation(customer, address, station, other, product, 2, 1, 2, "12", "0", "12");
        jdbc.update("update orders set settle_station_id=? where id=?", other, order);
        long cross = pending(order, other);
        long own = pending(null, station);
        JsonNode ownPage = page(1, null, token);
        assertEquals(List.of(own), ids(ownPage.path("items")));
        JsonNode otherFirst = page(1, null, otherToken);
        assertEquals(List.of(foreign), ids(otherFirst.path("items")));
        JsonNode otherNext = page(1, otherFirst.path("nextCursor").asText(), otherToken);
        assertEquals(List.of(cross), ids(otherNext.path("items")));
        // Map 序列化可省略 SQL NULL；与既有跨站隔离回归一致，契约是不下发有值的客户画像。
        JsonNode crossRow = otherNext.path("items").get(0);
        assertFalse(crossRow.hasNonNull("customerName"), crossRow.toString());
        assertFalse(crossRow.hasNonNull("customerPhone"), crossRow.toString());
        JsonNode forgedStation = get("/api/payments/pending?pageSize=50&stationId=" + other, token).data();
        assertEquals(List.of(own), ids(forgedStation.path("items")));
        assertFalse(ids(page(50, otherFirst.path("nextCursor").asText(), token).path("items")).contains(cross));
    }

    @Test
    void legacyMissingCreationTimeContinuesByIdBeforeDatedRows() {
        long first = pending(null, station), second = pending(null, station), dated = pending(null, station);
        jdbc.update("update payment_record set create_time=null where id in (?,?)", first, second);
        JsonNode data = page(1, null, token);
        assertEquals(List.of(first), ids(data.path("items")));
        String cursor = data.path("nextCursor").asText();
        assertEquals("~" + first, cursor);
        jdbc.update("update payment_record set status=4 where id=?", first);
        assertEquals(List.of(second, dated), ids(page(50, cursor, token).path("items")));
    }

    @Test
    void invalidBoundsAndCursorsFailReadablyAndEmptyPageRemainsExplicit() {
        for (String query : List.of("pageSize=0", "pageSize=201", "cursor=~1", "pageSize=10&cursor=bad",
                "pageSize=10&cursor=~0", "pageSize=10&cursor=~9999999999999999999",
                "pageSize=10&cursor=2026-02-31T09:00:00.000000~1")) {
            assertEquals(1, get("/api/payments/pending?" + query, token).code(), query);
        }
        JsonNode empty = page(50, null, token);
        assertTrue(empty.path("items").isEmpty());
        assertTrue(empty.path("nextCursor").isNull());
        assertEquals(401, get("/api/payments/pending?pageSize=50", null).status());
        String driver = staffToken(createStaff("分页配送员", "DELIVERY", station, 1), "DELIVERY", station);
        assertEquals(1, get("/api/payments/pending?pageSize=50", driver).code());
    }
}
