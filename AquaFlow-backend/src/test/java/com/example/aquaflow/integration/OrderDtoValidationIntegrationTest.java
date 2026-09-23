package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase D-C1 契约测试：OrderController.create 改为 @Valid OrderCreateDTO 后，
 * 非法入参必须在边界被拒（code != 0）且不落库。
 */
class OrderDtoValidationIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("D-C1 · POST /api/orders/create 缺 items 被 Bean Validation 拒回，且不建订单")
    void createMissingItemsRejected() {
        long customer = createCustomer("OC1", "openid-oc1");
        String token = customerToken(customer);

        Api api = post("/api/orders/create", token,
                "{\"addressId\":1,\"stationId\":1,\"paymentMethod\":3}");

        assertFalse(api.isSuccess(), "缺 items 应被拒: " + api);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "非法下单不应建订单");
    }

    @Test
    @DisplayName("D-C1 · POST /api/orders/create 商品缺 productId 被 @NotNull 拒回")
    void createItemMissingProductIdRejected() {
        long customer = createCustomer("OC2", "openid-oc2");
        long station = createStation("ST-OC2");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        String token = customerToken(customer);

        Api api = post("/api/orders/create", token,
                "{\"addressId\":1,\"stationId\":" + station
                        + ",\"paymentMethod\":3,\"items\":[{\"quantity\":2}]}");

        assertFalse(api.isSuccess(), "商品缺 productId 应被拒: " + api);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "非法下单不应建订单");
    }

    @Test
    @DisplayName("D-C1 · POST /api/orders/create 商品 quantity=0 被 @Min(1) 拒回")
    void createItemZeroQuantityRejected() {
        long customer = createCustomer("OC3", "openid-oc3");
        long station = createStation("ST-OC3");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        String token = customerToken(customer);

        Api api = post("/api/orders/create", token,
                "{\"addressId\":1,\"stationId\":" + station
                        + ",\"paymentMethod\":3,\"items\":[{\"productId\":" + product + ",\"quantity\":0}]}");

        assertFalse(api.isSuccess(), "quantity<1 应被拒: " + api);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "非法下单不应建订单");
    }
}
