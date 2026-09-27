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
 * 它由 `OrderServiceImpl` 写成「本单要送桶 **且** 客户在本站**还没有桶权益**」——
 * 即「该客户在本站的**第一笔需要买桶**的订单」；`OrderWorkflowServiceImpl.completeDelivery` 读它，
 * 为真时**跳过回桶核对**（`if (!isFirstBarrelOrder)`）：客户刚从水站买下桶，手上根本没有空桶可还。
 * 与之配套的押金口径是 `shortage = max(0, needed − 已到手权益)` —— 首单权益为 0，所以收满押金；
 * 复购时权益已够，押金自然为 0。**两者是同一件事的两种表达，不要只看其中一个。**</p>
 *
 * <p>⚠️ [2026-09-26] 判据**只看桶**：以前写的是 `firstStationAsset`（票/押金/桶任一为有就算老客户），
 * 于是"先买过水票、再第一次买桶"的客户被判成非首单 —— 完成配送页要求核对回桶、回桶数还被默认填成
 * "送出多少回多少"，而他手里一个空桶都没有（产品原话：「第一次送达桶确实不需要回收，把第一次桶送达时
 * 的默认回桶值取消掉」）。用例见 {@link #firstBarrelOrderIgnoresTicketsBoughtEarlier()}。</p>
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

    /**
     * [2026-09-26 产品口径] 先买过水票的客户，第一笔买桶单**仍然是首单**。
     *
     * <p>这是判据从 `hasStationAsset`（票/押金/桶任一）收窄到 `hasBarrelAsset`（只看桶）之前
     * 的真实缺陷：票账户一存在就被判成"老客户"，于是完成配送页要求核对回桶、并把回桶数默认填成
     * "送出多少回多少" —— 客户手里一个空桶都没有。照那个默认提交，还会凭空给他记上欠桶。</p>
     */
    @Test
    @DisplayName("先买过水票的客户：第一笔买桶单仍是首单，完成配送不核对回桶、也不产生欠桶")
    void firstBarrelOrderIgnoresTicketsBoughtEarlier() {
        long station = createStation("票后首单站");
        long manager = createStaff("票后首单站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("票后首单客户", "ticket-first-openid");
        long product = createProduct("票后首单水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 50);
        long address = createAddress(customer, "票后首单地址");
        createCustomerStationConfig(customer, station, 1);
        // 先在本站买 5 张水票：票账户 remain>0 → hasStationAsset 为真（但那与"手里有没有空桶"无关）
        createTicketAccount(customer, station, product, 5);

        String cus = customerToken(customer);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        String body = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"ticket-first-1\",\"items\":[{\"productId\":"
                + product + ",\"quantity\":2}]}";
        Api created = post("/api/orders/create", cus, body);
        assertEquals(0, created.code(), "下单: " + created);
        long order = created.data().path("orderId").asLong();
        assertEquals(1, intOf("SELECT first_barrel_order FROM orders WHERE id=?", order),
                "买过水票不影响「本站第一笔买桶订单」的判定（只看桶权益，不看票/押金）");
        assertEquals(0, new java.math.BigDecimal("60.00")
                        .compareTo(decimalOf("SELECT deposit_amount FROM orders WHERE id=?", order)),
                "首单权益为 0 → 仍是收满押金（2 × 30）");

        // 完成配送：客户手上没有空桶，回桶数就是 0（页面也不会给"默认回满"的输入）
        assertEquals(0, post("/api/delivery/orders/" + order + "/accept", mgr, null).code(), "接单");
        long itemId = longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", order);
        String completeBody = "{\"collected\":true,\"itemReturns\":[{\"orderItemId\":" + itemId
                + ",\"productName\":\"p1\",\"expected\":2,\"actual\":0,\"reasons\":[]}]}";
        assertEquals(0, post("/api/delivery/orders/" + order + "/complete", mgr, completeBody).code(),
                "完成配送");
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_barrel_exception WHERE order_id=?", order),
                "第一笔买桶单不该因为「回桶 0」生成桶异常单");
        assertEquals(0, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                        + "WHERE customer_id=? AND station_id=?", customer, station),
                "也不该凭空产生欠桶（他从来没拿过桶）");
        assertEquals(2, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                        + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customer, station, product), "送达后客户应有 2 个桶权益");
    }

    @Test
    @DisplayName("混合单：默认回桶只算客户手上的旧桶，本单新买押金的桶不回收（也不能回收）")
    void mixedOrderDefaultsToExistingBarrelsOnly() {
        long station = createStation("混合单站");
        long manager = createStaff("混合单站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("混合单客户", "mixed-order-openid");
        long product = createProduct("混合单水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(station, product, 50);
        long address = createAddress(customer, "混合单地址");
        createCustomerStationConfig(customer, station, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // ---- 先造"客户手上已有 2 个桶"：首单 2 桶 + 送达（首单不核对回桶）----
        String first = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"mixed-first\",\"items\":[{\"productId\":"
                + product + ",\"quantity\":2}]}";
        long order1 = post("/api/orders/create", cus, first).data().path("orderId").asLong();
        assertEquals(0, post("/api/delivery/orders/" + order1 + "/accept", mgr, null).code(), "站长接首单");
        long item1 = longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", order1);
        assertEquals(0, post("/api/delivery/orders/" + order1 + "/complete", mgr,
                        "{\"collected\":true,\"itemReturns\":[{\"orderItemId\":" + item1
                                + ",\"expected\":2,\"actual\":0,\"reasons\":[]}]}").code(),
                "首单完成配送");
        assertEquals(2, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                        + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customer, station, product), "客户手上应有 2 个桶（权益）");

        // ---- 混合单：要 4 个桶，其中 2 个是旧桶换新水、2 个是本单新买押金桶 ----
        String mixed = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"mixed-second\",\"items\":[{\"productId\":"
                + product + ",\"quantity\":4}]}";
        Api created = post("/api/orders/create", cus, mixed);
        assertEquals(0, created.code(), "混合单下单: " + created);
        long order2 = created.data().path("orderId").asLong();
        assertEquals(0, intOf("SELECT first_barrel_order FROM orders WHERE id=?", order2),
                "已经不是首单（客户手上有桶，要核对回桶）");
        assertEquals(0, new java.math.BigDecimal("60.00")
                        .compareTo(decimalOf("SELECT deposit_amount FROM orders WHERE id=?", order2)),
                "只对缺的 2 个桶收押金（4 − 已有 2），不是 4 个");

        // 完成配送页拿到的默认值：该回 2（旧桶），不是送出 4
        Api detail = get("/api/delivery/orders/" + order2, mgr);
        assertEquals(0, detail.code(), "读订单详情: " + detail);
        com.fasterxml.jackson.databind.JsonNode item = detail.data().path("items").get(0);
        assertEquals(true, item.path("barrelItem").asBoolean(), "桶装水明细必须标成 barrelItem");
        assertEquals(2, item.path("suggestedReturnQty").asInt(),
                "默认回桶数 = 客户手上的旧桶（2），不含本单新买的 2 个押金桶");

        // 想按"送出多少回多少"报 4 个：必须被物理上限拦掉（占用只有 2）
        long item2 = longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", order2);
        assertEquals(0, post("/api/delivery/orders/" + order2 + "/accept", mgr, null).code(), "站长接混合单");
        Api tooMany = post("/api/delivery/orders/" + order2 + "/complete", mgr,
                "{\"collected\":true,\"itemReturns\":[{\"orderItemId\":" + item2
                        + ",\"expected\":4,\"actual\":4,\"reasons\":[]}]}");
        assertNotEquals(0, tooMany.code(), "本单新买押金的桶不能当成回收数（回 4 个必须被拒）: " + tooMany);

        // 按默认值报 2 个：通过，且账要对
        assertEquals(0, post("/api/delivery/orders/" + order2 + "/complete", mgr,
                        "{\"collected\":true,\"itemReturns\":[{\"orderItemId\":" + item2
                                + ",\"expected\":2,\"actual\":2,\"reasons\":[]}]}").code(),
                "按默认值回 2 个应成功");
        assertEquals(0, intOf("SELECT IFNULL(MAX(over_qty),0) FROM customer_barrel_over "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?", customer, station, product),
                "旧桶换新水是等量交换，不该产生欠桶");
        assertEquals(4, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                        + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customer, station, product), "送达后客户手上 4 个桶（2 旧 + 2 新买）");
    }

    @Test
    @DisplayName("瓶装水明细不进回桶计划（桶账判据只认桶装水）")
    void nonBarrelItemHasNoReturnPlan() {
        long station = createStation("瓶装水站");
        long manager = createStaff("瓶装水站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("瓶装水客户", "bottle-only-openid");
        long water = createProduct("混合单瓶装水", 2, "3.00", "0.00", 0, "0.00");        createInventory(station, water, 50);
        long address = createAddress(customer, "瓶装水地址");
        createCustomerStationConfig(customer, station, 1);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        String body = "{\"addressId\":" + address + ",\"stationId\":" + station
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"bottle-only-1\",\"items\":[{\"productId\":"
                + water + ",\"quantity\":2}]}";
        long order = post("/api/orders/create", cus, body).data().path("orderId").asLong();
        assertEquals(0, post("/api/delivery/orders/" + order + "/accept", mgr, null).code(), "接单");

        Api detail = get("/api/delivery/orders/" + order, mgr);
        com.fasterxml.jackson.databind.JsonNode item = detail.data().path("items").get(0);
        assertEquals(false, item.path("barrelItem").asBoolean(),
                "瓶装水不是桶：完成页不该为它画回桶行");
        assertTrue(item.path("suggestedReturnQty").isNull(),
                "瓶装水没有默认回桶数（null），别下发 0 让它看起来像'该回 0 个桶'");

        // 老版本小程序会给瓶装水报回桶 —— 必须给一句看得懂的拒绝，而不是"超过持有数(0)"
        long itemId = longOf("SELECT id FROM order_item WHERE order_id=? ORDER BY id LIMIT 1", order);
        Api rejected = post("/api/delivery/orders/" + order + "/complete", mgr,
                "{\"collected\":true,\"itemReturns\":[{\"orderItemId\":" + itemId
                        + ",\"expected\":2,\"actual\":2,\"reasons\":[]}]}");
        assertNotEquals(0, rejected.code(), "瓶装水报回桶必须被拒");
        assertTrue(rejected.message().contains("不涉及回桶"),
                "拒绝理由要说清是'不涉及回桶'，不能是看不懂的持有数报错：" + rejected.message());
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
