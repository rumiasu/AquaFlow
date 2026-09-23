package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水票档位套餐 + 批次单价快照（v36，Phase 1 · P1-D）。
 *
 * <p>本用例盯的是**账目数值**，不是接口返回是否成功。理由：这一版动的是水票（预付资产）的
 * 计价与核销口径，接口返回 0 完全可能伴随着一笔算错的钱。</p>
 *
 * <p>两条不变量（{@code docs/design/19}）：</p>
 * <ol>
 *   <li>{@code ticket_account.remain_quantity == Σ ticket_lot.remain_qty}（status=1）</li>
 *   <li>{@code ticket_account.right_amount == Σ remain_qty × unit_price}（status=1）</li>
 * </ol>
 * <p>这两条就是对账 E8 的内容，所以每个用例末尾都用 {@link #assertTicketBookConsistent} 复核一遍。</p>
 */
class TicketPackageAndLotIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("按档位购票：批次单价快照取**实付均价**，而不是站级单张价")
    void packagePurchaseSnapshotsPaidUnitPrice() {
        long station = createStation("档位站");
        long manager = createStaff("档位站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("档位客户", "pkg-openid");
        // 散买单张价 9.00；档位 100 张 800.00 → 均价 8.00
        long product = createProduct("档位水", 1, "20.00", "30.00", 1, "9.00");
        createInventoryFull(station, product, 100, 1, "9.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        Api pkg = post("/api/ticket-packages", mgr,
                "{\"productId\":" + product + ",\"qty\":100,\"price\":800.00,\"title\":\"100 张超值装\"}");
        assertEquals(0, pkg.code(), "站长建档位: " + pkg);
        long packageId = pkg.data().path("id").asLong();
        assertEquals(0, new BigDecimal("8.00").compareTo(
                        decimalOf("SELECT unit_price FROM ticket_package WHERE id=?", packageId)),
                "均价由服务端算（800/100），不接受客户端传入");

        Api purchase = post("/api/tickets/purchase", cus,
                "{\"productId\":" + product + ",\"quantity\":100,\"paymentMethod\":2,\"stationId\":" + station
                        + ",\"packageId\":" + packageId + ",\"idempotencyKey\":\"pkg-buy-1\"}");
        assertEquals(0, purchase.code(), "按档位购票: " + purchase);
        long paymentId = purchase.data().path("paymentId").asLong();
        assertEquals(0, new BigDecimal("800.00").compareTo(
                        decimalOf("SELECT amount FROM payment_record WHERE id=?", paymentId)),
                "总价必须取服务端档位价，不是 100 × 单张价 900");
        assertEquals(packageId, longOf("SELECT ticket_package_id FROM payment_record WHERE id=?", paymentId),
                "流水必须记下是哪个档位 —— 档位价会变，历史流水要能自证");

        // 未确认支付前不得入账
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_account WHERE customer_id=?", customer),
                "未确认收款前不该有水票账户余额");

        assertEquals(0, put("/api/payments/" + paymentId + "/confirm", mgr, null).code(), "站长确认收款");

        assertEquals(100, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? "
                + "AND product_id=?", customer, station, product), "确认后入账 100 张");
        // ⚠️ 核心断言：批次单价必须是实付均价 8.00，不是站级单张价 9.00。
        // 用 9.00 记的后果很实际：客户退票时按 9.00 退，水站每张多退 1 元。
        assertEquals(0, new BigDecimal("8.00").compareTo(
                        decimalOf("SELECT unit_price FROM ticket_lot WHERE customer_id=? AND station_id=? AND product_id=?",
                                customer, station, product)),
                "批次单价快照必须是**实付均价**（800/100=8.00），而不是站级单张价 9.00");
        assertEquals(100, intOf("SELECT qty FROM ticket_lot WHERE customer_id=?", customer));
        assertEquals(0, new BigDecimal("800.00").compareTo(
                        decimalOf("SELECT right_amount FROM ticket_account WHERE customer_id=?", customer)),
                "账户的金额价值 = 100 × 8.00");
        assertTicketBookConsistent(customer, station, product);
    }

    @Test
    @DisplayName("档位护栏：张数必须与档位一致、档位不能跨站使用")
    void packageGuards() {
        long station = createStation("档位A站");
        long otherStation = createStation("档位B站");
        long manager = createStaff("档位站长A", "STATION_MANAGER", station, 1);
        long otherManager = createStaff("档位站长B", "STATION_MANAGER", otherStation, 1);
        long customer = createCustomer("档位护栏客户", "pkgguard-openid");
        long product = createProduct("档位护栏水", 1, "20.00", "30.00", 1, "9.00");
        createInventoryFull(station, product, 100, 1, "9.00");
        createInventoryFull(otherStation, product, 100, 1, "9.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        long packageId = post("/api/ticket-packages", mgr,
                "{\"productId\":" + product + ",\"qty\":10,\"price\":85.00}")
                .data().path("id").asLong();

        // 张数与档位不一致 → 拒绝（只信服务端档位，不信客户端传的"套餐"）
        assertNotEquals(0, post("/api/tickets/purchase", cus,
                "{\"productId\":" + product + ",\"quantity\":5,\"paymentMethod\":2,\"stationId\":" + station
                        + ",\"packageId\":" + packageId + ",\"idempotencyKey\":\"pkg-qty-mismatch\"}").code(),
                "张数与档位不一致必须被拒");

        // 档位是站级的：A 站的档位拿到 B 站用必须被拒（否则 A 站的便宜票在 B 站有价差）
        assertNotEquals(0, post("/api/tickets/purchase", cus,
                "{\"productId\":" + product + ",\"quantity\":10,\"paymentMethod\":2,"
                        + "\"stationId\":" + otherStation + ",\"packageId\":" + packageId
                        + ",\"idempotencyKey\":\"pkg-cross-station\"}").code(),
                "档位不得跨站使用");

        // 不存在的档位
        assertNotEquals(0, post("/api/tickets/purchase", cus,
                "{\"productId\":" + product + ",\"quantity\":10,\"paymentMethod\":2,\"stationId\":" + station
                        + ",\"packageId\":99999999,\"idempotencyKey\":\"pkg-missing\"}").code(),
                "不存在的档位必须被拒");

        // 站点取自登录态：B 站站长看不到 A 站的档位
        assertEquals(0, get("/api/ticket-packages/manage?productId=" + product,
                        staffToken(otherManager, "STATION_MANAGER", otherStation)).data().size(),
                "档位必须按站隔离（B 站站长看不到 A 站的档位）");
        // 客户端只看到上架的
        assertEquals(1, get("/api/ticket-packages?stationId=" + station + "&productId=" + product, cus)
                .data().size(), "客户端应看到 1 个在售档位");

        // 非法输入
        assertNotEquals(0, post("/api/ticket-packages", mgr,
                "{\"productId\":" + product + ",\"qty\":10,\"price\":0}").code(), "总价必须大于 0");
        assertNotEquals(0, post("/api/ticket-packages", mgr,
                "{\"productId\":" + product + ",\"qty\":0,\"price\":10}").code(), "张数必须大于 0");
    }

    @Test
    @DisplayName("站长加票与扣票都过批次账：余额与金额价值同步，E8 恒成立")
    void addAndConsumeKeepLotBookConsistent() {
        long station = createStation("批次站");
        long manager = createStaff("批次站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("批次客户", "lot-openid");
        long product = createProduct("批次水", 1, "20.00", "30.00", 1, "7.00");
        createInventoryFull(station, product, 100, 1, "7.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 站长加票 20 张：单价取本站水票价 7.00 并标记为推断（没有真实付款）
        assertEquals(0, post("/api/tickets/add", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":20}").code());
        assertEquals(20, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer));
        assertEquals(0, new BigDecimal("7.00").compareTo(
                        decimalOf("SELECT unit_price FROM ticket_lot WHERE customer_id=?", customer)),
                "人工加票的推理单价 = 本站水票价");
        assertEquals(1, intOf("SELECT is_migrated FROM ticket_lot WHERE customer_id=?", customer),
                "没有真实付款 → 单价标记为推断值（退票需二次确认）");
        assertTicketBookConsistent(customer, station, product);

        // 扣 8 张 → 批次剩余 12、金额 84
        assertEquals(0, post("/api/tickets/consume", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":8}").code());
        assertEquals(12, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer));
        assertEquals(12, intOf("SELECT remain_qty FROM ticket_lot WHERE customer_id=?", customer));
        assertEquals(0, new BigDecimal("84.00").compareTo(
                        decimalOf("SELECT right_amount FROM ticket_account WHERE customer_id=?", customer)),
                "扣 8 张后金额价值 = 12 × 7.00");
        assertTicketBookConsistent(customer, station, product);

        // 扣光 → 批次状态置为「已退完」
        assertEquals(0, post("/api/tickets/consume", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":12}").code());
        assertEquals(0, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=?", customer));
        assertEquals(2, intOf("SELECT status FROM ticket_lot WHERE customer_id=?", customer), "批次应置为已退完");
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        decimalOf("SELECT right_amount FROM ticket_account WHERE customer_id=?", customer)));
        assertTicketBookConsistent(customer, station, product);

        // 余额不足必须被拒，且不得动批次
        assertNotEquals(0, post("/api/tickets/consume", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":1}").code(),
                "余额不足必须被拒");
        assertTicketBookConsistent(customer, station, product);
    }

    @Test
    @DisplayName("FIFO 核销：先买的批次先扣，扣完才动后一批")
    void consumeIsFifoAcrossLots() {
        long station = createStation("FIFO站");
        long manager = createStaff("FIFO站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("FIFO客户", "fifo-openid");
        long product = createProduct("FIFO水", 1, "20.00", "30.00", 1, "5.00");
        createInventoryFull(station, product, 100, 1, "5.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 两批：先 10 张（价 5.00），再 10 张（价 6.00 —— 站长中途改了水票价）
        assertEquals(0, post("/api/tickets/add", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":10}").code());
        // 直接改库来造第二批的不同单价：这里要验的是批次单价快照的 FIFO 核销，
        // 不是"站长改价"这个接口本身（那由 StationPricingIntegrationTest 覆盖）。
        jdbc.update("UPDATE inventory SET ticket_price = ? WHERE station_id = ? AND product_id = ?",
                new BigDecimal("6.00"), station, product);
        assertEquals(0, post("/api/tickets/add", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":10}").code());

        assertEquals(2, intOf("SELECT COUNT(*) FROM ticket_lot WHERE customer_id=?", customer), "应有 2 个批次");
        assertEquals(0, new BigDecimal("110.00").compareTo(
                        decimalOf("SELECT right_amount FROM ticket_account WHERE customer_id=?", customer)),
                "金额 = 10×5 + 10×6 = 110");

        // 扣 10 张：应只动第一个批次（FIFO）
        assertEquals(0, post("/api/tickets/consume", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + product + ",\"quantity\":10}").code());
        assertEquals(0, intOf("SELECT remain_qty FROM ticket_lot WHERE customer_id=? ORDER BY id ASC LIMIT 1",
                customer), "先买的批次应先被扣光");
        assertEquals(10, intOf("SELECT remain_qty FROM ticket_lot WHERE customer_id=? ORDER BY id DESC LIMIT 1",
                customer), "后一批不应被动");
        assertEquals(0, new BigDecimal("60.00").compareTo(
                        decimalOf("SELECT right_amount FROM ticket_account WHERE customer_id=?", customer)),
                "剩余价值 = 10 × 6.00，说明先扣掉的是便宜的那批");
        assertTicketBookConsistent(customer, station, product);
    }

    /**
     * 删除档位：只能删本站的，且**只删价目表，不动已售出的票**。
     *
     * <p>为什么单独钉一条：2026-09-18 起「商品上架 → 本站设置」里直接就能删档位
     * （站长不用再去档位模块），这条写路径因此从"只有接口"变成"界面天天点"。
     * 两条护栏必须成立：① 传别人的档位 id 删不掉（跨站）；② 删档位不能碰客户账户余额 ——
     * 票的价值由 {@code ticket_lot} 的单价快照记着，与价目表在不在无关。</p>
     */
    @Test
    @DisplayName("删除档位：跨站删不掉、删自己的只动价目表（已售水票一张不少）")
    void deletePackageGuardsStationAndKeepsSoldTickets() {
        long stationA = createStation("删档位A站");
        long stationB = createStation("删档位B站");
        long mgrA = createStaff("删档位站长A", "STATION_MANAGER", stationA, 1);
        long mgrB = createStaff("删档位站长B", "STATION_MANAGER", stationB, 1);
        long customer = createCustomer("删档位客户", "pkg-del-openid");
        long product = createProduct("删档位水", 1, "20.00", "30.00", 1, "9.00");
        createInventoryFull(stationA, product, 100, 1, "9.00");
        String tokenA = staffToken(mgrA, "STATION_MANAGER", stationA);
        String tokenB = staffToken(mgrB, "STATION_MANAGER", stationB);
        String cus = customerToken(customer);

        long packageId = post("/api/ticket-packages", tokenA,
                "{\"productId\":" + product + ",\"qty\":10,\"price\":80.00}")
                .data().path("id").asLong();

        // 先按档位买一张并使用：证明"卖出去的票"确实存在
        long paymentId = post("/api/tickets/purchase", cus,
                "{\"productId\":" + product + ",\"quantity\":10,\"paymentMethod\":2,\"stationId\":" + stationA
                        + ",\"packageId\":" + packageId + ",\"idempotencyKey\":\"pkg-del-buy\"}")
                .data().path("paymentId").asLong();
        assertEquals(0, put("/api/payments/" + paymentId + "/confirm", tokenA, null).code(), "站长确认收款");
        assertEquals(10, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=?",
                customer, stationA), "前置：客户账上已有 10 张");

        // ① 他站站长删不掉本站档位（按 id 操作必须验证归属）
        assertNotEquals(0, delete("/api/ticket-packages/" + packageId, tokenB).code(),
                "他站站长不得删除本站档位");
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_package WHERE id=?", packageId),
                "被拒之后档位必须还在");

        // ② 本站站长可以删，且**不动已售出的水票**
        assertEquals(0, delete("/api/ticket-packages/" + packageId, tokenA).code(), "本站站长应能删除档位");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_package WHERE id=?", packageId), "档位应已删除");
        assertEquals(10, intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=?",
                customer, stationA), "删档位不得影响客户账上的票");
        assertTicketBookConsistent(customer, stationA, product);

        // ③ 删不存在的 id 给业务错误（code=1），不是 500
        assertEquals(1, delete("/api/ticket-packages/" + packageId, tokenA).code(),
                "重复删除应给业务错误而不是系统异常");
    }

    /**
     * 复核 E8 的两条等式（{@code docs/design/19} §6）。
     *
     * <p>这里直接用 SQL 断言而不是去调对账服务：E8 的内容就是这两条等式，
     * 直接断言更清楚；对账服务本身有没有正确挂上 E8，由 {@code ReconciliationJobIntegrationTest} 覆盖。</p>
     */
    private void assertTicketBookConsistent(long customerId, long stationId, long productId) {
        int accountQty = intOf("SELECT COALESCE(remain_quantity,0) FROM ticket_account "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customerId, stationId, productId);
        int lotQty = intOf("SELECT COALESCE(SUM(remain_qty),0) FROM ticket_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customerId, stationId, productId);
        assertEquals(accountQty, lotQty,
                "E8 数量等式：账户余额必须等于 Σ 批次剩余（账户 " + accountQty + " vs 批次 " + lotQty + "）");

        BigDecimal accountAmount = decimalOf("SELECT COALESCE(right_amount,0) FROM ticket_account "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customerId, stationId, productId);
        BigDecimal lotAmount = decimalOf("SELECT COALESCE(SUM(remain_qty * unit_price),0) FROM ticket_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customerId, stationId, productId);
        assertTrue(accountAmount.subtract(lotAmount).abs().compareTo(new BigDecimal("0.009")) <= 0,
                "E8 金额等式：账户金额价值必须等于 Σ 剩余×批次单价（账户 " + accountAmount
                        + " vs 批次 " + lotAmount + "）");
    }
}
