package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补齐矩阵里最后三个 🟡：S2.8「首单语义」、S2.9「下单入口的履约站」、S8.6「SQL 注入」。
 *
 * <p><b>首单语义（`first_barrel_order`）到底是怎么回事</b>（我第一版判断错了，留个记录）：
 * 它由 `OrderServiceImpl:453` 写成 `firstStationAsset && totalNeededBuckets > 0` ——
 * 即「该客户在本站的**第一笔需要买桶**的订单」；`OrderWorkflowServiceImpl:296` 读它，
 * 为真时**跳过回桶核对**（`if (!isFirstBarrelOrder)`）：客户刚从水站买下桶，手上根本没有空桶可还。
 * 与之配套的押金口径是 `shortage = max(0, needed − 已到手权益)` —— 首单权益为 0，所以收满押金；
 * 复购时权益已够，押金自然为 0。**两者是同一件事的两种表达，不要只看其中一个。**</p>
 */
class OrderEntryAndInjectionIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("复购不再收押金：押金只按「还缺几个桶权益」收；下单入口自己写履约站")
    void secondOrderWithEnoughRightsPaysNoDeposit() {
        long station = createStation("复购站");
        long manager = createStaff("复购站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("复购客户", "repurchase-openid");
        long product = createProduct("复购水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 50);
        long address = createAddress(customer, "复购地址");
        createCustomerStationConfig(customer, station, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // ---- 首单：手上 0 权益 → 需要买 2 个桶，押金 2×30=60 ----
        // 注意 needConfirm 是**库存不足**的确认（"暂时没货，需要等待配送"），与桶押金无关；
        // 库存充足时不会要求确认，直接建单。
        String body1 = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"first-1\",\"items\":[{\"productId\":"
                + product + ",\"quantity\":2}]}";
        Api order1 = post("/api/orders/create", cus, body1);
        assertEquals(0, order1.code(), "首单下单: " + order1);
        assertTrue(!order1.data().path("needConfirm").asBoolean(),
                "库存充足时不该要求确认（needConfirm 只用于库存不足）");
        long orderId1 = order1.data().path("orderId").asLong();
        assertEquals(0, new java.math.BigDecimal("60.00")
                .compareTo(decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId1)),
                "首单押金 = 缺桶数 × 桶押金");
        assertEquals(1, intOf("SELECT first_barrel_order FROM orders WHERE id=?", orderId1),
                "本站第一笔买桶订单必须打上 first_barrel_order（它决定完成配送时是否核对回桶）");
        // S2.9：下单入口只认一个水站 —— 归属站与履约站都写成客户选的那个站，
        // 跨站（station_id != delivery_station_id）只能由「外派」产生，下单阶段不可能出现。
        assertEquals(station, longOf("SELECT station_id FROM orders WHERE id=?", orderId1));
        assertEquals(station, longOf("SELECT delivery_station_id FROM orders WHERE id=?", orderId1));

        // ---- 库存不足：先要客户确认，确认前不得建单 ----
        long scarce = createProduct("紧俏水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, scarce, 1);
        String scarceBody = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"scarce-1\",\"items\":[{\"productId\":"
                + scarce + ",\"quantity\":3}]}";
        Api needConfirm = post("/api/orders/create", cus, scarceBody);
        assertEquals(0, needConfirm.code(), "库存不足时应返回「待确认」而不是报错: " + needConfirm);
        assertTrue(needConfirm.data().path("needConfirm").asBoolean(), "库存不足必须要求确认");
        assertEquals(1, needConfirm.data().path("shortages").size());
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE idempotency_key='scarce-1'"),
                "客户还没确认，不能先把单建出来");

        String scarceOk = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"scarce-1\",\"confirmShortage\":true,"
                + "\"items\":[{\"productId\":" + scarce + ",\"quantity\":3}]}";
        Api afterConfirm = post("/api/orders/create", cus, scarceOk);
        assertEquals(0, afterConfirm.code(), "确认缺货后应可下单: " + afterConfirm);
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE idempotency_key='scarce-1'"));

        // ---- 履约：接单 + 完成配送。首单的**空桶必须一个都不用还**（客户刚买下桶）----
        // 这里故意把 actual 填 0：若 first_barrel_order 没生效，就会生成"少收 2 桶"的异常单。
        assertEquals(0, post("/api/delivery/orders/" + orderId1 + "/accept", mgr, null).code(), "接单");
        long itemId = longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", orderId1);
        String completeBody = "{\"collected\":true,\"note\":\"first order\",\"itemReturns\":[{\"orderItemId\":"
                + itemId + ",\"productName\":\"p1\",\"expected\":2,\"actual\":0,\"reasons\":[]}]}";
        assertEquals(0, post("/api/delivery/orders/" + orderId1 + "/complete", mgr, completeBody).code(),
                "完成首单配送");
        assertEquals(2, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                        + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customer, station, product), "送达后客户应有 2 个桶权益");
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id=?", orderId1),
                "首单押金桶无需回桶：即使回桶数为 0 也不得生成桶异常单");
        assertEquals(0, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "首单不产生欠桶（买的 2 个桶正是送到的 2 个）");

        // ---- 复购：权益已够 → 不再收押金，也不再是首单 ----
        String body2 = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"second-1\",\"items\":[{\"productId\":"
                + product + ",\"quantity\":2}]}";
        Api order2 = post("/api/orders/create", cus, body2);
        assertEquals(0, order2.code(), "复购下单: " + order2);
        long orderId2 = order2.data().path("orderId").asLong();
        assertEquals(0, new java.math.BigDecimal("0.00")
                        .compareTo(decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId2)),
                "已有足够桶权益时不得再收押金");
        assertEquals(0, intOf("SELECT first_barrel_order FROM orders WHERE id=?", orderId2),
                "第二笔买桶订单不再是首单（此时要核对回桶）");
    }

    @Test
    @DisplayName("字符串参数不可注入：关键字/路径参数都被参数化或强类型挡住")
    void stringParametersAreNotInjectable() throws Exception {
        long station = createStation("注入站");
        long manager = createStaff("注入站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("张三", "inject-openid");
        long product = createProduct("注入水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 10);
        long address = createAddress(customer, "注入路1号");
        // CustomerMapper.searchByStation 是 `customer c JOIN orders o ... where o.station_id=?`，
        // 并且只搜 customer.name / customer.phone（不搜地址）——造数时要同时满足这两点，
        // 否则"正常关键字搜不到"会被误读成"参数化把搜索弄坏了"。
        createOrderFull(customer, address, station, product, 1, 1, 2,
                "10.00", "30.00", "40.00", true, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 1) 站长综合搜索：LIKE #{keyword} 参数化 → 注入串只会被当普通文本匹配（应 0 命中）
        Api search = get("/api/search?keyword=" + java.net.URLEncoder.encode("' OR 1=1 --", "UTF-8"), mgr);
        assertEquals(0, search.code(), "注入尝试不该让接口报错: " + search);
        assertEquals(0, search.data().get("customers").size(), "注入串不该命中任何客户");
        assertEquals(0, search.data().get("addresses").size());
        assertEquals(0, search.data().get("orders").size());
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer WHERE id=?", customer), "数据必须还在");

        // 2) 商品关键字：Java 侧过滤，注入串同样只是文本
        Api products = get("/api/products?keyword=" + java.net.URLEncoder.encode("' OR 1=1 --", "UTF-8"), cus);
        assertEquals(0, products.code());
        assertEquals(0, products.data().size(), "商品搜索不该被注入串放大成全部商品");

        // 3) 强类型参数：数字型 @RequestParam / @PathVariable 收到注入串应给出客户端错误，而不是服务端异常
        Api badStation = get("/api/products/sale-by-station?stationId=1%20OR%201=1", cus);
        assertNotEquals(0, badStation.code(), "非法 stationId 应被拒");
        assertNotEquals(500, badStation.code(), "参数类型错误属于客户端问题，不该报成系统异常: " + badStation);

        Api badPath = get("/api/customer/exceptions/1%20OR%201=1", cus);
        assertNotEquals(0, badPath.code(), "非法路径参数应被拒");
        assertNotEquals(500, badPath.code(), "不该是系统异常: " + badPath);

        // 4) 反向确认参数化没把正常搜索弄坏
        Api normal = get("/api/search?keyword=" + java.net.URLEncoder.encode("张三", "UTF-8"), mgr);
        assertEquals(0, normal.code());
        assertEquals(1, normal.data().get("customers").size(), "正常关键字仍应命中");
    }
}
