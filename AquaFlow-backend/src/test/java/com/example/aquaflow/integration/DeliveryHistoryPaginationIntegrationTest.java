package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 SQL 的同秒游标、旧客户端兼容与本人历史范围。 */
class DeliveryHistoryPaginationIntegrationTest extends AbstractIntegrationTest {
    @Test
    void cursorAndLegacyScope() {
        long station = createStation("历史分页站"), other = createStation("历史其他站");
        long staff = createStaff("历史配送员", "DELIVERY", station, 1);
        long otherStaff = createStaff("其他配送员", "DELIVERY", station, 1);
        long customer = createCustomer("历史客户", "history-page-customer");
        long product = createProduct("历史水", 1, "10.00", "0.00", 0, "0.00");
        long address = createAddress(customer, "历史地址");
        long[] ids = new long[3];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = createOrderFull(customer, address, station, product, 4, 2, 2, "10.00", "0.00", "10.00", false, 1);
            jdbc.update("update orders set delivery_staff_id=?, create_time='2026-10-02 10:00:00' where id=?", staff, ids[i]);
        }
        long previousStation = createOrderFull(customer, address, other, product, 4, 2, 2, "10.00", "0.00", "10.00", false, 1);
        jdbc.update("update orders set delivery_staff_id=?, create_time='2026-10-01 10:00:00' where id=?", staff, previousStation);
        long hiddenStaff = createOrderFull(customer, address, station, product, 4, 2, 2, "10.00", "0.00", "10.00", false, 1);
        jdbc.update("update orders set delivery_staff_id=? where id=?", otherStaff, hiddenStaff);
        String token = staffToken(staff, "DELIVERY", station);
        Api first = get("/api/delivery/history?pageSize=2", token);
        assertEquals(0, first.code()); JsonNode data = first.data();
        assertEquals(2, data.path("orders").size());
        assertEquals(ids[2], data.path("orders").get(0).path("id").asLong());
        assertEquals(ids[1], data.path("orders").get(1).path("id").asLong());
        assertTrue(data.path("hasMore").asBoolean());
        String cursor = URLEncoder.encode(data.path("nextBeforeTime").asText(), StandardCharsets.UTF_8);
        Api last = get("/api/delivery/history?pageSize=2&beforeTime=" + cursor + "&beforeId=" + data.path("nextBeforeId").asLong(), token);
        assertEquals(0, last.code()); assertEquals(2, last.data().path("orders").size());
        assertEquals(ids[0], last.data().path("orders").get(0).path("id").asLong());
        assertEquals(previousStation, last.data().path("orders").get(1).path("id").asLong());
        assertFalse(last.data().path("hasMore").asBoolean());
        Api legacy = get("/api/delivery/history", token);
        assertEquals(0, legacy.code()); assertTrue(legacy.data().isArray()); assertEquals(4, legacy.data().size());
        for (JsonNode order : legacy.data()) assertNotEquals(hiddenStaff, order.path("id").asLong());
        assertNotEquals(0, get("/api/delivery/history?pageSize=101", token).code());
        assertNotEquals(0, get("/api/delivery/history?pageSize=2&beforeId=1", token).code());
    }
}
