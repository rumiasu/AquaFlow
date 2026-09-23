package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 前置字段（v34）：站点坐标、地址楼层/电梯、订单与支付流水的费用列。
 *
 * <p>本版**只加字段，不改业务逻辑**（见 {@code docs/design/16} §3.0）。所以这组用例要证明的是
 * 三件事，而不是"新功能能用"：</p>
 * <ol>
 *   <li>新字段能存能取，且越界/非法输入被拒；</li>
 *   <li><b>存量口径没变</b> —— 下单金额仍是「水费 + 押金」，费用列恒为 0；</li>
 *   <li>整行覆盖没有把新字段冲掉 —— 这是本仓反复出事的形状（旧客户端不传新字段时，
 *       一次普通编辑就会把它们抹成 NULL）。</li>
 * </ol>
 */
class DeliveryFeeFieldsIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("站长地图选点：可存可读、越界被拒、可清除、顾客无权调用")
    void stationCoordinatesRoundTrip() {
        long station = createStation("选点站");
        long manager = createStaff("选点站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("选点客户", "coord-openid");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 初始为 NULL：站点没选点时坐标为空，此时配送范围校验必须跳过（不是拒单）
        assertEquals(1, intOf("SELECT COUNT(*) FROM station WHERE id=? AND lat IS NULL AND lng IS NULL", station),
                "新建站点的坐标应为 NULL");

        Api saved = put("/api/stations/mine/coordinates", mgr, "{\"lat\":39.908700,\"lng\":116.397500}");
        assertEquals(0, saved.code(), "站长选点应成功: " + saved);
        assertEquals(0, new BigDecimal("39.908700").compareTo(decimalOf("SELECT lat FROM station WHERE id=?", station)),
                "纬度应落库（decimal(10,6)）");
        assertEquals(0, new BigDecimal("116.397500").compareTo(decimalOf("SELECT lng FROM station WHERE id=?", station)),
                "经度应落库");

        // 读回来：站长查自己的站，坐标要一并返回给前端做展示
        Api mine = get("/api/stations/mine", mgr);
        assertEquals(0, mine.code(), "站长查自己的站: " + mine);
        assertTrue(mine.data().path("lat").asText().startsWith("39.90"),
                "坐标应随站点下发，实际=" + mine.data().path("lat").asText());

        // 越界必须拒绝：纬度 999 会让距离公式算出无意义结果
        assertNotEquals(0, put("/api/stations/mine/coordinates", mgr, "{\"lat\":999,\"lng\":116.4}").code(),
                "纬度越界必须被拒");
        assertNotEquals(0, put("/api/stations/mine/coordinates", mgr, "{\"lat\":39.9,\"lng\":999}").code(),
                "经度越界必须被拒");
        assertEquals(0, new BigDecimal("39.908700").compareTo(decimalOf("SELECT lat FROM station WHERE id=?", station)),
                "被拒的请求不得改动已存的坐标");

        // 顾客无权设置站点坐标
        assertNotEquals(0, put("/api/stations/mine/coordinates", customerToken(customer),
                "{\"lat\":1,\"lng\":2}").code(), "顾客不得设置站点坐标");

        // 清除坐标：允许（= 未设置），清除后范围校验跳过
        assertEquals(0, put("/api/stations/mine/coordinates", mgr, "{\"lat\":null,\"lng\":null}").code(),
                "清除坐标应被允许（清除后范围校验跳过，不会拒单）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM station WHERE id=? AND lat IS NULL", station), "坐标应已清空");
    }

    @Test
    @DisplayName("地址楼层与电梯：可存可读，且旧客户端不传时保留原值（不被整行覆盖抹掉）")
    void addressFloorSavedAndPreservedOnPartialUpdate() {
        long station = createStation("楼层站");
        long customer = createCustomer("楼层客户", "floor-openid");
        long address = createAddress(customer, "某小区 3 号楼 601");
        String cus = customerToken(customer);

        // 初始 NULL —— 与「确认无电梯(0)」必须区分，否则会向客户乱收楼层费
        assertEquals(1, intOf("SELECT COUNT(*) FROM address WHERE id=? AND floor IS NULL AND has_elevator IS NULL",
                address), "新建地址的楼层与电梯应为 NULL（未确认）");

        Api set = put("/api/addresses/" + address, cus,
                "{\"name\":\"楼层客户\",\"detail\":\"某小区 3 号楼 601\",\"floor\":6,\"hasElevator\":0}");
        assertEquals(0, set.code(), "保存楼层应成功: " + set);
        assertEquals(6, intOf("SELECT floor FROM address WHERE id=?", address), "楼层应落库");
        assertEquals(0, intOf("SELECT has_elevator FROM address WHERE id=?", address),
                "无电梯应存 0（而不是 NULL —— 两者语义不同）");

        // ⚠️ 核心断言：编辑地址时不传楼层字段，原值必须保留。
        // 旧版小程序不会传这两个新字段，而 mapper 的 update 是整行覆盖 ——
        // 没有这层保留合并，客户改一次收件人姓名就把楼层抹成 NULL，楼层费再也算不出来。
        Api partial = put("/api/addresses/" + address, cus,
                "{\"name\":\"新收件人\",\"detail\":\"某小区 3 号楼 601\"}");
        assertEquals(0, partial.code(), "部分字段更新应成功: " + partial);
        assertEquals(1, intOf("SELECT COUNT(*) FROM address WHERE id=? AND name=?", address, "新收件人"),
                "姓名应已更新");
        assertEquals(6, intOf("SELECT floor FROM address WHERE id=?", address),
                "未传 floor 时必须保留原值 6（整行覆盖防护）");
        assertEquals(0, intOf("SELECT has_elevator FROM address WHERE id=?", address),
                "未传 hasElevator 时必须保留原值 0");

        // 显式传值要能改
        assertEquals(0, put("/api/addresses/" + address, cus,
                "{\"name\":\"新收件人\",\"detail\":\"某小区 3 号楼 601\",\"floor\":3,\"hasElevator\":1}").code());
        assertEquals(3, intOf("SELECT floor FROM address WHERE id=?", address), "显式传值应能修改");
        assertEquals(1, intOf("SELECT has_elevator FROM address WHERE id=?", address));
    }

    @Test
    @DisplayName("下单口径未变：费用列恒为 0，total_amount 仍等于 水费 + 押金")
    void orderTotalsUnchangedAndFeeColumnsZero() {
        long station = createStation("金额站");
        long customer = createCustomer("金额客户", "fee-order-openid");
        long address = createAddress(customer, "金额小区 1 号");
        long product = createProduct("金额水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");

        Api created = post("/api/orders/create", customerToken(customer),
                "{\"addressId\":" + address + ",\"stationId\":" + station
                        + ",\"paymentMethod\":1,\"idempotencyKey\":\"phase0-fee-1\","
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":2}]}");
        assertEquals(0, created.code(), "下单应成功: " + created);
        long orderId = created.data().path("orderId").asLong();
        assertTrue(orderId > 0, "应返回订单ID: " + created);

        // 费用列默认 0，且本版**不参与计算**
        assertEquals(0, new BigDecimal("0.00").compareTo(decimalOf("SELECT delivery_fee FROM orders WHERE id=?", orderId)),
                "v34 只加列，配送费应恒为 0");
        assertEquals(0, new BigDecimal("0.00").compareTo(decimalOf("SELECT floor_fee FROM orders WHERE id=?", orderId)),
                "v34 只加列，楼层费应恒为 0");

        // ⚠️ 这是本阶段最重要的回归：total_amount 的构成**一个字都没改**
        // 报价与下单必须同口径（PriceUtil 文件头记录过"计价双轨"引发的客诉事故）
        BigDecimal total = decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId);
        BigDecimal water = decimalOf("SELECT water_amount FROM orders WHERE id=?", orderId);
        BigDecimal deposit = decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId);
        assertEquals(0, water.add(deposit).compareTo(total),
                "total_amount 必须仍等于 水费 + 押金（费用列本版不并入）：water=" + water
                        + " deposit=" + deposit + " total=" + total);

        // 报价侧同口径：quote 的 totalAmount 必须与落库 total 一致
        Api quote = post("/api/payments/quote", customerToken(customer),
                "{\"stationId\":" + station + ",\"paymentMethod\":1,"
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":2}]}");
        assertEquals(0, quote.code(), "报价: " + quote);
        assertEquals(0, new BigDecimal(quote.data().path("totalAmount").asText()).compareTo(total),
                "quote 的 totalAmount 必须与下单落库的 total_amount 相等（同口径）");

        // 支付流水也要带上同口径的费用列（当前为 0），供对账等式2 比对
        assertEquals(0, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND delivery_fee=0 AND floor_fee=0",
                orderId), "本版没有支付流水属正常；若有流水，其费用列必须为 0");
    }
}
