package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送员计件工资（v37，Phase 2）。
 *
 * <p>盯的是**账目数值**与**幂等**，不是接口是否返回成功 —— 这一版动的是"发给人多少钱"，
 * 多算一笔或多结一次都不会报错，只会悄悄把钱发错。</p>
 *
 * <p>三条不变量（{@code docs/design/18}）：</p>
 * <ol>
 *   <li>收益在订单进入「送达」时产生，<b>重复完成配送不能重复计钱</b>（{@code uk_earning_auto}）；</li>
 *   <li>归属站 = <b>履约站</b>（工钱是履约成本，跟出车的人走）；</li>
 *   <li>结算单合计 == 本期明细之和（对账 E-PAY），且「算出来」与「发出去」是两个状态。</li>
 * </ol>
 */
class StaffEarningAndPayrollIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("完成配送产生计件收益：送桶 + 楼层补贴 + 单奖，且归属履约站")
    void earningsGeneratedOnDelivery() {
        long[] ids = seed();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        long mgr2 = ids[4], delivery = ids[5];
        String mgr = staffToken(mgr2, "STATION_MANAGER", station);

        // 3 元/桶、无电梯每层 2 元（v42 起工资只有这两项，不再有回桶奖励/每单补贴/少收扣减）
        assertEquals(0, put("/api/manager/piece-rate", mgr,
                "{\"productId\":0,\"perBucketAmount\":3.00,"
                        + "\"floorBonusPerLevel\":2.00,\"floorFreeLevel\":1}").code(),
                "站长配计件单价");

        long order = deliveringCashOrder(customer, address, station, product, delivery, 2);
        Api done = post("/api/delivery/orders/" + order + "/complete",
                staffToken(delivery, "DELIVERY", station), "{\"collected\":true}");
        assertEquals(0, done.code(), "完成配送: " + done);

        // 送桶 2 × 3.00 = 6.00
        assertEquals(0, new BigDecimal("6.00").compareTo(
                        decimalOf("SELECT amount FROM staff_earning WHERE order_id=? AND kind='DELIVERY_BUCKET'", order)),
                "送桶计件应为 2 × 3.00");
        // 楼层：地址 6 层无电梯、免费 1 层 → 超 5 层 × 2.00 = 10.00
        assertEquals(0, new BigDecimal("10.00").compareTo(
                        decimalOf("SELECT amount FROM staff_earning WHERE order_id=? AND kind='FLOOR_BONUS'", order)),
                "楼层补贴应为 (6-1) × 2.00 = 10.00");
        // v42：已停用的三类**一条都不许再产生**（否则就是"删了配置还在偷偷算钱"）
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=? "
                        + "AND kind IN ('RETURN_BUCKET','ORDER_BONUS','PENALTY')", order),
                "v42 起回桶奖励/每单补贴/少收扣减都不再产生");

        // 归属站必须是**履约站**，且归属人必须是实际完成的人。
        // 注意断言写法：一单会产生多条收益（送桶/楼层各一条），所以不能断言 COUNT=1，
        // 而要断言「该单的全部收益都落在这个 (station, staff) 上」—— 后者才是真正要守的不变量。
        assertEquals(intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=?", order),
                intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=? AND station_id=? AND staff_id=?",
                        order, station, delivery),
                "该单的全部收益都必须记在履约站与完成人身上");
        assertEquals(2, intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=?", order),
                "一单两条：送桶计件 + 楼层补贴");
        assertEquals(0, new BigDecimal("16.00").compareTo(
                        decimalOf("SELECT COALESCE(SUM(amount),0) FROM staff_earning WHERE order_id=?", order)),
                "本单合计 6 + 10 = 16.00（不再有每单奖励的 1.00）");
    }

    @Test
    @DisplayName("重复完成配送不重复计钱（唯一键兜底），且站点没配计件时不产生任何收益")
    void earningsAreIdempotentAndAbsentWhenUnconfigured() {
        long[] ids = seed();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        long mgr2 = ids[4], delivery = ids[5];
        String mgr = staffToken(mgr2, "STATION_MANAGER", station);
        assertEquals(0, put("/api/manager/piece-rate", mgr, "{\"perBucketAmount\":3.00}").code());

        long order = deliveringCashOrder(customer, address, station, product, delivery, 2);
        assertEquals(0, post("/api/delivery/orders/" + order + "/complete",
                staffToken(delivery, "DELIVERY", station), "{\"collected\":true}").code());
        BigDecimal first = decimalOf("SELECT COALESCE(SUM(amount),0) FROM staff_earning WHERE order_id=?", order);
        assertEquals(0, new BigDecimal("6.00").compareTo(first), "首次应为 2 × 3.00");

        // 再完成一次：状态已不是配送中，必然被拒；即便绕过状态，唯一键也会拦住第二笔
        assertNotEquals(0, post("/api/delivery/orders/" + order + "/complete",
                staffToken(delivery, "DELIVERY", station), "{\"collected\":true}").code(),
                "重复完成配送必须被拒");
        assertEquals(0, first.compareTo(
                        decimalOf("SELECT COALESCE(SUM(amount),0) FROM staff_earning WHERE order_id=?", order)),
                "重复调用不得改变收益合计");

        // 站点没配计件的订单：不产生任何收益（"没配"是合法经营状态，不是故障）
        long station2 = createStation("未配计件站");
        long mgr3 = createStaff("未配计件站长", "STATION_MANAGER", station2, 1);
        long del2 = createStaff("未配计件配送员", "DELIVERY", station2, 1);
        long cust2 = createCustomer("未配计件客户", "norate-openid");
        long addr2 = createAddress(cust2, "未配计件小区 1 号");
        createInventoryFull(station2, product, 100, 0, "0.00");
        long order2 = deliveringCashOrder(cust2, addr2, station2, product, del2, 2);
        assertEquals(0, post("/api/delivery/orders/" + order2 + "/complete",
                staffToken(del2, "DELIVERY", station2), "{\"collected\":true}").code(),
                "没配计件也应能正常完成配送");
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=?", order2),
                "本站没配计件单价就不该产生任何收益");
    }

    @Test
    @DisplayName("结算单：合计 == 明细之和（E-PAY），确认后才能发放，同期间不得重复生成")
    void payrollLifecycleAndEpay() {
        long[] ids = seed();
        long station = ids[0], delivery = ids[5], mgr2 = ids[4];
        String mgr = staffToken(mgr2, "STATION_MANAGER", station);

        // 造三笔未结算收益（走人工调整入口，同时也验证了"允许自带符号"）
        assertEquals(0, post("/api/manager/payroll/adjust", mgr,
                "{\"staffId\":" + delivery + ",\"amount\":100.00,\"note\":\"补上月漏算\"}").code());
        assertEquals(0, post("/api/manager/payroll/adjust", mgr,
                "{\"staffId\":" + delivery + ",\"amount\":-20.00,\"note\":\"上月多算\"}").code());
        assertEquals(0, post("/api/manager/payroll/adjust", mgr,
                "{\"staffId\":" + delivery + ",\"amount\":5.50,\"note\":\"临时帮忙\"}").code());
        assertEquals(0, new BigDecimal("85.50").compareTo(
                        decimalOf("SELECT COALESCE(SUM(amount),0) FROM staff_earning WHERE staff_id=? AND payroll_id IS NULL",
                                delivery)), "三笔未结算合计 100 - 20 + 5.50 = 85.50");

        // 生成结算单（期间取今天，覆盖刚造的明细）
        String today = java.time.LocalDate.now().toString();
        Api gen = post("/api/manager/payroll", mgr,
                "{\"staffId\":" + delivery + ",\"periodStart\":\"" + today + "\",\"periodEnd\":\"" + today + "\"}");
        assertEquals(0, gen.code(), "生成结算单: " + gen);
        long payrollId = gen.data().path("payrollId").asLong();

        // ⚠️ E-PAY：合计必须等于本期明细之和
        BigDecimal total = decimalOf("SELECT total_amount FROM staff_payroll WHERE id=?", payrollId);
        BigDecimal detail = decimalOf("SELECT COALESCE(SUM(amount),0) FROM staff_earning WHERE payroll_id=?", payrollId);
        assertEquals(0, total.compareTo(detail),
                "E-PAY：结算单合计(" + total + ")必须等于明细之和(" + detail + ")");
        assertEquals(0, new BigDecimal("85.50").compareTo(total), "本期合计应为 85.50");
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_earning WHERE staff_id=? AND payroll_id IS NULL", delivery),
                "明细应全部挂上结算单");

        // 同一期间不得重复生成（否则同一笔工钱会被结算两次）
        assertNotEquals(0, post("/api/manager/payroll", mgr,
                "{\"staffId\":" + delivery + ",\"periodStart\":\"" + today + "\",\"periodEnd\":\"" + today + "\"}").code(),
                "同期间重复生成结算单必须被拒");

        // 未确认不能发放
        assertNotEquals(0, post("/api/manager/payroll/" + payrollId + "/pay", mgr, "{}").code(),
                "草稿状态不得直接标记发放");
        assertEquals(1, intOf("SELECT status FROM staff_payroll WHERE id=?", payrollId));

        // 确认 → 发放；两次状态都只前进
        assertEquals(0, post("/api/manager/payroll/" + payrollId + "/confirm", mgr, "{}").code(), "确认结算单");
        assertEquals(2, intOf("SELECT status FROM staff_payroll WHERE id=?", payrollId));
        assertNotEquals(0, post("/api/manager/payroll/" + payrollId + "/confirm", mgr, "{}").code(),
                "重复确认必须被拒");
        assertEquals(0, post("/api/manager/payroll/" + payrollId + "/pay", mgr, "{}").code(), "标记发放");
        assertEquals(3, intOf("SELECT status FROM staff_payroll WHERE id=?", payrollId));
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff_payroll WHERE id=? AND paid_time IS NOT NULL", payrollId),
                "发放必须留下时间痕迹（发钱在线下，系统只能留痕）");
        assertNotEquals(0, post("/api/manager/payroll/" + payrollId + "/pay", mgr, "{}").code(),
                "重复发放必须被拒");
    }

    @Test
    @DisplayName("计件单价防护：负值归零、按站隔离、跨站看不到也改不了")
    void pieceRateGuards() {
        long stationA = createStation("计件A站");
        long stationB = createStation("计件B站");
        long mgrA = createStaff("计件站长A", "STATION_MANAGER", stationA, 1);
        long mgrB = createStaff("计件站长B", "STATION_MANAGER", stationB, 1);
        String a = staffToken(mgrA, "STATION_MANAGER", stationA);
        String b = staffToken(mgrB, "STATION_MANAGER", stationB);

        // 负值一律归零：负数工钱会让站长倒欠配送员，属"能少付钱"的输入
        assertEquals(0, put("/api/manager/piece-rate", a,
                "{\"perBucketAmount\":-5.00,\"floorBonusPerLevel\":-3.00,\"floorFreeLevel\":-2}").code());
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        decimalOf("SELECT per_bucket_amount FROM staff_piece_rate WHERE station_id=? AND product_id=0",
                                stationA)), "负的计件价必须归零");
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        decimalOf("SELECT floor_bonus_per_level FROM staff_piece_rate WHERE station_id=? AND product_id=0",
                                stationA)), "负的楼层补贴必须归零");

        // 按站隔离：B 站看不到 A 站的配置
        assertEquals(0, get("/api/manager/piece-rate", b).data().path("rates").size(),
                "B 站站长看不到 A 站的计件配置");
        assertEquals(0, put("/api/manager/piece-rate", b, "{\"perBucketAmount\":2.00}").code());
        assertEquals(1, get("/api/manager/piece-rate", b).data().path("rates").size());
        assertEquals(0, new BigDecimal("2.00").compareTo(
                        decimalOf("SELECT per_bucket_amount FROM staff_piece_rate WHERE station_id=? AND product_id=0",
                                stationB)), "B 站的配置写在 B 站");
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        decimalOf("SELECT per_bucket_amount FROM staff_piece_rate WHERE station_id=? AND product_id=0",
                                stationA)), "A 站的配置不受影响");

        // 人工调整必须拒绝 0 金额（没有记录价值，也不该占位）
        assertNotEquals(0, post("/api/manager/payroll/adjust", a,
                "{\"staffId\":" + mgrA + ",\"amount\":0}").code(), "调整金额 0 必须被拒");
    }

    /**
     * 楼层上报（v43）：报了就按报的算并在明细里标记与地址不一致；没报就沿用地址。
     *
     * <p>为什么值得单独钉：楼层补贴是给配送员的钱，只有他知道自己爬了几层 ——
     * 让客户在地址里填的楼层既当收费依据又当发钱依据，等于用别人的话给自己发工资。
     * 这条用例锁三件事：① 上报值落库且驱动补贴；② 不一致必须留痕（防虚报）；③ 没上报时口径与升级前一致。</p>
     */
    @Test
    @DisplayName("楼层上报（v43）：按上报值算补贴并标记不一致，没上报则沿用地址")
    void floorReportDrivesBonus() {
        long[] ids = seed();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        long mgr = ids[4], delivery = ids[5];
        assertEquals(0, put("/api/manager/piece-rate", staffToken(mgr, "STATION_MANAGER", station),
                "{\"productId\":0,\"perBucketAmount\":3.00,\"floorBonusPerLevel\":2.00,\"floorFreeLevel\":1}").code(),
                "站长配计件单价");

        // ① 地址里是 6 层；配送员报 8 层 → 以他报的为准，并在明细里标出"不一致"
        long order = deliveringCashOrder(customer, address, station, product, delivery, 2);
        Api done = post("/api/delivery/orders/" + order + "/complete",
                staffToken(delivery, "DELIVERY", station), "{\"collected\":true,\"reportedFloor\":8}");
        assertEquals(0, done.code(), "完成配送: " + done);
        assertEquals(8, intOf("SELECT reported_floor FROM orders WHERE id=?", order),
                "上报值必须落库 —— 它是发钱的依据，也要能在订单上追溯");
        assertEquals(0, new BigDecimal("14.00").compareTo(
                        decimalOf("SELECT amount FROM staff_earning WHERE order_id=? AND kind='FLOOR_BONUS'", order)),
                "楼层补贴按上报的 8 层算：(8 − 1) × 2.00 = 14.00");
        assertTrue(jdbc.queryForObject(
                        "SELECT note FROM staff_earning WHERE order_id=? AND kind='FLOOR_BONUS'", String.class, order)
                        .contains("不一致"),
                "与地址不一致必须在明细里留痕（这就是防虚报的痕迹，站长看得到）");

        // ② 不报 → 沿用地址楼层（6 层 → (6−1) × 2.00 = 10.00），与升级前口径完全一致
        long order2 = deliveringCashOrder(customer, address, station, product, delivery, 2);
        assertEquals(0, post("/api/delivery/orders/" + order2 + "/complete",
                staffToken(delivery, "DELIVERY", station), "{\"collected\":true}").code(), "不填楼层也能完成配送");
        assertEquals(0, intOf("SELECT IFNULL(reported_floor, 0) FROM orders WHERE id=?", order2),
                "没上报时 reported_floor 必须是 NULL（= 沿用地址，不是 0 层）");
        assertEquals(0, new BigDecimal("10.00").compareTo(
                        decimalOf("SELECT amount FROM staff_earning WHERE order_id=? AND kind='FLOOR_BONUS'", order2)),
                "没上报就沿用地址的 6 层：(6 − 1) × 2.00 = 10.00");

        // ③ 荒唐的楼层数给业务错误（code=1），不是 500
        long order3 = deliveringCashOrder(customer, address, station, product, delivery, 2);
        assertEquals(1, post("/api/delivery/orders/" + order3 + "/complete",
                        staffToken(delivery, "DELIVERY", station), "{\"collected\":true,\"reportedFloor\":0}").code(),
                "0 层应给业务错误");
        assertEquals(1, post("/api/delivery/orders/" + order3 + "/complete",
                        staffToken(delivery, "DELIVERY", station), "{\"collected\":true,\"reportedFloor\":999}").code(),
                "999 层应给业务错误");
    }

    // ---------- helpers ----------

    /** @return {station, customer, address, product, manager, deliveryStaff} */
    private long[] seed() {
        long station = createStation("计件站");
        long manager = createStaff("计件站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("计件配送员", "DELIVERY", station, 1);
        long customer = createCustomer("计件客户", "piece-openid-" + station);
        long address = createAddress(customer, "计件小区 1 号");
        long product = createProduct("计件水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        // 货到付款要客户级授权（站长逐个开通）——不配就下不了现金单
        createCustomerStationConfig(customer, station, 1);
        // 地址 6 层、无电梯：用于验证楼层补贴
        jdbc.update("UPDATE address SET floor=6, has_elevator=0 WHERE id=?", address);
        return new long[]{station, customer, address, product, manager, delivery};
    }

    /**
     * 造一张「配送中(2)」的现金单，并指派给指定配送员。
     *
     * <p>直接造状态而不是走 下单→指派→接单 三步：本用例要验的是**计件与结算**，
     * 那三步已由 {@code DeliveryCompleteIntegrationTest} 等覆盖。</p>
     */
    private long deliveringCashOrder(long customer, long address, long station, long product,
                                     long deliveryStaff, int buckets) {
        long order = createOrderFull(customer, address, station, product, 2, 1, 2,
                "40.00", "60.00", "100.00", true, buckets);
        // ⚠️ 必须造 order_item：计件的送桶收益是**按 order_item 逐商品**产生的
        // （不同品类单价可以不同），没有明细就一分钱工钱都不会产生。
        insert("INSERT INTO order_item(order_id, product_id, product_name_snapshot, price, quantity, deposit, subtotal) "
                        + "VALUES (?,?,?,?,?,?,?)",
                order, product, "计件水", new BigDecimal("20.00"), buckets,
                new BigDecimal("30.00"), new BigDecimal("40.00"));
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", deliveryStaff, order);
        return order;
    }
}
