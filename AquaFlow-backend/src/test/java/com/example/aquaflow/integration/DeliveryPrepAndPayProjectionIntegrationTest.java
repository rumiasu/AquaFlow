package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送端「出发前备货信息 + 客户自助支付投影」（契约工作包 C4 / A3）的读侧验收。
 *
 * <p>两件事各自对应一条真实形状：</p>
 * <ul>
 *   <li><b>C4 备货信息</b>：配送端原来**完全没有**"已备齐 / 还缺哪些"，而 {@code available_qty}
 *       下发的是**实物量**（已下单未出库的那部分被显示成还有货）。现在订单详情里带 {@code stockPrep}，
 *       口径 = 凭据上的需求快照 − 已预留；{@code available_qty} 也改成"实物 − 活跃预留"。</li>
 *   <li><b>A3 自助支付投影</b>：{@code canRepay} 原来**恒为 false**（实体不知道这个部署开了哪些渠道）
 *       ⇒ 模拟渠道开着时未付水票/微信单也没有入口；若改成恒 true，现金单又会拿到一个点了没用的
 *       按钮（"假支付"）。现在由 {@code PaymentService.canSelfPay} 按渠道能力投影。</li>
 * </ul>
 */
@DisplayName("配送端读侧 · 备货信息与自助支付投影（C4/A3）")
class DeliveryPrepAndPayProjectionIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long address;
    private long manager;

    /** 非桶装商品：把变量集中在库存/备货本身，不牵扯押金与桶权益。 */
    private void seed(int stock) {
        station = createStation("备货站A");
        product = createProduct("备货瓶装水", 2, "20.00", "0.00", 0, "0.00");
        createInventory(station, product, stock);
        createInventoryRecord(station, product, stock, "INIT", 0);
        customer = createCustomer("备货客户", "prep-openid-" + System.nanoTime());
        address = createAddress(customer, "备货小区1号");
        manager = createStaff("备货站长", "STATION_MANAGER", station, 1);
    }

    private long inTransitOrder(int quantity, int reservedQty) {
        long orderId = createOrderFull(customer, address, station, product, 1, 1, 2,
                "40.00", "0.00", "40.00", false, 0);
        long itemId = createOrderItemFull(orderId, product, "备货瓶装水", quantity, reservedQty,
                "20.00", "0.00");
        // createReservation(订单, 明细, 商品, 站, 预留量, 状态)；status=1 才是活跃凭据
        createReservation(orderId, itemId, product, station, reservedQty, 1);
        return orderId;
    }

    @Test
    @DisplayName("部分预留 ⇒ 详情里能看出还缺多少（按商品），补货后变已备齐")
    void stockPrepShowsShortageThenReadyAfterInbound() {
        seed(1);                                   // 只有 1 桶，客户要 3 桶 ⇒ 缺 2
        long orderId = inTransitOrder(3, 1);

        Api before = get("/api/delivery/orders/" + orderId, staffToken(manager, "STATION_MANAGER", station));
        assertEquals(0, before.code(), "站长应能读本单详情: " + before);
        JsonNode prep = before.data().path("stockPrep");
        assertFalse(prep.isMissingNode(), "详情里必须带 stockPrep: " + before.data());
        assertEquals(false, prep.path("ready").asBoolean(), "部分预留不该说已备齐: " + prep);
        assertEquals(2, prep.path("shortageTotal").asInt(), "还缺 2 桶: " + prep);
        assertEquals(1, prep.path("items").size(), "应列出这件商品: " + prep);
        JsonNode first = prep.path("items").get(0);
        assertEquals("备货瓶装水", first.path("productName").asText());
        assertEquals(3, first.path("needQty").asInt());
        assertEquals(1, first.path("reservedQty").asInt());
        assertEquals(2, first.path("shortage").asInt());

        // 入库补位（站长）→ 凭据被补满 → 备货信息变"已备齐"
        // 请求体形状：{items:[{productId, quantity}]}，站别走 query 参数（与其它用例同款）
        assertEquals(0, post("/api/inventory/inbound?stationId=" + station,
                staffToken(manager, "STATION_MANAGER", station),
                "{\"items\":[{\"productId\":" + product + ",\"quantity\":2}]}").code(), "入库");
        Api after = get("/api/delivery/orders/" + orderId, staffToken(manager, "STATION_MANAGER", station));
        JsonNode prep2 = after.data().path("stockPrep");
        assertTrue(prep2.path("ready").asBoolean(), "补货后应已备齐: " + prep2);
        assertEquals(0, prep2.path("shortageTotal").asInt());
    }

    @Test
    @DisplayName("商品读取下发的是**可用量**（实物 − 活跃预留），不是实物量")
    void productListSendsAvailableQtyNotPhysical() {
        seed(5);
        inTransitOrder(4, 4);                      // 预留 4 ⇒ 可用只剩 1

        Api list = get("/api/products/sale-by-station?stationId=" + station, customerToken(customer));
        assertEquals(0, list.code(), "顾客侧商品列表: " + list);
        JsonNode row = null;
        for (JsonNode node : list.data()) {
            if (String.valueOf(product).equals(node.path("id").asText())) {
                row = node;
            }
        }
        assertNotNull(row, "列表里应有这个商品: " + list.data());
        assertEquals(1, row.path("availableQty").asInt(),
                "实物 5 − 活跃预留 4 = 可用 1（改之前会下发 5）: " + row);
    }

    @Test
    @DisplayName("零预留 ⇒ 整单都在等货；跨站单按**凭据所在站**算，不看归属站库存")
    void zeroReservedAndCrossStationPrepFollowCredentials() {
        seed(0);                                   // 站 A 一件都没有
        long orderId = inTransitOrder(2, 0);       // 客户要 2，预留 0
        Api zero = get("/api/delivery/orders/" + orderId, staffToken(manager, "STATION_MANAGER", station));
        JsonNode prep = zero.data().path("stockPrep");
        assertEquals(false, prep.path("ready").asBoolean(), "零预留不该说已备齐: " + prep);
        assertEquals(2, prep.path("shortageTotal").asInt(), "整单 2 桶都在等货: " + prep);
        assertEquals(0, prep.path("items").get(0).path("reservedQty").asInt());

        // 跨站单：归属站 A、履约站 B；凭据挂在 B（货记在 B）⇒ 备货口径只看凭据所在站
        long stationB = createStation("备货站B");
        createInventory(stationB, product, 5);
        long crossOrder = createOrderCrossStation(customer, address, station, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00");
        long crossItem = createOrderItemFull(crossOrder, product, "备货瓶装水", 2, 2, "20.00", "0.00");
        createReservation(crossOrder, crossItem, product, stationB, 2, 1);
        long managerB = createStaff("备货站长B", "STATION_MANAGER", stationB, 1);
        Api cross = get("/api/delivery/orders/" + crossOrder, staffToken(managerB, "STATION_MANAGER", stationB));
        assertEquals(0, cross.code(), "履约站站长应能读本单: " + cross);
        JsonNode crossPrep = cross.data().path("stockPrep");
        assertTrue(crossPrep.path("ready").asBoolean(), "B 站有货、凭据也在 B ⇒ 已备齐: " + crossPrep);
        assertEquals(0, crossPrep.path("shortageTotal").asInt());

        // 归属站 A 的站长看这单应被拒（跨站不看：他只管归属，不管履约）
        Api byOwner = get("/api/delivery/orders/" + crossOrder, staffToken(manager, "STATION_MANAGER", station));
        assertFalse(byOwner.isSuccess(), "归属站不是履约站，不该读到配送详情: " + byOwner);
    }

    @Test
    @DisplayName("自助支付投影：现金单不给在线入口；水票未付可重试原单；已取消单没有入口")
    void canSelfPayFollowsChannelCapability() {
        seed(10);
        // ① 现金单（货到付款、待收款）⇒ 客户不该看到"去支付"（点了只会多一条待收款流水）
        long cashOrder = createOrderFull(customer, address, station, product, 1, 1, 2,
                "20.00", "0.00", "20.00", false, 0);
        createOrderItemFull(cashOrder, product, "备货瓶装水", 1, 0, "20.00", "0.00");
        Api cash = get("/api/orders/" + cashOrder, customerToken(customer));
        assertEquals(0, cash.code(), cash.toString());
        assertEquals(false, cash.data().path("canRepay").asBoolean(true), "现金单不应有自助支付入口: " + cash.data());

        // ② 水票单还没扣票（下单即付那次请求没成功）⇒ 允许对**同一张单**重试扣票
        long ticketOrder = createOrderFull(customer, address, station, product, 0, 0, 3,
                "20.00", "0.00", "20.00", false, 0);
        createOrderItemFull(ticketOrder, product, "备货瓶装水", 1, 0, "20.00", "0.00");
        Api ticket = get("/api/orders/" + ticketOrder, customerToken(customer));
        assertEquals(0, ticket.code(), ticket.toString());
        assertTrue(ticket.data().path("canRepay").asBoolean(false),
                "水票未付时应允许重试原单支付（不重建单）: " + ticket.data());
        assertFalse(ticket.data().path("payHint").asText("").isEmpty(), "付款说明必须由后端下发");

        // ③ 已取消单 ⇒ 没有支付入口，且说明要说清"已取消"
        assertEquals(0, put("/api/orders/" + cashOrder + "/customer-cancel", customerToken(customer), null).code(),
                "客户取消待配送单");
        Api cancelled = get("/api/orders/" + cashOrder, customerToken(customer));
        assertEquals(false, cancelled.data().path("canRepay").asBoolean(true), "已取消单不该有支付入口");
        assertTrue(cancelled.data().path("payHint").asText("").contains("取消"),
                "付款说明要说清已取消: " + cancelled.data().path("payHint").asText(""));
    }
}
