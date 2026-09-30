package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // [F-31 2026-09-30] 原来只断言 !isSuccess —— 而 body 里的 orderId=99999 本身就会被业务层
        // 以「订单不存在」拒回，于是把 paymentMethod 上的 @NotNull 摘掉，这条用例照样是绿的
        // （走错分支也算过）。现在钉住：拒绝必须**出自 Bean Validation 的字段级文案**。
        assertTrue(api.message() != null && api.message().contains("支付方式不能为空"),
                "拒绝必须点名缺的是 paymentMethod，不能是被别的分支（如订单不存在）挡下，实际=" + api.message());
    }

    @Test
    @DisplayName("D-A · POST /api/payments/quote 空 items 被拒")
    void quoteEmptyItemsRejected() {
        long customerId = createCustomer("C3", "openid-c3");
        String token = customerToken(customerId);

        Api api = post("/api/payments/quote", token,
                "{\"stationId\":1,\"paymentMethod\":1,\"items\":[]}");

        assertFalse(api.isSuccess(), "空 items 应被拒: " + api);
        // [F-31 2026-09-30] 原来只断言 !isSuccess —— stationId=1 在空库里根本不存在，
        // 业务层「水站不存在」同样会拒，@Size(min=1) 被摘掉也看不出来。现钉住字段级文案。
        assertTrue(api.message() != null && api.message().contains("至少包含一个商品"),
                "拒绝必须来自 items 的 @Size(min=1)，不能是被别的分支挡下，实际=" + api.message());
    }

    @Test
    @DisplayName("D-A · POST /api/payments/quote 缺 stationId 被拒")
    void quoteMissingStationRejected() {
        long customerId = createCustomer("C4", "openid-c4");
        String token = customerToken(customerId);

        Api api = post("/api/payments/quote", token,
                "{\"paymentMethod\":1,\"items\":[{\"productId\":1,\"quantity\":1}]}");

        assertFalse(api.isSuccess(), "缺 stationId 应被拒: " + api);
        // [F-31 2026-09-30] 同上：productId=1 不存在也会拒，只说"不成功"证明不了
        // stationId 的 @NotNull 还在。现钉住字段级文案。
        assertTrue(api.message() != null && api.message().contains("请先选择服务水站"),
                "拒绝必须点名缺的是 stationId，实际=" + api.message());
    }
}
