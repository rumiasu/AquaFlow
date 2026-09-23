package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下单金额重算与幂等回归。
 *
 * <p>核心不变量：金额 100% 由服务端推导（客户端传什么都无关紧要）；
 * 同一个幂等键重复下单只产生一张订单，库存与桶权益只扣一次。</p>
 *
 * <p>金额口径：水费取<b>站级水票价</b>（inventory.ticket_price=18），不是商品零售价（20）——
 * 这两者一混，水票订单就会多收钱；押金是<b>缺桶数 × 押金单价</b>，
 * 客户手上已有权益的部分不该再收一次押金。</p>
 */
@DisplayName("Phase B · 下单金额重算与幂等")
class OrderCreationIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
    }

    private Api createOrderApi(String idemKey, int qty) {
        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":3,\"idempotencyKey\":\"" + idemKey + "\","
                + "\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}";
        return post("/api/orders/create", customerToken(customer), body);
    }

    @Test
    @DisplayName("订单金额全部由服务端重算（水票价取站级配置，押金按缺桶数）")
    void orderAmountIsServerDerived() {
        seed();

        Api res = createOrderApi("idem-amt", 2);
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        long orderId = res.data().path("orderId").asLong();

        BigDecimal water = decimalOf("SELECT water_amount FROM orders WHERE id=?", orderId);
        assertEquals(0, water.compareTo(new BigDecimal("36.00")),
                "水费应为站级水票价 18.00×2=36.00（取零售价 20 即为计价错位），实际=" + water);

        BigDecimal deposit = decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId);
        assertEquals(0, deposit.compareTo(new BigDecimal("60.00")), "押金应按缺桶数重算，实际=" + deposit);

        BigDecimal total = decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId);
        assertEquals(0, total.compareTo(new BigDecimal("96.00")), "合计应为 36+60=96.00，实际=" + total);

        assertEquals(8, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product));
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit WHERE related_order_id=?", orderId));
        assertEquals(2, intOf("SELECT qty FROM customer_barrel_in_transit WHERE related_order_id=?", orderId));
        assertEquals(0, decimalOf("SELECT unit_price FROM customer_barrel_in_transit WHERE related_order_id=?", orderId)
                        .compareTo(new BigDecimal("30.00")),
                "配送中桶应快照下单当时押金单价");
    }

    @Test
    @DisplayName("同一幂等键重复下单：只建一单、只扣一次库存、不重复建配送中桶")
    void duplicateIdempotencyKeyIsIgnored() {
        seed();

        Api first = createOrderApi("idem-dup", 2);
        Api second = createOrderApi("idem-dup", 2);

        assertTrue(first.isSuccess(), "首次下单应成功，实际=" + first);
        assertTrue(second.isSuccess(), "重复下单应返回既有订单而非报错，实际=" + second);

        long firstId = first.data().path("orderId").asLong();
        long secondId = second.data().path("orderId").asLong();
        assertEquals(firstId, secondId, "幂等键命中应返回同一订单");
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders"), "只应存在一张订单");
        assertEquals(8, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                        station, product),
                "库存只应扣减一次（10-2=8）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit"), "配送中桶只应建一次");
    }
}
