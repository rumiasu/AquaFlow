package com.example.aquaflow.integration;

import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品与库存重构 P0：**本站定价**（售价 / 押金 / 水票价）的落地与快照口径。
 *
 * <p>规格见 {@code docs/design/12-商品与库存重构.md}。这批用例存在的理由是：
 * 现有 31 个走 helper 造数的测试类**没有一条**断言"站级覆盖生效" —— 覆盖为空时它们照样全绿，
 * 所以"改完测试全绿"这件事本身不构成验收。</p>
 *
 * <p>口径要点：</p>
 * <ul>
 *   <li>售价/押金/水票价三级阶梯一律走 {@code PriceUtil}：站级覆盖 → 通用库参考值；</li>
 *   <li><b>覆盖判定是"非空且 &gt; 0"</b>，留空或 0 = 用参考值（0 押金会与桶账 [DEF-2] 打架）；</li>
 *   <li>下单必须把"本站押金"快照进 {@code orders.deposit_amount} 与
 *       {@code customer_barrel_in_transit.unit_price}，之后改价不影响历史；</li>
 *   <li>库存增减必须落 {@code inventory_record} 流水。</li>
 * </ul>
 */
@DisplayName("本站定价：售价/押金/水票价站级覆盖、快照、库存流水、无配置不得自动建行")
class StationPricingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BarrelLedgerService barrelLedgerService;

    private long station;
    private long customer;
    private long addr;

    private void seedBase() {
        station = createStation("定价站");
        customer = createCustomer("定价客户", "pricing-openid");
        addr = createAddress(customer, "某小区 1 号");
    }

    private Api order(String key, long productId, int qty) {
        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":1,\"idempotencyKey\":\"" + key + "\","
                + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}";
        return post("/api/orders/create", customerToken(customer), body);
    }

    private Api quote(long productId, int qty) {
        return post("/api/payments/quote", customerToken(customer),
                "{\"stationId\":" + station + ",\"paymentMethod\":1,"
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }

    @Test
    @DisplayName("站级覆盖：售价对瓶装水生效（报价=下单=明细）；押金对瓶装水**不生效**（恒 0）")
    void stationSalePriceAndDepositOverrideCatalogValues() {
        seedBase();
        // 通用库参考价：售价 22 / 押金 30；本站覆盖：售价 18 / 押金 50
        // ⚠️ 这里刻意给**瓶装水**塞了站级押金 50：2026-09-19 起"押金只对桶装水生效"，
        // 本用例因此变成**读侧防御的行为证明** —— 即使库里被塞了押金（历史数据 / 人为改库），
        // 报价与下单也必须按 0 收。站级押金"能生效"的那一侧由下面
        // stationDepositIsSnapshottedIntoInTransit 用桶装水验证（缺桶押金 + unit_price 快照）。
        long product = createProduct("瓶装水", 2, "22.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, product, 100, "18.00", "50.00", 0, "0.00");

        Api q = quote(product, 2);
        assertTrue(q.isSuccess(), "报价应成功，实际=" + q);
        assertEquals(0, new BigDecimal(q.data().path("waterAmount").asText()).compareTo(new BigDecimal("36.00")),
                "水费必须按**本站售价** 18×2=36，而不是通用库 22×2=44：" + q);
        assertEquals(0, new BigDecimal(q.data().path("barrelDeposit").asText()).compareTo(BigDecimal.ZERO),
                "瓶装水不收押金：即使站级押金配了 50，也必须是 0（押金是循环桶的押金）：" + q);

        Api res = order("station-price-k1", product, 2);
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        long orderId = res.data().path("orderId").asLong();

        assertEquals(0, decimalOf("SELECT water_amount FROM orders WHERE id=?", orderId)
                        .compareTo(new BigDecimal("36.00")),
                "订单水费必须与报价同口径（站级售价）");
        assertEquals(0, decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId)
                        .compareTo(BigDecimal.ZERO),
                "瓶装水订单押金必须为 0（与报价同口径；钱收得进就必须退得出，而非桶装没有退还路径）");
        assertEquals(0, decimalOf("SELECT price FROM order_item WHERE order_id=?", orderId)
                        .compareTo(new BigDecimal("18.00")),
                "订单明细单价快照 = 下单当时生效的站级售价");
        assertEquals(0, decimalOf("SELECT deposit FROM order_item WHERE order_id=?", orderId)
                        .compareTo(BigDecimal.ZERO),
                "非桶装明细的押金快照恒为 0（它是退押金时的兜底单价来源，留旧值会污染将来的押金条）");
    }

    @Test
    @DisplayName("桶装水：缺桶押金按本站押金收取并快照进配送中记录（退桶金额的来源）")
    void stationDepositIsSnapshottedIntoInTransit() {
        seedBase();
        long product = createProduct("桶装水19L", 1, "22.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, product, 100, null, "50.00", 0, "0.00");

        Api q = quote(product, 2);
        assertTrue(q.isSuccess(), "报价应成功，实际=" + q);
        assertEquals(0, new BigDecimal(q.data().path("extraDeposit").asText()).compareTo(new BigDecimal("100.00")),
                "缺 2 个桶应按本站押金 50×2=100，而不是通用库 30×2=60：" + q);

        Api res = order("station-deposit-k1", product, 2);
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        long orderId = res.data().path("orderId").asLong();

        assertEquals(0, decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId)
                        .compareTo(new BigDecimal("100.00")), "订单押金 = 站级押金 × 缺桶数");
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit "
                        + "WHERE related_order_id=? AND product_id=? AND status='PENDING'", orderId, product));
        assertEquals(0, decimalOf("SELECT unit_price FROM customer_barrel_in_transit "
                        + "WHERE related_order_id=? AND product_id=?", orderId, product)
                        .compareTo(new BigDecimal("50.00")),
                "配送中记录的 unit_price 必须是本站押金快照 —— 它决定建 lot 后客户能退多少钱");
    }

    @Test
    @DisplayName("没有下单快照的老数据：兜底取本站押金，不再取通用库参考押金")
    void missingSnapshotFallsBackToStationDeposit() {
        seedBase();
        long manager = createStaff("定价站长", "STATION_MANAGER", station, 1);
        // 通用库参考押金 30，本站押金 50
        long product = createProduct("桶装水19L", 1, "22.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, product, 100, null, "50.00", 0, "0.00");

        // 构造一张"配送中记录没有 unit_price 快照"的历史单（price_source 走兜底推断）
        long orderId = createOrderFull(customer, addr, station, product, 2, 0, 1,
                "22.00", "0.00", "22.00", true, 1);
        createOrderItem(orderId, product, "桶装水19L", 1, "22.00", "0.00", 1);
        insert("INSERT INTO customer_barrel_in_transit(customer_id, station_id, product_id, qty, unit_price, "
                        + "related_order_id, status) VALUES (?,?,?,?,NULL,?, 'PENDING')",
                customer, station, product, 1, orderId);

        barrelLedgerService.applyDelivery(orderId, customer, station, new HashMap<>(), manager);

        assertEquals(0, decimalOf("SELECT unit_price FROM customer_barrel_lot WHERE related_order_id=? AND product_id=?",
                        orderId, product).compareTo(new BigDecimal("50.00")),
                "无快照兜底必须用**本站押金** 50；若仍读 product.deposit(30)，客户退桶就少退 20");
    }

    @Test
    @DisplayName("站长商品列表：回读本站售价/押金，并给出生效价（前端不再自己拼）")
    void managerListExposesStationPricing() {
        seedBase();
        long manager = createStaff("定价站长", "STATION_MANAGER", station, 1);
        long product = createProduct("瓶装水", 2, "22.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, product, 100, "18.00", "50.00", 0, "0.00");

        Api list = get("/api/manager/catalog", staffToken(manager, "STATION_MANAGER", station));
        assertTrue(list.isSuccess(), "列表应成功，实际=" + list);

        boolean checked = false;
        for (var node : list.data()) {
            if (node.path("id").asLong() != product) continue;
            checked = true;
            assertEquals(0, new BigDecimal(node.path("salePrice").asText()).compareTo(new BigDecimal("18.00")),
                    "此前 `salePrice` 前后端都没有落点（填了等于没填），现在必须回读得到：" + node);
            assertEquals(0, new BigDecimal(node.path("effectivePrice").asText()).compareTo(new BigDecimal("18.00")),
                    "生效价 = 站级覆盖");
            assertEquals(0, new BigDecimal(node.path("effectiveDeposit").asText()).compareTo(new BigDecimal("50.00")),
                    "生效押金 = 站级覆盖");
            assertTrue(node.path("selected").asBoolean(), "已配置 inventory 的行 selected 应为 true");
        }
        assertTrue(checked, "列表里应能找到刚造的商品");
    }

    @Test
    @DisplayName("商品页改库存：数量生效且必须留下 ADJUST 流水（原先直接覆盖数量、流水断链）")
    void changingStockWritesAdjustRecord() {
        seedBase();
        long manager = createStaff("库存站长", "STATION_MANAGER", station, 1);
        long product = createProduct("瓶装水", 2, "22.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, product, 10, "18.00", null, 0, "0.00");
        String token = staffToken(manager, "STATION_MANAGER", station);

        Api put = post("/api/manager/catalog/" + product + "/stock", token, "{\"target\":7,\"note\":\"盘点\"}");
        assertTrue(put.isSuccess(), "改库存应成功，实际=" + put);

        assertEquals(7, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?", station, product),
                "库存应变为 7");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=? AND product_id=? "
                        + "AND type='ADJUST' AND delta=-3", station, product),
                "库存变更必须留 ADJUST 流水 delta=-3（否则库存与流水永远对不上）");
        assertEquals(0, decimalOf("SELECT sale_price FROM inventory WHERE station_id=? AND product_id=?",
                        station, product).compareTo(new BigDecimal("18.00")),
                "只改库存不得把本站售价冲掉（整行覆盖的历史事故）");
    }

    @Test
    @DisplayName("水票购买：按站级水票价计费（旧实现少了一级、与用票结算可能两个价）")
    void ticketPurchaseUsesStationTicketPrice() {
        seedBase();
        // 通用库：售价 22 / product.ticket_price 0；本站水票价 20
        long product = createProduct("桶装水19L", 1, "22.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, product, 100, null, null, 1, "20.00");

        Api res = post("/api/tickets/purchase", customerToken(customer),
                "{\"productId\":" + product + ",\"quantity\":2,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"idempotencyKey\":\"ticket-station-pricing-1\"}");
        assertTrue(res.isSuccess(), "购买水票应成功，实际=" + res);
        assertEquals(0, new BigDecimal(res.data().path("amount").asText()).compareTo(new BigDecimal("40.00")),
                "2 张水票应按站级水票价 20×2=40 计费，而不是零售价 22×2=44：" + res);
    }

    @Test
    @DisplayName("护栏：本站没配置过的商品不能下单，且不得被下单流程自动建行/自动上架")
    void orderWithoutStationInventoryIsRejectedWithoutAutoCreate() {
        seedBase();
        long product = createProduct("没上架的桶装水", 1, "22.00", "30.00", 0, "0.00");

        Api res = order("no-inventory-k1", product, 1);
        assertTrue(res.code() != 0, "本站没有该商品的上架配置时必须拒绝下单，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=?", station, product),
                "下单流程绝不允许自动补建 inventory 行（原实现会建并 enabled=1 —— 等于客户一单就替站长上架）");
    }

    /* ==================== P3：顾客侧下发的价格必须与结算同源 ==================== */

    @Test
    @DisplayName("顾客列表：sale-by-station 下发本站有效价，且与结算价一致（列表价 = 结算价）")
    void saleByStationExposesEffectivePriceMatchingQuote() {
        seedBase();
        long product = createProduct("桶装水19L", 1, "22.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, product, 100, "18.00", "50.00", 1, "20.00");

        Api list = get("/api/products/sale-by-station?stationId=" + station, customerToken(customer));
        assertTrue(list.isSuccess(), "在售列表应成功，实际=" + list);
        assertEquals(1, list.data().size());

        var node = list.data().get(0);
        assertEquals(0, new BigDecimal(node.path("effectivePrice").asText()).compareTo(new BigDecimal("18.00")),
                "列表必须下发本站售价（不是通用库 22.00）：" + node);
        assertEquals(0, new BigDecimal(node.path("effectiveDeposit").asText()).compareTo(new BigDecimal("50.00")),
                "列表必须下发本站押金：" + node);
        assertEquals(0, new BigDecimal(node.path("effectiveTicketPrice").asText()).compareTo(new BigDecimal("20.00")),
                "水票面值必须是本站水票价：" + node);
        assertTrue(node.path("inStock").asBoolean(), "有库存时 inStock=true");
        assertEquals("桶装水", node.path("categoryText").asText(), "分类文案由后端下发");

        // 与结算价对照：报价的水费 = 列表价 × 数量
        Api q = quote(product, 2);
        assertTrue(q.isSuccess(), "报价应成功，实际=" + q);
        assertEquals(0, new BigDecimal(q.data().path("waterAmount").asText())
                        .compareTo(new BigDecimal(node.path("effectivePrice").asText())
                                .multiply(new BigDecimal("2"))),
                "列表价 × 数量 必须等于报价水费（否则又是计价双轨）：list=" + node + " quote=" + q);
    }

    @Test
    @DisplayName("顾客详情：带 stationId 时同样下发本站有效价")
    void productDetailWithStationReturnsEffectivePrice() {
        seedBase();
        long product = createProduct("桶装水19L", 1, "22.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, product, 100, "18.00", "50.00", 0, "0.00");

        Api detail = get("/api/products/" + product + "?stationId=" + station, customerToken(customer));
        assertTrue(detail.isSuccess(), "详情应成功，实际=" + detail);
        assertEquals(0, new BigDecimal(detail.data().path("effectivePrice").asText())
                .compareTo(new BigDecimal("18.00")), "详情也必须是本站售价：" + detail);
        assertEquals(0, new BigDecimal(detail.data().path("effectiveDeposit").asText())
                .compareTo(new BigDecimal("50.00")), "详情也必须是本站押金：" + detail);
    }

    @Test
    @DisplayName("优先展示：顾客列表里排在最前（重构前该字段对顾客完全无效）")
    void priorityDisplayOrdersCustomerList() {
        seedBase();
        long normal1 = createProduct("普通水A", 1, "20.00", "30.00", 0, "0.00");
        long starred = createProduct("优先水", 1, "21.00", "30.00", 0, "0.00");
        long normal2 = createProduct("普通水B", 1, "19.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(station, normal1, 100, null, null, 0, "0.00");
        createInventoryWithStationPricing(station, starred, 100, null, null, 0, "0.00");
        createInventoryWithStationPricing(station, normal2, 100, null, null, 0, "0.00");
        jdbc.update("UPDATE inventory SET priority_display = 1 WHERE station_id = ? AND product_id = ?",
                station, starred);

        Api list = get("/api/products/sale-by-station?stationId=" + station, customerToken(customer));
        assertTrue(list.isSuccess(), "在售列表应成功，实际=" + list);
        assertEquals(3, list.data().size());
        assertEquals(starred, list.data().get(0).path("id").asLong(),
                "设了优先展示的商品必须排在顾客列表第一位：" + list);
    }

    @Test
    @DisplayName("概览押金合计用批次快照：站长调价不改变客户已付过的钱")
    void barrelSummaryDepositUsesBatchSnapshot() {
        seedBase();
        long product = createProduct("桶装水19L", 1, "22.00", "30.00", 0, "0.00");
        // 当前本站押金已调到 50，但客户当初是按 30 买的
        createInventoryWithStationPricing(station, product, 100, null, "50.00", 0, "0.00");

        // 已到手权益 2 个（买入价 30 → right_amount=60）+ 配送中 1 个（下单快照 30）
        createBarrelAsset(customer, station, product, 2, "60.00");
        createBarrelInTransit(customer, station, product, 1, "30.00", null, "PENDING");

        Api summary = get("/api/barrels/summary?stationId=" + station, customerToken(customer));
        assertTrue(summary.isSuccess(), "桶概览应成功，实际=" + summary);
        BigDecimal deposit = new BigDecimal(summary.data().path("depositTotal").asText());
        assertEquals(0, deposit.compareTo(new BigDecimal("90.00")),
                "押金合计必须是快照口径 60+30=90；若按当前押金 50 算会得到 150（那是把客户已付的钱改了）：" + summary);
    }
}
