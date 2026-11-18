package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StationProductPromotionIntegrationTest extends AbstractIntegrationTest {
    private String manager(long station) {
        return staffToken(createStaff("站长", "STATION_MANAGER", station, 1), "STATION_MANAGER", station);
    }

    private void saved(String path, String token, String body) {
        Api result = put(path, token, body);
        assertTrue(result.isSuccess(), result.toString());
    }

    private void price(JsonNode row, String key, String value) {
        assertTrue(row.hasNonNull(key), key + " missing in " + row);
        assertEquals(0, new BigDecimal(value).compareTo(row.get(key).decimalValue()), key);
    }

    @Test void promotionAndDisplayDetailsReachCatalogQuoteAndOrderOnlyAtThisStation() {
        long station = createStation("活动站"), other = createStation("相邻站");
        String manager = manager(station);
        long product = createProduct("模板水", 2, "23", "0", 0, "0");
        jdbc.update("update product set spec='550mL' where id=?", product);
        createInventory(station, product, 30);
        createInventory(other, product, 10);
        long customer = createCustomer("客户", "station-promo-order");
        String auth = customerToken(customer);
        long address = createAddress(customer, "测试小区");
        String path = "/api/manager/catalog/" + product;
        saved(path, manager, """
                {"salePrice":20,"promotionActive":true,"promotionPrice":15.25,
                 "stationProductName":"本站瓶装水","stationProductSpec":"550mL × 12瓶"}
                """);
        var employee = get("/api/manager/catalog", manager).data().get(0);
        var item = get("/api/products/sale-by-station?stationId=" + station, auth).data().get(0);
        String detail = "/api/products/" + product + "?stationId=";
        for (JsonNode row : List.of(employee, item, get(detail + station, auth).data())) {
            assertEquals("本站瓶装水", row.path("name").asText());
            assertEquals("550mL × 12瓶", row.path("spec").asText());
            assertTrue(row.path("promotionActive").asBoolean());
            price(row, "effectivePrice", "15.25");
            price(row, "promotionPrice", "15.25");
            price(row, "regularPrice", "20");
        }
        assertEquals("模板水", employee.path("platformName").asText());
        assertEquals("550mL", employee.path("platformSpec").asText());
        var otherItem = get(detail + other, auth).data();
        assertEquals("模板水", otherItem.path("name").asText());
        assertEquals("550mL", otherItem.path("spec").asText());
        assertFalse(otherItem.path("promotionActive").asBoolean());
        price(otherItem, "effectivePrice", "23");
        price(item, "effectiveTicketPrice", "20");

        String order = """
                {"stationId":%d,"addressId":%d,"paymentMethod":1,"idempotencyKey":"promo-order",
                 "items":[{"productId":%d,"quantity":2}]}
                """.formatted(station, address, product);
        price(post("/api/payments/quote", auth, order).data(), "waterAmount", "30.50");
        Api created = post("/api/orders/create", auth, order);
        assertTrue(created.isSuccess(), created.toString());
        long orderId = created.data().path("orderId").asLong();
        saved(path, manager, "{\"enabled\":0}");
        assertTrue(get("/api/products/sale-by-station?stationId=" + station, auth).data().isEmpty());
        var unavailable = post("/api/orders/create", auth, order.replace("promo-order", "new-order-after-unlist"));
        assertFalse(unavailable.isSuccess());
        assertTrue(unavailable.message().contains("未上架"), unavailable.toString());
        assertEquals(1, intOf("select count(*) from orders where station_id=?", station));
        assertEquals(1, get("/api/products/sale-by-station?stationId=" + other, auth).data().size());
        saved(path, manager, "{\"enabled\":1}");
        saved(path, manager, "{\"salePrice\":21}");
        price(get(detail + station, auth).data(), "effectivePrice", "15.25");
        saved(path, manager, "{\"promotionActive\":true,\"promotionPrice\":14}");
        price(get(detail + station, auth).data(), "effectivePrice", "14");
        saved(path, manager, "{\"promotionActive\":false,\"stationProductName\":\"\",\"stationProductSpec\":\"\"}");
        var ended = get(detail + station, auth).data();
        assertFalse(ended.path("promotionActive").asBoolean());
        assertFalse(ended.hasNonNull("promotionPrice"));
        assertEquals("模板水", ended.path("name").asText());
        assertEquals("550mL", ended.path("spec").asText());
        price(ended, "effectivePrice", "21");
        price(post("/api/payments/quote", auth, order).data(), "waterAmount", "42");
        assertEquals(new BigDecimal("30.50"), decimalOf("select water_amount from orders where id=?", orderId));
        var snapshot = jdbc.queryForMap("select product_name_snapshot,spec_snapshot,price from order_item where order_id=?", orderId);
        assertEquals("本站瓶装水", snapshot.get("product_name_snapshot"));
        assertEquals("550mL × 12瓶", snapshot.get("spec_snapshot"));
        assertEquals(new BigDecimal("15.25"), snapshot.get("price"));
        assertEquals(30, intOf("select quantity from inventory where station_id=? and product_id=?", station, product));
    }

    @Test void platformWithdrawalAlsoRemovesThePromotedLineFromQuotes() {
        long station = createStation("停售信息同步");
        long product = createProduct("停售前的促销商品", 2, "23", "0", 0, "0");
        createInventory(station, product, 4);
        long customer = createCustomer("报价测试", "promo-stop-quote");
        String auth = customerToken(customer);
        long address = createAddress(customer, "测试街");
        saved("/api/manager/catalog/" + product, manager(station), "{\"promotionActive\":true,\"promotionPrice\":12}");
        String cart = """
                {"stationId":%d,"addressId":%d,"paymentMethod":1,"idempotencyKey":"withdrawn-promo",
                 "items":[{"productId":%d,"quantity":2}]}
                """.formatted(station, address, product);
        price(post("/api/payments/quote", auth, cart).data(), "waterAmount", "24");
        for (int status : List.of(0, 2)) {
            jdbc.update("update product set status=? where id=?", status, product);
            assertTrue(get("/api/products/sale-by-station?stationId=" + station, auth).data().isEmpty());
            price(post("/api/payments/quote", auth, cart).data(), "waterAmount", "0");
            assertFalse(post("/api/orders/create", auth, cart).isSuccess());
        }
        assertEquals(0, intOf("select count(*) from orders where station_id=?", station));
    }

    @Test void validationIsAtomicAndTicketPricesRemainIndependent() {
        long station = createStation("价格站"), other = createStation("另一站");
        long product = createProduct("桶装水", 1, "20", "50", 1, "16");
        String manager = manager(station);
        String delivery = staffToken(createStaff("配送", "DELIVERY", station, 1), "DELIVERY", station);
        String auth = customerToken(createCustomer("试算客户", "station-promo-ticket"));
        createInventory(station, product, 4);
        String path = "/api/manager/catalog/" + product;
        String detail = "/api/products/" + product + "?stationId=" + station;
        String valid = "{\"promotionActive\":true,\"promotionPrice\":12}";
        assertFalse(put(path, delivery, valid).isSuccess());
        assertFalse(put(path, manager(other), valid).isSuccess());
        saved(path, manager, valid);
        price(get(detail, auth).data(), "effectiveDeposit", "50");
        price(get(detail, auth).data(), "effectiveTicketPrice", "16");
        saved(path, manager, "{\"ticketEnabled\":1,\"ticketPrice\":17}");
        price(get(detail, auth).data(), "effectiveTicketPrice", "17");
        String ticketQuote = """
                {"stationId":%d,"paymentMethod":3,"items":[{"productId":%d,"quantity":1}]}
                """.formatted(station, product);
        price(post("/api/payments/quote", auth, ticketQuote).data(), "waterAmount", "17");
        for (String invalid : List.of("{\"salePrice\":12}", "{\"promotionPrice\":11}", "{\"promotionActive\":true}",
                "{\"promotionPrice\":11,\"promotionActive\":false}", "{\"promotionActive\":true,\"promotionPrice\":0}",
                "{\"promotionActive\":true,\"promotionPrice\":20}", "{\"promotionActive\":true,\"promotionPrice\":9.999}",
                "{\"salePrice\":24,\"stationProductName\":\"" + "名".repeat(101) + "\"}")) {
            assertFalse(put(path, manager, invalid).isSuccess(), invalid);
        }
        price(get(detail, auth).data(), "effectivePrice", "12");
        price(get(detail, auth).data(), "regularPrice", "20");
        assertEquals(new BigDecimal("12.00"), decimalOf(
                "select promotion_price from inventory where station_id=? and product_id=?", station, product),
                "被拒绝的设置不得改写原促销价");
        saved(path, manager, "{\"promotionActive\":false,\"salePrice\":10}");
        price(get(detail, auth).data(), "effectivePrice", "10");
    }

    @Test void simultaneousPriceAndDetailsPatchesPreserveBothEdits() {
        long station = createStation("并行编辑站");
        String manager = manager(station);
        long product = createProduct("原名称", 2, "20", "0", 0, "0");
        createInventory(station, product, 8);
        String path = "/api/manager/catalog/" + product;
        saved(path, manager, "{\"stationProductName\":\"本站名称\"}");
        assertEquals(0, intOf("select pricing_confirmed from inventory where station_id=? and product_id=?", station, product));
        var details = CompletableFuture.runAsync(() -> saved(path, manager, "{\"stationProductSpec\":\"12瓶装\"}"));
        var priceEdit = CompletableFuture.runAsync(() -> saved(path, manager,
                "{\"salePrice\":24,\"promotionActive\":true,\"promotionPrice\":18}"));
        CompletableFuture.allOf(details, priceEdit).join();
        var row = get("/api/manager/catalog", manager).data().get(0);
        price(row, "effectivePrice", "18");
        price(row, "regularPrice", "24");
        assertEquals("本站名称", row.path("name").asText());
        assertEquals("12瓶装", row.path("spec").asText());
        assertEquals(8, row.path("quantity").asInt());
    }

    @Test void historicalRowsAtAnotherStationDoNotExposeOrSellPrivateCatalogData() {
        long owner = createStation("商品归属站"), other = createStation("历史记录站");
        String ownerManager = manager(owner), otherManager = manager(other);
        long product = createProduct("私有商品名称", 2, "37", "0", 0, "0");
        jdbc.update("update product set owner_station_id=?,spec='私有规格' where id=?", owner, product);
        createInventory(owner, product, 8);
        createInventory(other, product, 3);
        jdbc.update("insert into inventory_record(station_id,product_id,delta,type) values(?,?,3,'INBOUND')", other, product);
        long customer = createCustomer("货品隔离", "promotion-private-catalog");
        String auth = customerToken(customer);
        long address = createAddress(customer, "测试街");
        createTicketAccount(customer, other, product, 2);
        String path = "/api/manager/catalog/" + product;
        saved(path, ownerManager, "{\"promotionActive\":true,\"promotionPrice\":30}");
        assertFalse(put(path, otherManager, "{\"promotionActive\":true,\"promotionPrice\":15}").isSuccess());
        assertFalse(get("/api/products/" + product + "?stationId=" + other, auth).isSuccess());
        assertTrue(get("/api/products/sale-by-station?stationId=" + other, auth).data().isEmpty());
        assertTrue(get("/api/manager/catalog", otherManager).data().isEmpty());
        for (Api response : List.of(get("/api/inventory", otherManager),
                get("/api/inventory/records?productId=" + product, otherManager), get("/api/tickets?stationId=" + other, auth))) {
            assertTrue(response.isSuccess(), response.toString());
            assertEquals(1, response.data().size(), "应保留本站历史记录");
            assertFalse(response.data().toString().contains("私有"));
        }
        String order = """
                {"stationId":%d,"addressId":%d,"paymentMethod":1,"idempotencyKey":"private-catalog",
                 "items":[{"productId":%d,"quantity":1}]}
                """.formatted(other, address, product);
        var rejected = post("/api/orders/create", auth, order);
        assertFalse(rejected.isSuccess());
        assertFalse(rejected.message().contains("私有"));
        price(post("/api/payments/quote", auth, order).data(), "waterAmount", "0");
        assertEquals(0, intOf("select count(*) from orders where station_id=?", other));
    }
}
