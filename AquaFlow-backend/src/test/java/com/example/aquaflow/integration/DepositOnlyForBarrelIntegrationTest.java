package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 押金只跟桶装水走（2026-09-19）：**非桶装商品一律不收押金，站长也填不进去**。
 *
 * <p>正本是两处列注释都写着"仅桶装水使用"：{@code product.deposit} 与 {@code inventory.deposit_price}。
 * 但执行层历史写法是<b>反选</b> {@code category != 1}，于是报价与下单都会对瓶装水/饮水器按件收押金，
 * 而押金唯一的出口是按桶型押金条核销退桶 —— 非桶装不建押金条，钱进了押金账户却没有退还路径。</p>
 *
 * <p>本类锁三件事：① 读侧（报价 / 下单）非桶装押金恒为 0；② 写侧（自定义商品、站级选品设置）
 * 非桶装填押金被**明确拒绝**而不是静默忽略；③ 桶装水那一侧行为不变
 * （"桶装水必须有押金"的既有护栏不能被这次收口弄丢）。</p>
 */
@DisplayName("押金边界：只有桶装水能设/收押金，非桶装读侧为 0、写侧被拒")
class DepositOnlyForBarrelIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long manager;
    private long customer;
    private long address;
    private String mgrToken;
    private String cusToken;

    private void seed() {
        station = createStation("押金边界站");
        manager = createStaff("边界站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("边界客户", "deposit-scope-openid");
        address = createAddress(customer, "边界小区1号");
        createCustomerStationConfig(customer, station, 1);
        mgrToken = staffToken(manager, "STATION_MANAGER", station);
        cusToken = customerToken(customer);
    }

    private BigDecimal quoteDeposit(long productId, int qty) {
        Api res = post("/api/payments/quote", cusToken, "{\"stationId\":" + station + ",\"paymentMethod\":2,"
                + "\"addressId\":" + address + ",\"items\":[{\"productId\":" + productId
                + ",\"quantity\":" + qty + "}]}");
        assertEquals(0, res.code(), "报价应成功: " + res);
        return new BigDecimal(res.data().path("totalAmount").asText("0"));
    }

    /* ==================== ① 读侧：非桶装不收押金 ==================== */

    @Test
    @DisplayName("非桶装商品押金填了也不收：报价合计 = 水费，订单押金 = 0")
    void nonBarrelDepositIsNeverCharged() {
        seed();
        // 故意把押金填成 30（模拟"历史数据 / 人为改库 / 通用库维护"把非桶装押金填了值）
        long disposable = createProduct("一次性桶 15L", 2, "22.00", "30.00", 1, "22.00");
        createInventoryFull(station, disposable, 50, 1, "22.00");

        // 2 × 22 = 44；若旧口径把 30/件的押金算进去会变成 104
        assertEquals(0, quoteDeposit(disposable, 2).compareTo(new BigDecimal("44.00")),
                "非桶装押金必须不计入报价合计（旧口径会算成 44+60=104）");

        Api created = post("/api/orders/create", cusToken, "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"nb-dep-1\","
                + "\"items\":[{\"productId\":" + disposable + ",\"quantity\":2}]}");
        assertTrue(created.isSuccess(), "下单应成功: " + created);
        long order = created.data().path("orderId").asLong();

        assertEquals(0, decimalOf("SELECT deposit_amount FROM orders WHERE id = ?", order)
                        .compareTo(BigDecimal.ZERO),
                "非桶装订单的押金金额必须是 0（收了就没有退还路径）");
        // 明细快照同样为 0：它是"退押金时回退单价"的兜底来源，留着旧值会污染将来的押金条
        assertEquals(0, decimalOf("SELECT IFNULL(SUM(deposit), 0) FROM order_item WHERE order_id = ?", order)
                        .compareTo(BigDecimal.ZERO),
                "非桶装明细的押金快照必须是 0");
    }

    @Test
    @DisplayName("站级押金覆盖（inventory.deposit_price）对非桶装同样无效")
    void stationLevelDepositOverrideDoesNotApplyToNonBarrel() {
        seed();
        long disposable = createProduct("一次性桶 12L", 2, "18.00", "0.00", 1, "18.00");
        createInventoryFull(station, disposable, 50, 1, "18.00");
        // 直接改库模拟"历史遗留的站级押金"：这是读侧必须兜住的场景（写侧已有校验，见下一条用例）
        jdbc.update("UPDATE inventory SET deposit_price = 50.00 WHERE station_id = ? AND product_id = ?",
                station, disposable);

        assertEquals(0, quoteDeposit(disposable, 1).compareTo(new BigDecimal("18.00")),
                "站级押金对非桶装也必须无效（18 而不是 68）");
    }

    /* ==================== ② 写侧：填进去就报错，不静默忽略 ==================== */

    @Test
    @DisplayName("自定义商品：非桶装填押金被拒；桶装水不填押金也被拒（两条护栏都在）")
    void customProductDepositRules() {
        seed();
        String base = "{\"name\":\"%s\",\"category\":%d,\"price\":20.00,\"deposit\":%s}";

        Api nonBarrel = post("/api/manager/my-products", mgrToken,
                String.format(base, "一次性桶 5L", 2, "30.00"));
        assertNotEquals(0, nonBarrel.code(), "非桶装填押金必须被拒，实际=" + nonBarrel);
        assertTrue(nonBarrel.message() != null && nonBarrel.message().contains("只有桶装水"),
                "拒绝文案要说清原因，实际=" + nonBarrel.message());

        Api barrelNoDeposit = post("/api/manager/my-products", mgrToken,
                String.format(base, "本站循环桶", 1, "0.00"));
        assertNotEquals(0, barrelNoDeposit.code(), "桶装水不给押金仍必须被拒（既有护栏不能丢）");

        Api barrelOk = post("/api/manager/my-products", mgrToken,
                String.format(base, "本站循环桶", 1, "50.00"));
        assertEquals(0, barrelOk.code(), "桶装水带押金应当能创建，实际=" + barrelOk);
        assertEquals(0, intOf("SELECT COUNT(*) FROM product WHERE owner_station_id = ? AND category <> 1", station),
                "被拒的非桶装商品不得留痕");
    }

    @Test
    @DisplayName("站级选品设置：非桶装填押金被拒（同一句话），桶装水不受影响")
    void catalogSettingDepositRules() {
        seed();
        long disposable = createProduct("通用库一次性桶 15L", 2, "22.00", "0.00", 1, "22.00");
        long barrel = createProduct("通用库循环桶 18.9L", 1, "12.00", "50.00", 1, "12.00");
        createInventoryFull(station, disposable, 50, 1, "22.00");
        createInventoryFull(station, barrel, 50, 1, "12.00");

        // 端点正本：PUT /api/manager/catalog/{productId}（不是 /setting —— 别按名字猜路由）
        Api rejected = put("/api/manager/catalog/" + disposable, mgrToken,
                "{\"enabled\":1,\"salePrice\":22.00,\"depositPrice\":30.00}");
        assertNotEquals(0, rejected.code(), "非桶装设站级押金必须被拒，实际=" + rejected);
        assertTrue(rejected.message() != null && rejected.message().contains("只有桶装水"),
                "拒绝文案要说清原因，实际=" + rejected.message());

        Api ok = put("/api/manager/catalog/" + barrel, mgrToken,
                "{\"enabled\":1,\"salePrice\":13.00,\"depositPrice\":60.00}");
        assertEquals(0, ok.code(), "桶装水设站级押金应当成功，实际=" + ok);
    }
}
