package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送员自助「我的工资」（2026-09-18）。
 *
 * <p>盯的是**越权与口径**，不是"接口能不能返回"：这个端点的身份完全来自登录态，
 * 一旦有人给它加一个 {@code staffId} 参数，配送员就能看到同事的工钱 ——
 * 而那种改动<b>不会报错</b>，只会在界面上安静地多出别人的数字。</p>
 *
 * <p>四条不变量：</p>
 * <ol>
 *   <li>只看得到自己的收益（换人查、带别人的 {@code staffId} 都拿不到）；</li>
 *   <li>期间口径含<b>当天</b>（写成 {@code <= 结束日} 会漏掉今天，AGENTS §8.19）；</li>
 *   <li>结算单状态与类型文案由后端下发（前端禁止自带映射表）；</li>
 *   <li>顾客调不到这个端点。</li>
 * </ol>
 */
@DisplayName("配送员自助 · 我的工资（只读 / 只看自己）")
class DeliveryMyEarningsIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("看得到自己的工钱：明细 + 期间合计 + 未结合计，且今天产生的算得进来")
    void showsOwnEarningsIncludingToday() {
        long[] ids = seed();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        long mgr = ids[4], deliveryA = ids[5];
        long order = completeOneOrder(station, customer, address, product, mgr, deliveryA);

        String tokenA = staffToken(deliveryA, "DELIVERY", station);
        Api mine = get("/api/delivery/earnings", tokenA);
        assertTrue(mine.isSuccess(), "配送员查自己的工资应成功，实际=" + mine);

        // 送桶 2×3.00 + 楼层 (6-1)×2.00 = 16.00（v42 起工资只有这两项，与 StaffEarningAndPayrollIntegrationTest 同口径）
        assertEquals(0, new BigDecimal("16.00").compareTo(mine.data().path("periodTotal").decimalValue()),
                "期间合计应为 16.00，实际=" + mine.data().path("periodTotal"));
        assertEquals(0, new BigDecimal("16.00").compareTo(mine.data().path("unsettledTotal").decimalValue()),
                "刚产生的工钱一分都还没结算，未结合计也应是 16.00");
        assertEquals(2, mine.data().path("items").size(), "本单产生两条收益：送桶计件 + 楼层补贴（v42 起不再有每单奖励）");

        // 类型文案必须由后端下发（前端自带 1/2/3 映射表在本仓是历史事故源头）
        StringBuilder kinds = new StringBuilder();
        for (int i = 0; i < mine.data().path("items").size(); i++) {
            String kindText = mine.data().path("items").get(i).path("kindText").asText();
            assertFalse(kindText == null || kindText.isEmpty(), "每条明细都要带类型文案");
            kinds.append(kindText).append(",");
        }
        assertTrue(kinds.toString().contains("送水计件"), "文案应包含「送水计件」，实际=" + kinds);
        assertEquals(order, mine.data().path("items").get(0).path("orderId").asLong(),
                "明细要能追溯到订单（配送员问「这笔是哪来的」时唯一的凭据）");

        // ⚠️ 时间窗上界：今天产生的工钱必须算得进来（<= 结束日 会漏掉今天）
        String today = LocalDate.now().toString();
        Api todayOnly = get("/api/delivery/earnings?from=" + today + "&to=" + today, tokenA);
        assertTrue(todayOnly.isSuccess(), "按当天查询应成功，实际=" + todayOnly);
        assertEquals(0, new BigDecimal("16.00").compareTo(todayOnly.data().path("periodTotal").decimalValue()),
                "把区间限定为「今天」仍必须看得到今天的工钱（写成 <= 结束日 会一条都查不到）");

        // 区间颠倒要给出可读错误，而不是返回一个空列表让人以为"没挣钱"
        assertFalse(get("/api/delivery/earnings?from=" + today + "&to=2020-01-01", tokenA).isSuccess(),
                "结束日早于开始日应报业务错误");
    }

    @Test
    @DisplayName("只看得到自己：同事查不到我的工钱，带上我的 staffId 也拿不到")
    void cannotSeeOthersEarnings() {
        long[] ids = seed();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        long mgr = ids[4], deliveryA = ids[5], deliveryB = ids[6];
        completeOneOrder(station, customer, address, product, mgr, deliveryA);

        String tokenB = staffToken(deliveryB, "DELIVERY", station);
        Api b = get("/api/delivery/earnings", tokenB);
        assertTrue(b.isSuccess(), "同事查自己的工资也应成功（结果是 0，而不是报错）");
        assertEquals(0, b.data().path("items").size(), "同事不该看到我的收益明细");
        assertEquals(0, BigDecimal.ZERO.compareTo(b.data().path("unsettledTotal").decimalValue()),
                "同事的未结合计应为 0");

        // 关键护栏：这个端点**不接受 staffId**，传了也必须被忽略（否则配送员改个数字就能看别人的工钱）
        Api forged = get("/api/delivery/earnings?staffId=" + deliveryA, tokenB);
        assertTrue(forged.isSuccess(), "多传参数不该报错，但也不能生效");
        assertEquals(0, forged.data().path("items").size(), "带着别人的 staffId 也必须只返回自己的数据");
    }

    @Test
    @DisplayName("结算单：生成后未结合计归零，状态文案随「确认 / 发放」变化")
    void payrollStatusIsVisibleToTheOwner() {
        long[] ids = seed();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        long mgr = ids[4], deliveryA = ids[5];
        completeOneOrder(station, customer, address, product, mgr, deliveryA);
        String tokenA = staffToken(deliveryA, "DELIVERY", station);
        String today = LocalDate.now().toString();

        assertEquals(0, post("/api/manager/payroll", staffToken(mgr, "STATION_MANAGER", station),
                        "{\"staffId\":" + deliveryA + ",\"periodStart\":\"" + today + "\",\"periodEnd\":\""
                                + today + "\"}").code(),
                "站长按「今天~今天」生成结算单（上界含当天，见 AGENTS §8.19）");

        Api after = get("/api/delivery/earnings", tokenA);
        assertTrue(after.isSuccess(), "实际=" + after);
        assertEquals(1, after.data().path("payrolls").size(), "配送员应看到站长给自己开的结算单");
        assertEquals("草稿", after.data().path("payrolls").get(0).path("statusText").asText(),
                "状态文案由后端下发（前端不得自带 1/2/3 映射表）");
        assertEquals(0, new BigDecimal("16.00").compareTo(
                        after.data().path("payrolls").get(0).path("totalAmount").decimalValue()),
                "结算单合计应等于本期明细之和");
        assertEquals(0, BigDecimal.ZERO.compareTo(after.data().path("unsettledTotal").decimalValue()),
                "已挂到结算单上的工钱不再算「未结」");
        assertEquals(0, new BigDecimal("16.00").compareTo(after.data().path("periodTotal").decimalValue()),
                "结算不改变期间收益合计（结的是「发没发」，不是「挣没挣」）");

        long payrollId = after.data().path("payrolls").get(0).path("id").asLong();
        String mgrToken = staffToken(mgr, "STATION_MANAGER", station);
        assertEquals(0, post("/api/manager/payroll/" + payrollId + "/confirm", mgrToken, "{}").code(),
                "站长确认结算单");
        assertEquals(0, post("/api/manager/payroll/" + payrollId + "/pay", mgrToken, "{}").code(),
                "站长登记已发放（发钱本身在线下）");

        Api paid = get("/api/delivery/earnings", tokenA);
        assertEquals("已发放", paid.data().path("payrolls").get(0).path("statusText").asText(),
                "发放后配送员要能看到状态变化");
        assertFalse(paid.data().path("payrolls").get(0).path("paidTime").isNull(),
                "发放时间要留痕（否则下个月谁也说不清这笔发没发过）");
    }

    @Test
    @DisplayName("角色边界：顾客调不到；站长也能调（站长自己也可能送水）")
    void roleGuard() {
        long[] ids = seed();
        long station = ids[0], mgr = ids[4];
        long customer = ids[1];

        assertFalse(get("/api/delivery/earnings", customerToken(customer)).isSuccess(),
                "顾客不得查看员工工资");
        Api mgrSelf = get("/api/delivery/earnings", staffToken(mgr, "STATION_MANAGER", station));
        assertTrue(mgrSelf.isSuccess(), "站长自己送水时也该看得到自己的工钱，实际=" + mgrSelf);
        assertEquals(0, mgrSelf.data().path("items").size(), "本用例里站长没送过单，明细应为空");
    }

    // ---------- helpers ----------

    /** @return {station, customer, address, product, manager, deliveryA, deliveryB} */
    private long[] seed() {
        long station = createStation("工资站");
        long manager = createStaff("工资站长", "STATION_MANAGER", station, 1);
        long deliveryA = createStaff("工资配送员A", "DELIVERY", station, 1);
        long deliveryB = createStaff("工资配送员B", "DELIVERY", station, 1);
        long customer = createCustomer("工资客户", "myearn-openid-" + station);
        long address = createAddress(customer, "工资小区 1 号");
        long product = createProduct("工资水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        // 货到付款是客户级授权（站长逐个开通），不配就下不了现金单
        createCustomerStationConfig(customer, station, 1);
        jdbc.update("UPDATE address SET floor=6, has_elevator=0 WHERE id=?", address);
        // 3 元/桶、无电梯每层 2 元、每单 1 元 —— 与 StaffEarningAndPayrollIntegrationTest 同口径
        assertEquals(0, put("/api/manager/piece-rate", staffToken(manager, "STATION_MANAGER", station),
                        "{\"productId\":0,\"perBucketAmount\":3.00,"
                                + "\"floorBonusPerLevel\":2.00,\"floorFreeLevel\":1}").code(),
                "站长配计件单价");
        return new long[]{station, customer, address, product, manager, deliveryA, deliveryB};
    }

    /** 造一张「配送中(2)」的现金单并完成配送，产生 16.00 收益；返回订单 id。 */
    private long completeOneOrder(long station, long customer, long address, long product,
                                  long manager, long deliveryStaff) {
        long order = createOrderFull(customer, address, station, product, 2, 1, 2,
                "40.00", "60.00", "100.00", true, 2);
        // ⚠️ 必须造 order_item：送桶计件是**按 order_item 逐商品**产生的，没有明细一分钱都不会有
        insert("INSERT INTO order_item(order_id, product_id, product_name_snapshot, price, quantity, deposit, subtotal) "
                        + "VALUES (?,?,?,?,?,?,?)",
                order, product, "工资水", new BigDecimal("20.00"), 2,
                new BigDecimal("30.00"), new BigDecimal("40.00"));
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", deliveryStaff, order);
        assertEquals(0, post("/api/delivery/orders/" + order + "/complete",
                staffToken(deliveryStaff, "DELIVERY", station), "{\"collected\":true}").code(),
                "完成配送应成功");
        return order;
    }
}
