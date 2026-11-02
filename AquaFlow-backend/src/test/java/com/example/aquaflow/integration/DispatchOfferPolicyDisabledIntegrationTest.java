package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-03 的兼容边界：**开关关闭**时没有协议行，旧接单命令必须照旧可用
 * （与 B03 之前完全一致；本用例不注入任何 token）。
 *
 * <p>与「模型已启用但协议行缺失 → fail closed」是两条相反的判据，分别由本类与
 * {@code DispatchOfferConsentIntegrationTest#enabledModelFailsClosedWhenTheAgreementRowIsMissing} 覆盖。</p>
 */
@TestPropertySource(properties = "aquaflow.barrel.independent-rights-enabled=false")
class DispatchOfferPolicyDisabledIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("开关关闭：跨站单不建协议行，旧接单命令仍可接受")
    void disabledPolicyKeepsCrossStationAcceptanceWithoutAnAgreement() {
        long a = createStation("停用甲站"), b = createStation("停用乙站");
        long ma = createStaff("停用甲站长", "STATION_MANAGER", a, 1);
        long mb = createStaff("停用乙站长", "STATION_MANAGER", b, 1);
        long product = createProduct("停用瓶装水", 2, "40.00", "0.00", 0, "0.00");
        createInventoryFull(a, product, 20, 0, "0.00");
        createInventoryFull(b, product, 20, 0, "0.00");
        long customer = createCustomer("停用合成客户", "offer-disabled-only");
        long address = createAddress(customer, "停用合成门牌");
        createCustomerStationConfig(customer, a, 1);
        String ca = customerToken(customer);
        String ta = staffToken(ma, "STATION_MANAGER", a);
        String tb = staffToken(mb, "STATION_MANAGER", b);

        Api created = post("/api/orders/create", ca, "{\"stationId\":" + a + ",\"addressId\":" + address
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"disabled-order\",\"items\":[{\"productId\":" + product
                + ",\"quantity\":1}]}");
        assertEquals(0, created.code(), created.toString());
        long id = created.data().path("orderId").asLong();

        assertEquals(0, post("/api/delivery/orders/" + id + "/dispatch", ta, "{\"targetStationId\":" + b + "}").code());
        assertEquals(0, intOf("select count(*) from dispatch_agreement where order_id=?", id), "开关关闭时不建协议行");

        Api accepted = post("/api/delivery/orders/" + id + "/accept", tb, "{}");
        assertEquals(0, accepted.code(), accepted.toString());
        assertEquals(2, intOf("select status from orders where id=?", id));
        assertEquals(mb, longOf("select delivery_staff_id from orders where id=?", id));
        assertEquals(b, longOf("select delivery_station_id from orders where id=?", id));
        assertEquals(0, intOf("select count(*) from dispatch_agreement where order_id=?", id), "接单也不得补建协议行");
    }
}
