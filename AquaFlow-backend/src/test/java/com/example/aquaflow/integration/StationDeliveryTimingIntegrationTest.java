package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@Import(StationDeliveryTimingIntegrationTest.BusinessClock.class)
@TestPropertySource(properties = "app.payment.test-mode=true")
class StationDeliveryTimingIntegrationTest extends AbstractIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 17, 0);
    private long station;
    private long customer;
    private long address;
    private long product;
    private String manager;

    @TestConfiguration
    static class BusinessClock {
        @Bean @Primary Clock timingTestClock() {
            var zone = ZoneId.of("Asia/Shanghai");
            return Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        }
    }

    @BeforeEach
    void seed() {
        station = createStation("营业时间站");
        customer = createCustomer("时限客户", "delivery-timing-customer");
        address = createAddress(customer, "测试地址");
        product = createProduct("配送用瓶装水", 2, "20.00", "0.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        createCustomerStationConfig(customer, station, 1);
        manager = staffToken(createStaff("时限站长", "STATION_MANAGER", station, 1), "STATION_MANAGER", station);
    }

    @Test
    void exactClosingUsesNextOpeningAndOrderKeepsItsDeadlineAfterEdit() {
        var hours = get("/api/manager/station-status", manager).data().path("deliveryHours");
        assertEquals("08:00", hours.path("opensAt").asText());
        assertEquals("17:00", hours.path("closesAt").asText());
        assertEquals(60, hours.path("openMinutes").asInt());
        assertEquals(120, hours.path("closedMinutes").asInt());
        assertFalse(hours.path("openNow").asBoolean());

        long order = place("closing", 2, 1);
        assertEquals(NOW.plusDays(1).withHour(10), deadline(order));
        assertEquals("11月14日 10:00", get("/api/orders/" + order, customerToken(customer))
                .data().path("deliveryDeadlineText").asText());
        var saved = put("/api/manager/station-status/hours", manager,
                "{\"opensAt\":\"08:00\",\"closesAt\":\"17:00\",\"openMinutes\":45,\"closedMinutes\":95,\"closedHours\":2}");
        assertTrue(saved.isSuccess(), saved.message());
        assertEquals("已打烊", saved.data().path("stationStatusText").asText());
        assertEquals(NOW.plusDays(1).withHour(9).withMinute(35), deadline(place("exact-minutes", 2, 1)));
        assertTrue(saveHours("08:00", "17:00", 40, 2).isSuccess());
        assertEquals(95, get("/api/manager/station-status", manager)
                .data().path("deliveryHours").path("closedMinutes").asInt());
        assertTrue(saveHours("17:00", "01:00", 45, 3).isSuccess());
        var publicHours = get("/api/stations/" + station + "/status", null).data().path("deliveryHours");
        assertTrue(publicHours.path("openNow").asBoolean());
        assertTrue(publicHours.path("overnight").asBoolean());
        assertEquals(3, publicHours.path("closedHours").asInt());
        assertEquals("营业中", get("/api/stations/" + station + "/status", null).data().path("statusText").asText());
        assertEquals(NOW.plusMinutes(45), deadline(place("opening", 2, 1)));
        assertEquals(order, place("closing", 2, 1));
        assertEquals(NOW.plusDays(1).withHour(10), deadline(order));
    }

    @Test
    void specialOperatingStatusAndShortageDoNotPromiseADeadline() {
        assertNull(deadline(place("stock", 2, 11)));
        assertFalse(put("/api/manager/station-status", manager, "{\"operatingStatus\":2}").isSuccess());
        jdbc.update("UPDATE station SET operating_status=2 WHERE id=?", station);
        assertFalse(get("/api/stations/" + station + "/status", null).data().has("deliveryHours"));
        assertNull(deadline(place("resting", 2, 1)));
    }

    @Test
    void prepaidOrderGetsItsDeadlineAtPaymentAndKeepsItOnRetry() {
        long order = place("prepay", 1, 1);
        assertNull(deadline(order));
        assertTrue(saveHours("16:00", "23:00", 35, 3).isSuccess());
        String payment = "{\"orderId\":" + order + ",\"paymentMethod\":1}";
        var first = post("/api/payments", customerToken(customer), payment);
        assertTrue(first.isSuccess(), first.message());
        assertEquals(NOW.plusMinutes(35), deadline(order));
        assertTrue(saveHours("16:00", "23:00", 20, 3).isSuccess());
        assertTrue(post("/api/payments", customerToken(customer), payment).isSuccess());
        assertEquals(NOW.plusMinutes(35), deadline(order));
    }

    @Test
    void validationAndStationRoleGuardApplyToHours() {
        assertFalse(saveHours("08:00", "08:00", 45, 3).isSuccess());
        assertFalse(saveHours("08:00", "20:00", 0, 3).isSuccess());
        assertFalse(saveHours("08:00", "20:00", 45, 25).isSuccess());
        String driver = staffToken(createStaff("配送员", "DELIVERY", station, 1), "DELIVERY", station);
        assertFalse(put("/api/manager/station-status/hours", driver, hoursBody("09:00", "18:00", 45, 3)).isSuccess());
        long other = createStation("另一站");
        assertTrue(saveHours("09:00", "18:00", 45, 3).isSuccess());
        assertEquals("08:00", get("/api/stations/" + other + "/status", null)
                .data().path("deliveryHours").path("opensAt").asText());
    }

    private Api saveHours(String opens, String closes, int minutes, int hours) {
        return put("/api/manager/station-status/hours", manager, hoursBody(opens, closes, minutes, hours));
    }

    private String hoursBody(String opens, String closes, int minutes, int hours) {
        return """
                {"opensAt":"%s","closesAt":"%s","openMinutes":%d,"closedHours":%d}
                """.formatted(opens, closes, minutes, hours);
    }

    private long place(String key, int method, int quantity) {
        Api response = post("/api/orders/create", customerToken(customer), """
                {"stationId":%d,"addressId":%d,"paymentMethod":%d,"idempotencyKey":"%s",
                "confirmShortage":true,"items":[{"productId":%d,"quantity":%d}]}
                """.formatted(station, address, method, key, product, quantity));
        assertTrue(response.isSuccess(), response.message());
        return response.data().path("orderId").asLong();
    }

    private LocalDateTime deadline(long order) {
        return jdbc.queryForObject("select delivery_deadline from orders where id=?", LocalDateTime.class, order);
    }
}
