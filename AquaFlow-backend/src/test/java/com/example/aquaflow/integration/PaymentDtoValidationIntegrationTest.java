package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase D-A 契约测试：支付写路径改为强类型 DTO + Bean Validation 后，
 * 非法入参必须在边界被拒（code != 0）且不落库。
 */
class PaymentDtoValidationIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("D-A · POST /api/payments 缺 orderId 被 Bean Validation 拒回，且不落支付流水")
    void createMissingOrderIdRejected() {
        long customerId = createCustomer("C", "openid-c");
        String token = customerToken(customerId);

        Api api = post("/api/payments", token, "{\"amount\":\"10.00\",\"paymentMethod\":1}");

        assertFalse(api.isSuccess(), "缺 orderId 应被拒: " + api);
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record"), "非法请求不应产生支付流水");
    }

    @Test
    @DisplayName("D-A · POST /api/payments 缺 paymentMethod 被拒")
    void createMissingPaymentMethodRejected() {
        long customerId = createCustomer("C2", "openid-c2");
        String token = customerToken(customerId);

        Api api = post("/api/payments", token, "{\"orderId\":99999,\"amount\":\"10.00\"}");

        assertFalse(api.isSuccess(), "缺 paymentMethod 应被拒: " + api);
    }

    @Test
    @DisplayName("D-A · POST /api/payments/quote 空 items 被拒")
    void quoteEmptyItemsRejected() {
        long customerId = createCustomer("C3", "openid-c3");
        String token = customerToken(customerId);

        Api api = post("/api/payments/quote", token,
                "{\"stationId\":1,\"paymentMethod\":1,\"items\":[]}");

        assertFalse(api.isSuccess(), "空 items 应被拒: " + api);
    }

    @Test
    @DisplayName("D-A · POST /api/payments/quote 缺 stationId 被拒")
    void quoteMissingStationRejected() {
        long customerId = createCustomer("C4", "openid-c4");
        String token = customerToken(customerId);

        Api api = post("/api/payments/quote", token,
                "{\"paymentMethod\":1,\"items\":[{\"productId\":1,\"quantity\":1}]}");

        assertFalse(api.isSuccess(), "缺 stationId 应被拒: " + api);
    }
}
