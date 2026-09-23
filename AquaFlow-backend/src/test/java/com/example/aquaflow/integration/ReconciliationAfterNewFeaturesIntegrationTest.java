package com.example.aquaflow.integration;

import com.example.aquaflow.service.ReconciliationService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账回归：**把本次新增的写路径全部跑一遍之后，V1 与 V2 的每一条等式仍必须为 0**。
 *
 * <p>为什么必须有这一条：新增的每个功能各自都有用例，但那些用例只断言"自己那条等式"
 * （E8 / E10 / E-PAY）。而新增写路径真正的风险是**把原有的等式弄不平** ——
 * 一旦这样，每天 03:00 的日结就会报不平并发出 SYSTEM 告警，真问题被假警报淹没。</p>
 *
 * <p>⚠️ <b>造数必须自洽</b>：库存等式是 {@code inventory.quantity = SUM(inventory_record.delta)}，
 * 所以只插 {@code inventory} 不插流水，测出来的差异是造数问题、不是产品问题
 * （{@code ReconciliationJobIntegrationTest} 的注释已点名）。本用例因此
 * 每次都成对地造库存 + 流水。</p>
 *
 * <p>⚠️ 失败信息里带上**全量 map**：哪一项不平、差多少，一次运行就能定位，不用猜。</p>
 */
@DisplayName("对账回归 · 新增写路径跑完后 V1 与 V2 的每条等式仍为 0")
class ReconciliationAfterNewFeaturesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    private void assertAllZero(Map<String, Integer> counts, String what) {
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            assertEquals(0, e.getValue(),
                    what + " 的检查项 " + e.getKey() + " 应为 0，实际=" + e.getValue()
                            + "；全量=" + counts);
        }
    }

    private String returnBody(long orderId, int qty, int returned) {
        long itemId = longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", orderId);
        return "{\"itemReturns\":[{\"orderItemId\":" + itemId
                + ",\"expected\":" + qty + ",\"actual\":" + returned
                + ",\"reasons\":[{\"key\":\"customer_kept\",\"qty\":" + (qty - returned) + "}]}]"
                + ",\"collected\":true}";
    }

    @Test
    @DisplayName("配送计件 + 结算单 + 水票档位 + 应收核销，跑完后对账全平")
    void everyEqualityStaysBalanced() {
        long station = createStation("对账回归站");
        long manager = createStaff("对账站长", "STATION_MANAGER", station, 1);
        long rider = createStaff("对账配送员", "DELIVERY", station, 1);
        long customer = createCustomer("对账客户", "recon-openid");
        long address = createAddress(customer, "对账小区 1 号");
        long product = createProduct("对账水", 1, "20.00", "30.00", 1, "9.00");

        // 库存与流水成对造：只造 inventory 会让等式4 不平，那是造数问题而非产品问题
        createInventoryFull(station, product, 100, 1, "9.00");
        createInventoryRecord(station, product, 100, "in", product);
        createCustomerStationConfig(customer, station, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // ---- 1) 计件单价（Phase 2 的配置写入口）----
        assertEquals(0, put("/api/manager/piece-rate", mgr,
                "{\"productId\":0,\"perBucketAmount\":2.00}").code(),
                "设置计件单价");

        // ---- 2) 现金单走完配送：押金入账 + 桶权益 + 计件收益 ----
        // 手动造"配送中"的现金单（与 DeliveryCompleteIntegrationTest 同形）：
        // 必须带 in-transit 行，否则 applyDelivery 会把全额算成 over，模型自相矛盾。
        long order1 = createOrderFull(customer, address, station, product,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */,
                "40.00", "60.00", "100.00", true /* 首单：跳过回桶核对 */, 2 /* 送出桶数 */);
        createOrderItem(order1, product, "对账水 18.9L", 2, "20.00", "30.00", 1);
        createBarrelInTransit(customer, station, product, 2, "30.00", order1, "PENDING");

        assertEquals(0, post("/api/delivery/orders/" + order1 + "/complete", mgr,
                returnBody(order1, 2, 0)).code(), "完成配送（现场收款）");
        assertEquals(4, intOf("SELECT status FROM orders WHERE id=?", order1), "订单应已完成");

        // ---- 3) 结算单：生成 → 确认 → 标记发放（E-PAY 的两端）----
        String today = java.time.LocalDate.now().toString();
        Api gen = post("/api/manager/payroll", mgr,
                "{\"staffId\":" + rider + ",\"periodStart\":\"" + today + "\",\"periodEnd\":\"" + today + "\"}");
        assertEquals(0, gen.code(), "生成结算单: " + gen);
        long payrollId = gen.data().path("payrollId").asLong();
        assertEquals(0, post("/api/manager/payroll/" + payrollId + "/confirm", mgr, "{}").code(), "确认结算单");
        assertEquals(0, post("/api/manager/payroll/" + payrollId + "/pay", mgr, "{}").code(), "标记已发放");

        // ---- 4) 水票档位购票（档位 → 批次单价快照 → E8）----
        Api pkg = post("/api/ticket-packages", mgr,
                "{\"productId\":" + product + ",\"qty\":10,\"price\":\"90.00\",\"title\":\"10 张装\"}");
        assertEquals(0, pkg.code(), "挂档位: " + pkg);
        long packageId = pkg.data().path("id").asLong();
        Api buy = post("/api/tickets/purchase", customerToken(customer),
                "{\"productId\":" + product + ",\"quantity\":10,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"packageId\":" + packageId + ",\"idempotencyKey\":\"recon-ticket-1\"}");
        assertEquals(0, buy.code(), "按档位购票: " + buy);
        long paymentId = buy.data().path("paymentId").asLong();
        assertEquals(0, put("/api/payments/" + paymentId + "/confirm", mgr, null).code(), "站长确认收款（入批次）");

        // ---- 5) 应收核销（收款 + 核销，等式2 与 E10 的交汇点）----
        long order2 = createOrderFull(customer, address, station, product,
                1 /* 待配送 */, 1 /* 待收款 */, 2 /* 现金 */,
                "20.00", "0.00", "20.00", false, 0);
        assertEquals(0, post("/api/manager/receivables/settle", mgr,
                "{\"customerId\":" + customer + ",\"orderIds\":[" + order2 + "]}").code(), "核销应收");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order2), "核销应记为已付");
        assertEquals(2, intOf("SELECT settlement_status FROM orders WHERE id=?", order2), "核销应记为已结算");

        // ---- 6) 全部写路径跑完，对账必须全平 ----
        Map<String, Integer> v1 = reconciliationService.runReconcile();
        assertTrue(v1.size() >= 4, "V1 至少应含 押金/支付/桶/库存 四项，实际=" + v1.keySet());
        assertAllZero(v1, "V1");

        Map<String, Integer> v2 = reconciliationService.runReconcileV2();
        assertTrue(v2.size() >= 3, "V2 应含权益批次/占用恒等/物理守恒等项，实际=" + v2.keySet());
        assertAllZero(v2, "V2");
    }
}
