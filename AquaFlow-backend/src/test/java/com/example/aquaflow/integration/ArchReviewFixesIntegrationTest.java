package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构评审报告（2026-09-25）问题 1 / 2 / 3 / 7 / 9 的收口回归。
 *
 * <p>这份用例存在的意义是<b>把"拒绝"钉死</b>：报告里这几条的共同形态都是
 * "入口没有把不合法的输入挡住，于是坏数据一路走到落库"，而这类缺陷的特点是
 * <b>不报错、不显眼</b>（幽灵单、跨站建单、被吞掉的事务异常）。</p>
 *
 * <ul>
 *   <li><b>问题 1</b>：未完成身份选择的会话（role=UNSELECTED）只能走引导流程 —— 断言
 *       "读不到订单" + "select-role 仍然可用"（不过度收口）；</li>
 *   <li><b>问题 2</b>：{@code POST /api/orders}（裸实体整行更新）已删除 —— 断言<b>404</b>
 *       （只断言"不成功"是不够的：越权拒绝与端点不存在都会让 {@code !isSuccess()} 成立，
 *       见 {@code AuthzIsolationIntegrationTest} 里记的这条教训）；</li>
 *   <li><b>问题 3</b>：员工代客下单必须用<b>登录水站</b>（产品裁定：跨站代客下单不合法）——
 *       断言跨站被拒且零副作用、本站仍可下单；</li>
 *   <li><b>问题 7</b>：支付方式 / 订单来源必须走白名单 —— 断言非法值被拒且零副作用；</li>
 *   <li><b>问题 9</b>：{@code completeDelivery} 不再吞掉桶异常单的写入失败 —— 用"备注超长"
 *       在<b>真实的写入点</b>制造一次数据库拒绝，断言整笔回滚 + 可读的业务拒绝（code=1），
 *       而不是把事务判死后抛 {@code UnexpectedRollbackException}（表现为 500）。</li>
 * </ul>
 */
@DisplayName("架构评审收口（2026-09-25 问题 1/2/3/7/9）")
class ArchReviewFixesIntegrationTest extends AbstractIntegrationTest {

    /* ==================== 问题 1：未选身份会话 ==================== */

    @Test
    @DisplayName("问题1：UNSELECTED 会话读订单列表 → 拒绝，且一条订单都看不到")
    void unselectedSession_cannotListOrders() {
        long stationA = createStation("A站");
        long stationB = createStation("B站");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        long customerA = createCustomer("甲客户", "openid-fix-a");
        long customerB = createCustomer("乙客户", "openid-fix-b");
        jdbc.update("update customer set phone = ? where id = ?", "13800000001", customerA);
        jdbc.update("update customer set phone = ? where id = ?", "13800000002", customerB);
        createOrderFull(customerA, createAddress(customerA, "A小区1号"), stationA, product,
                1, 2, 2, "20.00", "0.00", "20.00", false, 0);
        createOrderFull(customerB, createAddress(customerB, "B小区2号"), stationB, product,
                1, 2, 2, "20.00", "0.00", "20.00", false, 0);

        Api res = get("/api/orders", unselectedStaffToken(-987654321L, "openid-fix-unselected"));

        assertFalse(res.isSuccess(), "未选身份的会话不得读订单列表，实际=" + res);
        assertTrue(res.data() == null || res.data().isNull() || res.data().size() == 0,
                "被拒的响应里不得夹带任何订单（含客户姓名/电话/地址），实际=" + res.body());
    }

    @Test
    @DisplayName("问题1：收口不能堵死引导流程 —— UNSELECTED 会话仍可 select-role（而且只能做这件事）")
    void unselectedSession_canStillSelectRole() {
        String token = unselectedStaffToken(-123123123L, "openid-fix-onboarding");

        // 引导流程本步必须仍然可用，否则新用户永远进不来（这是本次收口最容易犯的错）
        Api selectRole = post("/api/auth/select-role", token,
                "{\"role\":\"DELIVERY\",\"nickname\":\"新配送员\",\"phone\":\"13900000000\"}");
        assertTrue(selectRole.isSuccess(), "首次选身份必须仍然可用，实际=" + selectRole);
        assertEquals(1, intOf("SELECT COUNT(*) FROM staff WHERE openid=?", "openid-fix-onboarding"),
                "select-role 应正常建出员工记录");

        // 同一会话在选身份之前，业务端点一律拒绝（列表只是其中一个代表）
        String freshToken = unselectedStaffToken(-456456456L, "openid-fix-onboarding-2");
        assertFalse(get("/api/delivery/orders/pending", freshToken).isSuccess(),
                "配送待办列表也必须拒绝未选身份的会话");
        assertFalse(get("/api/manager/owed-barrels", freshToken).isSuccess(),
                "站长端点同样拒绝");
    }

    /* ==================== 问题 2：通用整行更新端点已删除 ==================== */

    @Test
    @DisplayName("问题2：POST /api/orders（裸实体整行更新）不再有任何写入口 —— 订单零变化")
    void genericOrderUpdateEndpoint_isGone() {
        long station = createStation("A站");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventory(station, product, 10);
        long customer = createCustomer("甲客户", "openid-fix-gone");
        long addr = createAddress(customer, "A小区9号");
        long order = createOrderFull(customer, addr, station, product,
                1, 1, 2, "20.00", "0.00", "20.00", false, 0);
        long manager = createStaff("A站站长", "STATION_MANAGER", station, 1);
        String token = staffToken(manager, "STATION_MANAGER", station);

        // 注意：这里**不能**断言 404 —— /api/orders 这个路径上还有 GET（订单列表），
        // 所以 Spring 抛的是"方法不支持"而不是"路由不存在"。断言真正重要的那件事：
        // 请求打不进去、订单一个字段都没变（改价/搬结算站的旁路已经没了）。
        Api res = post("/api/orders", token,
                "{\"id\":" + order + ",\"totalAmount\":0.01}");

        assertFalse(res.isSuccess(), "该写入口必须消失，实际=" + res);
        assertTrue(res.message().contains("请求方法"),
                "拒绝原因应指向「请求方法不支持」（方法级拒绝，不是路由 404），实际=" + res.message());
        assertEquals(0, decimalOf("SELECT total_amount FROM orders WHERE id=?", order)
                .compareTo(new BigDecimal("20.00")), "订单金额不得被改写");
        assertEquals(station, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "履约站不得被改写");
        // 夹具没有写 settle_station_id（保持 NULL），所以"没被改写"的判据就是它仍然是 NULL ——
        // 原旁路一旦打通，这里会变成客户端传入的站别（营收归属被搬走）。
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE id=? AND settle_station_id IS NULL", order),
                "结算站不得被改写");
    }

    /* ==================== 问题 3：员工代客下单必须用登录水站 ==================== */

    @Test
    @DisplayName("问题3：A 站员工替 B 站客户在 B 站下单 → 拒绝，且订单/库存零副作用")
    void crossStationStaffProxyOrder_isRejected() {
        long stationA = createStation("A站");
        long stationB = createStation("B站");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventory(stationA, product, 10);
        createInventory(stationB, product, 10);
        long customer = createCustomer("乙客户", "openid-fix-b2");
        long addr = createAddress(customer, "B小区3号");
        createCustomerStationConfig(customer, stationB, 1);      // 客户经营归属 = B 站
        long staffA = createStaff("A站站长", "STATION_MANAGER", stationA, 1);

        String body = "{\"customerId\":" + customer + ",\"stationId\":" + stationB
                + ",\"addressId\":" + addr + ",\"paymentMethod\":2"
                + ",\"items\":[{\"productId\":" + product + ",\"quantity\":1}]"
                + ",\"idempotencyKey\":\"fix-cross-station\"}";
        Api res = post("/api/orders/create", staffToken(staffA, "STATION_MANAGER", stationA), body);

        assertFalse(res.isSuccess(), "跨站代客下单不合法（产品裁定 2026-09-25），实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "被拒后不得建单");
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                stationB, product), "被拒后不得扣他站库存");
    }

    @Test
    @DisplayName("问题3：未选身份的会话也不能代客下单")
    void unselectedSession_cannotProxyOrder() {
        long station = createStation("B站");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventory(station, product, 10);
        long customer = createCustomer("乙客户", "openid-fix-b3");
        long addr = createAddress(customer, "B小区4号");
        createCustomerStationConfig(customer, station, 1);

        String body = "{\"customerId\":" + customer + ",\"stationId\":" + station
                + ",\"addressId\":" + addr + ",\"paymentMethod\":2"
                + ",\"items\":[{\"productId\":" + product + ",\"quantity\":1}]"
                + ",\"idempotencyKey\":\"fix-unselected-proxy\"}";
        Api res = post("/api/orders/create", unselectedStaffToken(-777777777L, "openid-fix-proxy"), body);

        assertFalse(res.isSuccess(), "未选身份的会话不得代客下单，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"));
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product));
    }

    @Test
    @DisplayName("问题3：本站代客下单不受影响（正常旅程不退化）")
    void sameStationStaffProxyOrder_stillWorks() {
        long station = createStation("A站");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventory(station, product, 10);
        long customer = createCustomer("甲客户", "openid-fix-a2");
        long addr = createAddress(customer, "A小区5号");
        createCustomerStationConfig(customer, station, 1);
        long staff = createStaff("A站站长", "STATION_MANAGER", station, 1);

        String body = "{\"customerId\":" + customer + ",\"stationId\":" + station
                + ",\"addressId\":" + addr + ",\"paymentMethod\":2,\"source\":1"
                + ",\"items\":[{\"productId\":" + product + ",\"quantity\":1}]"
                + ",\"idempotencyKey\":\"fix-same-station\"}";
        Api res = post("/api/orders/create", staffToken(staff, "STATION_MANAGER", station), body);

        assertTrue(res.isSuccess(), "本站代客下单必须仍然可用，实际=" + res);
        long orderId = res.data().get("orderId").asLong();
        assertEquals(station, longOf("SELECT station_id FROM orders WHERE id=?", orderId));
        // [2026-09-25 库存预留模型] 下单不扣实物，改为一条预留凭据（旧口径断言的是 9）
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product), "下单不动实物");
        assertEquals(1, intOf("SELECT COALESCE(SUM(reserved_qty),0) FROM inventory_reservation "
                + "WHERE order_id=? AND status=1", orderId), "下单必须留下预留凭据");
    }

    /* ==================== 问题 7：枚举白名单 ==================== */

    @Test
    @DisplayName("问题7：paymentMethod=99 → 拒绝，不建单、不扣库存、不建配送中桶")
    void unknownPaymentMethod_isRejected() {
        long station = createStation("A站");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventory(station, product, 10);
        long customer = createCustomer("甲客户", "openid-fix-a3");
        long addr = createAddress(customer, "A小区6号");

        Api res = post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + station + ",\"addressId\":" + addr + ",\"paymentMethod\":99"
                        + ",\"items\":[{\"productId\":" + product + ",\"quantity\":1}]"
                        + ",\"idempotencyKey\":\"fix-unknown-paymethod\"}");

        assertFalse(res.isSuccess(), "未知支付方式必须被拒，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"), "幽灵单不得落库");
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product), "被拒后不得扣库存");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit"), "不得留下配送中桶");
    }

    @Test
    @DisplayName("问题7：source=99（订单来源越界）→ 拒绝")
    void unknownOrderSource_isRejected() {
        long station = createStation("A站");
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventory(station, product, 10);
        long customer = createCustomer("甲客户", "openid-fix-a4");
        long addr = createAddress(customer, "A小区7号");

        Api res = post("/api/orders/create", customerToken(customer),
                "{\"stationId\":" + station + ",\"addressId\":" + addr + ",\"paymentMethod\":2"
                        + ",\"source\":99"
                        + ",\"items\":[{\"productId\":" + product + ",\"quantity\":1}]"
                        + ",\"idempotencyKey\":\"fix-unknown-source\"}");

        assertFalse(res.isSuccess(), "越界的订单来源必须被拒，实际=" + res);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders"));
    }

    @Test
    @DisplayName("问题7：非法支付方式的兜底文案不得说成某个已知支付方式")
    void unknownPayMethodText_doesNotClaimAKnownMethod() {
        // 历史脏数据（入口修复前落库的 99）在界面上必须显示成"未知"，不能显示成"现金" ——
        // 否则站长会以为那是一张货到付款单（AGENTS §6 明令：兜底文案不许把未知值说成已知值）
        assertEquals("未知支付方式", com.example.aquaflow.constant.PayMethod.textOf(99));
        assertEquals("未指定", com.example.aquaflow.constant.PayMethod.textOf(null));
        assertEquals("现金", com.example.aquaflow.constant.PayMethod.textOf(2));
    }

    /* ==================== 问题 9：桶异常单写不成 → 整笔回滚 + 可读拒绝 ==================== */

    @Test
    @DisplayName("问题9：回桶有差异时正常完成配送，并落一条待处置的桶异常单")
    void barrelDiscrepancy_onNormalPath_stillRecordsException() {
        long station = createStation("A站");
        long order = seedDeliveringOrder(station, 2);

        Api res = post("/api/delivery/orders/" + order + "/complete",
                staffToken(managerOf(station), "STATION_MANAGER", station),
                "{\"returnBucketQty\":1,\"barrelDiscrepancyNote\":\"客户只还了 1 个桶\"}");

        assertTrue(res.isSuccess(), "正常路径不得因为本次修复而失败，实际=" + res);
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id=?", order),
                "回桶差异必须落异常单（站长处置依据）");
    }

    @Test
    @DisplayName("问题9：异常单写入失败 → 整笔回滚 + code=1 可读拒绝（不是把事务判死后 500）")
    void barrelExceptionWriteFailure_rollsBackWholeDeliveryWithReadableError() {
        long station = createStation("A站");
        long order = seedDeliveringOrder(station, 2);
        // 在**真实的写入点**制造一次数据库拒绝：order_barrel_exception.staff_note 是 varchar(500)，
        // 而 barrelDiscrepancyNote 由请求体直接给出（legacy 回桶路径）⇒ 超长必然写失败。
        // 这就是报告 §5.9 要求的那种"注入失败"，只是不用 mock（本仓测试一律真 HTTP + 真库）。
        String tooLongNote = "桶".repeat(600);

        Api res = post("/api/delivery/orders/" + order + "/complete",
                staffToken(managerOf(station), "STATION_MANAGER", station),
                "{\"returnBucketQty\":1,\"barrelDiscrepancyNote\":\"" + tooLongNote + "\"}");

        assertEquals(1, res.code(), "应是可读的业务拒绝（code=1），不是 500，实际=" + res);
        // 整笔回滚：订单仍停在配送中(2)，异常单/工钱/欠桶一条都没落
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "状态不得前进（整笔回滚）");
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id=?", order));
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff_earning WHERE order_id=?", order),
                "工钱只在状态 CAS 成功后产生，回滚后不得有收益");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_barrel_over WHERE customer_id="
                + "(SELECT customer_id FROM orders WHERE id=?)", order),
                "桶账（欠桶）也必须一并回滚");
    }

    /* ==================== 夹具 ==================== */

    /**
     * 造一张"配送中"的现金单（首单标记为 false，否则 completeDelivery 会整段跳过回桶核对）。
     *
     * @param deliveredQty 应回收的桶数（= orders.delivery_bucket_qty），留出差异空间
     */
    private long seedDeliveringOrder(long station, int deliveredQty) {
        long product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventory(station, product, 10);
        long customer = createCustomer("甲客户", "openid-fix-deliver-" + station);
        long addr = createAddress(customer, "A小区8号");
        // ⚠️ 必须给客户先建**桶权益**：BarrelLedgerService 的物理护栏是
        // `回收数 ≤ 占用(权益 + over)`（见 AGENTS §1.1「桶的四个数」）。
        // 权益为 0 时"还回 1 个桶"会被直接拒（实测报「回收空桶数(1)超过该客户当前持有数(0)」）——
        // 那是桶账护栏在正常工作，不是完成配送的缺陷。
        createBarrelLot("FIX-LOT-" + station + "-" + deliveredQty, customer, station, product,
                "30.00", deliveredQty, deliveredQty);
        createBarrelAsset(customer, station, product, deliveredQty, "60.00");
        long order = createOrderFull(customer, addr, station, product,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */, "40.00", "0.00", "40.00",
                false /* 非首单：必须走回桶核对 */, deliveredQty);
        createOrderItemFull(order, product, "桶装水18.9L", deliveredQty, deliveredQty, "10.00", "30.00");
        return order;
    }

    private long managerOf(long station) {
        return createStaff("A站站长", "STATION_MANAGER", station, 1);
    }
}
