package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站长外派面的两件事：<b>客户画像隔离</b> + <b>含押金/桶权益单的风控</b>（2026-09-18 产品裁定）。
 *
 * <p>产品原话（第一件）：「订单有关的所有信息可查，但<b>没有在本站绑定过（主动选择下单）的客户，
 * 均不能看客户画像</b> —— 因为他没在本站下过单就等于没有本站画像，有也是别站的画像，跨站要隔离。」</p>
 *
 * <p>产品原话（第二件）：「如果产生押金问题，特别提醒站长，一般建议禁止外派直接拒单，因为押金不好划定；
 * 要么就是水站间的欠桶问题。如果不拒单也只能<b>指定水站外派</b>，双方都特别提醒后<b>同意</b>才行。」</p>
 *
 * <p>本类盯五件事：</p>
 * <ol>
 *   <li><b>跨租户列表不下发画像</b>：抢单池（Map 直出）、他站外派（实体直出）、以及站长待分配里
 *       "他站外派给本站"那些行，都不得带 {@code customerName} / {@code customerPhone}；
 *       而订单自身的 {@code receiverName} / {@code receiverPhone} / 地址 / 金额照常下发（配送必需）。
 *       <b>认领之后同样如此</b>：跨站单被接收站受理后，送货的配送员这一侧（待接单 / 配送中 /
 *       历史 / 回桶记录）与站长端员工画像也只带订单快照 —— 否则就是"认领前看不到、认领后就看到了"。</li>
 *   <li><b>本站客户画像端点看不到别站客户</b>：{@code GET /api/customers}、
 *       {@code /api/customers/{id}}、{@code /{id}/profile}、{@code /{id}/assets} 都查不到
 *       "只在别站下过单"的客户；反过来本站站长看得到自己的客户（证明不是一刀切全挡）。</li>
 *   <li><b>线下支付授权端点不能变成"认领别站客户"的后门</b>：它会 {@code ensureExists} 写绑定行，
 *       而绑定行正是 {@code PUT /api/customers/{id}} 的判据 —— 一次调用就能改别站客户的档案。</li>
 *   <li><b>含押金/桶权益的单入不了抢单池</b>：两个入池入口（{@code /outsource} 不带 target、
 *       {@code /station-reject} tryDispatch）都被拒，且订单状态 / 履约站 / 备注一个字都不动；
 *       规则上线前留在池里的历史单，抢单出口也拦（并给出出路）。</li>
 *   <li><b>定向外派要双方确认、普通单不加摩擦</b>：涉押金单未带 {@code riskAcknowledged} 一律拒；
 *       外派方确认 + 接收站"分配配送员"时二次确认，两侧都写进 {@code special_note} 与 {@code audit_log}；
 *       不涉押金/桶权益的单照原样走（入池、抢单、定向外派都不要求确认）。</li>
 * </ol>
 */
@DisplayName("跨站外派 · 客户画像隔离 + 押金/桶权益风控（入池拒绝 / 定向外派双方确认）")
class CrossStationPoolRiskAndProfileIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final String CUSTOMER_NAME = "别站客户甲";
    private static final String CUSTOMER_PHONE = "13800001111";
    private static final String RECEIVER_NAME = "收件人李四";
    private static final String RECEIVER_PHONE = "13900002222";
    private static final String ADDRESS_TEXT = "某小区3栋2单元501";

    private long stationA;      // 归属站（下单站、定价方）
    private long stationB;      // 履约站（抢单/被指定外派的一方）
    private long mgrA;
    private long mgrB;
    private long driverB;
    private long product;
    private long customer;
    private long address;

    private void seed() {
        stationA = createStation("A站");
        stationB = createStation("B站");
        mgrA = createStaff("MA", "STATION_MANAGER", stationA, 1);
        mgrB = createStaff("MB", "STATION_MANAGER", stationB, 1);
        driverB = createStaff("DB", "DELIVERY", stationB, 1);
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 0, "0.00");
        createInventory(stationA, product, 100);
        createInventory(stationB, product, 100);
        customer = createCustomer(CUSTOMER_NAME, "openid-foreign");
        address = createAddress(customer, ADDRESS_TEXT);
    }

    private String tokenA() {
        return staffToken(mgrA, "STATION_MANAGER", stationA);
    }

    private String tokenB() {
        return staffToken(mgrB, "STATION_MANAGER", stationB);
    }

    /* ==================================================================
     *  造数：三种订单形态（判据差异都体现在这三列上）
     * ================================================================== */

    /**
     * <b>涉押金单</b>：本单要收 30 元押金（首单买桶），完成配送时不用核对回桶。
     * 命中判据第 ① 项 {@code orders.deposit_amount > 0}。
     */
    private long pendingDepositOrderAtA() {
        long id = createOrderFull(customer, address, stationA, product,
                1 /* 待配送 */, 1 /* 待收款 */, 2 /* 现金 */,
                "40.00", "30.00", "70.00", false, 2);
        snapshotReceiver(id);
        return id;
    }

    /**
     * <b>涉桶权益单（不收押金）</b>：老客户换水 —— 押金早已交过（{@code deposit_amount = 0}），
     * 但本单要送 2 桶、且不是本站首笔买桶单，完成配送时要核对回桶。
     * 命中判据第 ② 项 {@code delivery_bucket_qty > 0 && first_barrel_order = 0}。
     */
    private long pendingBarrelOrderAtA() {
        long id = createOrderFull(customer, address, stationA, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 2);
        snapshotReceiver(id);
        return id;
    }

    /**
     * <b>普通单</b>：不含桶装水商品（瓶装水/饮水机的形态）→ {@code delivery_bucket_qty = 0}
     * 且 {@code deposit_amount = 0}。产品要求这类单"保持原状"，外派不加任何摩擦。
     */
    private long pendingPlainOrderAtA() {
        long id = createOrderFull(customer, address, stationA, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        snapshotReceiver(id);
        return id;
    }

    private void snapshotReceiver(long orderId) {
        jdbc.update("UPDATE orders SET receiver_name = ?, receiver_phone = ?, address_snapshot = ? WHERE id = ?",
                RECEIVER_NAME, RECEIVER_PHONE, ADDRESS_TEXT, orderId);
    }

    /* ==================================================================
     *  1. 跨租户列表不下发客户画像
     * ================================================================== */

    @Test
    @DisplayName("抢单池：不下发 customerName/customerPhone，但 receiver/地址/金额照常")
    void poolHidesCustomerProfileButKeepsDeliveryInfo() {
        seed();
        long order = pendingPlainOrderAtA();
        // 池中单 = 履约站为空（判定"在池中"的唯一标志）
        jdbc.update("UPDATE orders SET delivery_station_id = NULL, total_amount = 46.00, "
                + "delivery_fee = 5.00, floor_fee = 1.00 WHERE id = ?", order);

        Api res = get("/api/delivery/orders/pool", tokenB());
        assertEquals(0, res.code(), "抢单池应可读: " + res);
        JsonNode row = rowById(res.data(), order);
        assertNotNull(row, "B 站应能在抢单池里看到这一单");

        assertTrue(row.path("customerName").isNull(), "池子是跨租户可见面，不得下发别站客户的姓名");
        assertTrue(row.path("customerPhone").isNull(), "同上，不得下发手机号");
        // 整个响应体里都不该出现该客户的姓名/手机号（防"只改了这一处、别的字段又带出来"）
        assertFalse(res.data().toString().contains(CUSTOMER_NAME),
                "池响应里任何一个字段都不该出现别站客户的姓名: " + res.data());
        assertFalse(res.data().toString().contains(CUSTOMER_PHONE),
                "池响应里任何一个字段都不该出现别站客户的手机号: " + res.data());

        // 订单自身的配送信息与金额照常 —— 那些是"订单有关的信息"，跨站配送必需
        assertEquals(RECEIVER_NAME, row.path("receiverName").asText(), "收件人姓名必须照常下发（配送必需）");
        assertEquals(RECEIVER_PHONE, row.path("receiverPhone").asText(), "收件人电话必须照常下发");
        assertEquals(ADDRESS_TEXT, row.path("addressSnapshot").asText(), "地址快照必须照常下发");
        assertEquals(0, new java.math.BigDecimal("46.00").compareTo(row.path("totalAmount").decimalValue()),
                "金额快照照常下发，实际=" + row.path("totalAmount"));
    }

    @Test
    @DisplayName("他站外派：同一口径（实体直出也必须置 null），金额与去向照常")
    void directedIncomingHidesCustomerProfile() {
        seed();
        long order = createOrderCrossStation(customer, address, stationA, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00");
        snapshotReceiver(order);

        Api res = get("/api/delivery/orders/directed-incoming", tokenB());
        assertEquals(0, res.code(), "他站外派列表应可读: " + res);
        JsonNode row = rowById(res.data(), order);
        assertNotNull(row, "B 站应能看到这一单");

        assertTrue(row.path("customerName").isNull(), "别站客户姓名不得下发");
        assertTrue(row.path("customerPhone").isNull(), "别站客户手机号不得下发");
        assertFalse(res.data().toString().contains(CUSTOMER_NAME),
                "响应里任何字段都不该出现别站客户姓名: " + res.data());
        assertEquals(RECEIVER_NAME, row.path("receiverName").asText());
        assertEquals(RECEIVER_PHONE, row.path("receiverPhone").asText());
        assertEquals(ADDRESS_TEXT, row.path("addressSnapshot").asText());
        assertEquals(0, new java.math.BigDecimal("40.00").compareTo(row.path("totalAmount").decimalValue()));
    }

    /**
     * 站长待分配列表里混着"他站外派给本站"的行（SQL 的 {@code delivery_station_id = 本站} 那一支）——
     * 不堵这里，刚在抢单池/他站外派堵上的口子换个端点就漏。
     * 同时断言**本站自己的单保持原样**（不能一刀切，那是本站客户的画像）。
     */
    @Test
    @DisplayName("待分配：他站外派给我的那行不下发画像，本站自己的单照常显示客户姓名")
    void stationPendingMasksOnlyCrossStationRows() {
        seed();
        long crossOrder = createOrderCrossStation(customer, address, stationA, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00");
        snapshotReceiver(crossOrder);

        // B 站自己的客户与订单（同站单：归属=履约=B）
        long myCustomer = createCustomer("本站客户乙", "openid-mine");
        long myAddress = createAddress(myCustomer, "本站地址");
        long myOrder = createOrderFull(myCustomer, myAddress, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);

        Api res = get("/api/delivery/orders/station-pending", tokenB());
        assertEquals(0, res.code(), "待分配列表应可读: " + res);
        JsonNode crossRow = rowById(res.data(), crossOrder);
        JsonNode myRow = rowById(res.data(), myOrder);
        assertNotNull(crossRow, "他站外派给本站的单应出现在待分配里（接收站要在这里受理）");
        assertNotNull(myRow, "本站自己的待配送单也应在列表里");

        assertTrue(crossRow.path("customerName").isNull(), "跨站行的客户姓名必须抹掉");
        assertTrue(crossRow.path("customerPhone").isNull(), "跨站行的客户手机号必须抹掉");
        assertEquals("本站客户乙", myRow.path("customerName").asText(),
                "本站自己的单必须照常显示客户姓名（一刀切会把本站功能做坏）");
    }

    /**
     * 「认领之后」那一半：跨站单被接收站受理后，就以 {@code delivery_staff_id = 本站配送员} 的形态
     * 出现在配送员的任务 / 历史 / 回桶记录与站长端员工画像里 —— 只在池子与外派两个入口堵，
     * 等于"认领前看不到、认领后就看到了"。
     */
    @Test
    @DisplayName("认领之后：履约站的配送员面只见订单快照；本站自己的单照常显示客户姓名")
    void fulfillmentSideKeepsMaskingAfterClaim() {
        seed();
        String tokenA = tokenA();
        String tokenB = tokenB();
        String driver = staffToken(driverB, "DELIVERY", stationB);

        // 跨站单：A 定向外派给 B → B 分配给自己站的配送员（普通单，全程不加确认摩擦）
        long cross = pendingPlainOrderAtA();
        assertTrue(post("/api/delivery/orders/" + cross + "/dispatch", tokenA,
                "{\"targetStationId\":" + stationB + "}").isSuccess(), "定向外派普通单应成功");
        assertTrue(post("/api/delivery/orders/assign/" + cross, tokenB,
                "{\"deliveryStaffId\":" + driverB + "}").isSuccess(), "接收站分配应成功");

        // ① 配送员待接单
        assertProfileMasked(rowById(get("/api/delivery/orders/assigned-to-me", driver).data(), cross),
                "配送员待接单列表");
        // ② 接单 → 配送中
        assertTrue(post("/api/delivery/orders/" + cross + "/accept", driver, "{}").isSuccess(), "配送员接单应成功");
        assertProfileMasked(rowById(get("/api/delivery/orders/delivering", driver).data(), cross),
                "配送中列表");
        // ③ 回桶记录（同一批 status=2 的单，另一张列表）
        assertProfileMasked(rowById(get("/api/delivery/barrel-records", driver).data(), cross),
                "回桶记录列表");
        // ④ 站长端员工画像的「当前进行中」（Map 直出，靠 SQL 显式 as 出的两列判跨站）
        JsonNode row = rowById(get("/api/staff/" + driverB + "/profile", tokenB).data().path("currentOrders"), cross);
        assertNotNull(row, "员工画像的当前进行中应含这一单");
        assertTrue(row.path("customerName").isNull(), "员工画像里也不得出现别站客户姓名，实际=" + row);
        assertEquals(RECEIVER_NAME, row.path("receiverName").asText(), "收件人快照照常（配送必需）");

        // ⑤ 反向：本站自己的单照常显示客户姓名（防一刀切）
        long myCustomer = createCustomer("本站客户丙", "openid-mine-c");
        long myAddress = createAddress(myCustomer, "本站地址C");
        long mine = createOrderFull(myCustomer, myAddress, stationB, product,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        assertTrue(post("/api/delivery/orders/assign/" + mine, tokenB,
                "{\"deliveryStaffId\":" + driverB + "}").isSuccess(), "本站单分配应成功");
        JsonNode mineRow = rowById(get("/api/delivery/orders/assigned-to-me", driver).data(), mine);
        assertNotNull(mineRow, "本站自己的单应在配送员待接单列表里");
        assertEquals("本站客户丙", mineRow.path("customerName").asText(),
                "本站自己的单必须照常显示客户姓名");
    }

    /* ==================================================================
     *  2. 本站客户画像端点与别站客户
     * ================================================================== */

    @Test
    @DisplayName("越权：只在别站下过单的客户，本站的画像端点一个都查不到；本站自己的客户照常")
    void foreignCustomerIsInvisibleInProfileEndpoints() {
        seed();
        // 该客户的全部关系都在 A 站（一笔订单），与 B 站无绑定、无订单
        createOrderFull(customer, address, stationA, product, 1, 1, 2, "40.00", "0.00", "40.00", false, 0);

        // ① 列表：orders 驱动，B 站一行都不该有
        Api list = get("/api/customers", tokenB());
        assertEquals(0, list.code(), "站长客户列表应可读: " + list);
        assertNullById(list.data(), customer, "别站客户不得出现在本站客户列表里");

        // ②③④ 按 id 查的三个画像端点：一律"不存在或无权查看"
        String[] paths = {
                "/api/customers/" + customer,
                "/api/customers/" + customer + "/profile",
                "/api/customers/" + customer + "/assets"
        };
        for (String p : paths) {
            Api api = get(p, tokenB());
            assertFalse(api.isSuccess(), "别站客户必须查不到: " + p + " → " + api);
        }

        // 反向：归属站 A 自己看得到（证明上面不是"把所有客户都挡了"）
        assertEquals(0, get("/api/customers/" + customer, tokenA()).code(),
                "归属站必须能看自己的客户");
        Api ownerList = get("/api/customers", tokenA());
        assertNotNull(rowById(ownerList.data(), customer), "归属站的客户列表里应有这个客户");
    }

    /**
     * 线下支付授权端点是"别站客户认领后门"：它内部 {@code ensureExists} 会**凭空写一行绑定**，
     * 而绑定行正是 {@code PUT /api/customers/{id}} 的判据 —— 一次 GET 就能让本站拿到改档权限。
     */
    @Test
    @DisplayName("越权：线下支付授权不得给别站客户建绑定（否则能接着改别站客户档案）")
    void offlinePaymentCannotClaimForeignCustomer() {
        seed();
        createOrderFull(customer, address, stationA, product, 1, 1, 2, "40.00", "0.00", "40.00", false, 0);

        Api get1 = get("/api/customers/" + customer + "/offline-payment", tokenB());
        assertFalse(get1.isSuccess(), "别站客户的线下支付配置不得可读: " + get1);
        Api put1 = put("/api/customers/" + customer + "/offline-payment", tokenB(),
                "{\"offlinePaymentEnabled\":1}");
        assertFalse(put1.isSuccess(), "别站客户不得被本站开通货到付款: " + put1);

        // 关键：**不能留下绑定行** —— 它是"本站客户"口径里绑定那一支，也是改档案的钥匙
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_station_config WHERE customer_id=? AND station_id=?",
                customer, stationB), "被拒的请求不得给别站客户建绑定行");

        // 没有绑定行 → 档案修改也必须被拒（PUT 的判据就是这行绑定）
        Api update = put("/api/customers/" + customer, tokenB(), "{\"name\":\"被篡改\",\"phone\":\"13000000000\"}");
        assertFalse(update.isSuccess(), "不得改别站客户的档案: " + update);
        assertEquals(CUSTOMER_NAME, jdbc.queryForObject(
                "SELECT name FROM customer WHERE id=?", String.class, customer), "客户姓名不得被改掉");

        // 反向：客户在本站下过单时，这个端点照常可用（不能把正常路径一起挡掉）
        long myCustomer = createCustomer("本站客户丙", "openid-mine-2");
        long myAddress = createAddress(myCustomer, "本站地址2");
        createOrderFull(myCustomer, myAddress, stationB, product, 1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        Api mine = get("/api/customers/" + myCustomer + "/offline-payment", tokenB());
        assertEquals(0, mine.code(), "本站客户（有本站订单）的线下支付配置必须照常可读: " + mine);
    }

    /* ==================================================================
     *  3. 含押金/桶权益的单禁止进抢单池
     * ================================================================== */

    @Test
    @DisplayName("含押金单入池被拒（code=1）：状态/履约站/备注一个字都没动")
    void depositOrderCannotEnterPool() {
        seed();
        long order = pendingDepositOrderAtA();
        String noteBefore = specialNote(order);

        // 入口一：/outsource 不带 targetStationId = 放入抢单池
        Api out = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}");
        assertFalse(out.isSuccess(), "含押金单不得放入抢单池: " + out);
        assertTrue(out.message().contains("押金/桶权益"), "拒绝文案要点明原因，实际=" + out.message());
        assertTrue(out.message().contains("定向外派"), "文案要给出出路（不是一句干巴巴的'不行'），实际=" + out.message());

        // 入口二：站长拒单时勾"尝试外派"
        Api reject = post("/api/delivery/orders/" + order + "/station-reject", tokenA(),
                "{\"reason\":\"本站送不了\",\"tryDispatch\":true}");
        assertFalse(reject.isSuccess(), "拒单外派也不得把含押金单放进池子: " + reject);

        // 一个字都没动：状态未推、履约站未清、也没有任何 [外派] 痕迹
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "被拒不得改变订单状态");
        assertEquals(stationA, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "被拒不得清空履约站（清空=放进池子）");
        assertEquals(noteBefore, specialNote(order), "被拒不得留任何外派痕迹");
        assertFalse(poolContains(tokenB(), order), "池子里不该有这一单");
    }

    /**
     * 判据是<b>并集</b>：押金单之外，"老客户换水"这类<b>不收押金但要核对回桶</b>的单同样禁止入池
     * —— 那正是产品说的「水站间的欠桶问题」。同时验证池子的**出口**也拦（历史遗留单）。
     */
    @Test
    @DisplayName("桶权益单（不收押金的老客换水单）同样禁止入池；池中历史风险单抢单也被拒")
    void barrelOrderCannotEnterPoolAndClaimIsBlocked() {
        seed();
        long order = pendingBarrelOrderAtA();
        assertEquals(0, decimalOf("SELECT deposit_amount FROM orders WHERE id=?", order)
                .compareTo(java.math.BigDecimal.ZERO), "本用例前提：本单不收押金");
        assertEquals(2, intOf("SELECT delivery_bucket_qty FROM orders WHERE id=?", order), "本用例前提：要送 2 桶");

        Api out = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}");
        assertFalse(out.isSuccess(), "要核对回桶的单不得放入抢单池: " + out);
        assertTrue(out.message().contains("押金/桶权益"), "实际=" + out.message());

        // 池子的出口：模拟"规则上线前已经放进池里的历史单"
        long legacy = pendingBarrelOrderAtA();
        jdbc.update("UPDATE orders SET delivery_station_id = NULL WHERE id = ?", legacy);
        assertTrue(poolContains(tokenB(), legacy), "历史单仍会在池里可见（这正需要出口拦截）");

        Api claim = post("/api/delivery/orders/" + legacy + "/claim-pool", tokenB(),
                "{\"deliveryStaffId\":" + driverB + "}");
        assertFalse(claim.isSuccess(), "池中风险单不得被跨站抢走: " + claim);
        assertTrue(claim.message().contains("押金/桶权益"), "实际=" + claim.message());

        // 未变的证明：仍在池中、状态仍是待配送、没有配送员
        assertEquals("NULL", jdbc.queryForObject(
                        "SELECT IFNULL(delivery_station_id,'NULL') FROM orders WHERE id=?", String.class, legacy),
                "被拒后仍应留在池中（由归属站召回后自行处理）");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", legacy));
        assertEquals("NULL", jdbc.queryForObject(
                        "SELECT IFNULL(delivery_staff_id,'NULL') FROM orders WHERE id=?", String.class, legacy));
    }

    /* ==================================================================
     *  4. 定向外派：双方确认 + 留痕
     * ================================================================== */

    @Test
    @DisplayName("含押金单定向外派：未确认被拒；外派方确认 → 接收站再确认 → 两侧都留痕")
    void depositOrderDirectedDispatchNeedsBothSidesToAcknowledge() {
        seed();
        long order = pendingDepositOrderAtA();
        String tokenA = tokenA();
        String tokenB = tokenB();

        // ① 未确认 → 拒（且不产生任何副作用）
        Api noAck = post("/api/delivery/orders/" + order + "/dispatch", tokenA,
                "{\"targetStationId\":" + stationB + "}");
        assertFalse(noAck.isSuccess(), "涉押金单未确认不得外派: " + noAck);
        assertTrue(noAck.message().contains("押金/桶权益"), "实际=" + noAck.message());
        assertEquals(stationA, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order));
        assertEquals("", specialNote(order), "被拒不得留痕");

        // ② 另一个入口（/outsource 带 targetStationId）同一道闸门 —— 只堵一个等于没堵
        Api otherEntry = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA,
                "{\"targetStationId\":" + stationB + "}");
        assertFalse(otherEntry.isSuccess(), "同义的另一个端点也必须拦: " + otherEntry);

        // ③ 前端提示文案由后端下发（前端不自编）
        Api risk = get("/api/delivery/orders/" + order + "/cross-station-risk", tokenA);
        assertEquals(0, risk.code(), "风险查询应可读: " + risk);
        assertTrue(risk.data().path("depositBarrelRisk").asBoolean(), "本单应被判为风险单");
        assertTrue(risk.data().path("riskNote").asText().contains("押金/桶权益"),
                "风险文案由后端下发，实际=" + risk.data());

        // ④ 外派方确认 → 成功 + 留痕（谁、什么时候、确认了什么）
        Api ok = post("/api/delivery/orders/" + order + "/dispatch", tokenA,
                "{\"targetStationId\":" + stationB + ",\"riskAcknowledged\":true,\"reason\":\"人手不足\"}");
        assertTrue(ok.isSuccess(), "确认后应可外派: " + ok);
        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order));
        assertEquals(stationA, longOf("SELECT station_id FROM orders WHERE id=?", order), "归属站不得改变");
        String afterDispatch = specialNote(order);
        assertTrue(afterDispatch.contains("[外派风险确认]") && afterDispatch.contains("外派方=" + stationA),
                "外派方的确认必须写进 special_note，实际=" + afterDispatch);
        assertEquals(1, intOf("SELECT COUNT(*) FROM audit_log WHERE module='ORDER' AND action='DISPATCH' "
                        + "AND target=? AND user_id=? AND detail LIKE '%riskAcknowledged=true%'",
                "order:" + order, mgrA), "audit_log 要能回答'谁（user_id）在什么时候（create_time）同意的'");

        // ⑤ 接收站未确认 → 拒；确认后成功 + 留痕
        Api assignNoAck = post("/api/delivery/orders/assign/" + order, tokenB,
                "{\"deliveryStaffId\":" + driverB + "}");
        assertFalse(assignNoAck.isSuccess(), "接收站未确认不得受理这一单: " + assignNoAck);
        assertTrue(assignNoAck.message().contains("押金/桶权益"), "实际=" + assignNoAck.message());
        assertEquals("NULL", jdbc.queryForObject(
                        "SELECT IFNULL(delivery_staff_id,'NULL') FROM orders WHERE id=?", String.class, order),
                "被拒不得分配配送员");

        // 接收站也要能拿到同一段后端文案
        Api riskB = get("/api/delivery/orders/" + order + "/cross-station-risk", tokenB);
        assertEquals(0, riskB.code(), "接收站应能查到本单风险: " + riskB);
        assertTrue(riskB.data().path("depositBarrelRisk").asBoolean());

        Api assignOk = post("/api/delivery/orders/assign/" + order, tokenB,
                "{\"deliveryStaffId\":" + driverB + ",\"riskAcknowledged\":true}");
        assertTrue(assignOk.isSuccess(), "接收站确认后应可分配: " + assignOk);
        assertEquals(driverB, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order));
        String afterAssign = specialNote(order);
        // 统一标记 [外派风险确认]，用 role 区分是哪一侧确认的（外派方 / 接收站）：
        // 标记语义只有一个（"确认了押金/桶权益风险"），分两个标记会让"这条单确认到哪一步了"要读两遍。
        assertTrue(afterAssign.contains("[外派风险确认]") && afterAssign.contains("接收站=" + stationB),
                "接收站的确认也必须写进 special_note，实际=" + afterAssign);
        assertEquals(1, intOf("SELECT COUNT(*) FROM audit_log WHERE module='ORDER' AND action='ASSIGN' "
                        + "AND target=? AND user_id=? AND detail LIKE '%riskAcknowledged=true%'",
                "order:" + order, mgrB), "接收站的确认同样要可追溯");
    }

    /* ==================================================================
     *  5. 普通单：一个字都不加（防一刀切）
     * ================================================================== */

    @Test
    @DisplayName("不涉押金/桶权益的普通单：入池、抢单、定向外派全部照常，不要求任何确认")
    void plainOrderOutsourceStillWorks() {
        seed();
        String tokenA = tokenA();
        String tokenB = tokenB();

        // 入池 → 抢单：照原样
        long order = pendingPlainOrderAtA();
        Api risk = get("/api/delivery/orders/" + order + "/cross-station-risk", tokenA);
        assertEquals(0, risk.code());
        assertFalse(risk.data().path("depositBarrelRisk").asBoolean(), "普通单不该被判为风险单");
        assertTrue(risk.data().path("riskNote").isNull(), "普通单不下发风险文案");

        assertTrue(post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA, "{}").isSuccess(),
                "普通单放入抢单池应照常成功");
        assertEquals("NULL", jdbc.queryForObject(
                "SELECT IFNULL(delivery_station_id,'NULL') FROM orders WHERE id=?", String.class, order));
        assertTrue(post("/api/delivery/orders/" + order + "/claim-pool", tokenB,
                "{\"deliveryStaffId\":" + driverB + "}").isSuccess(), "普通单抢单应照常成功");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "抢单即接单");

        // 定向外派：不带 riskAcknowledged 也照常成功，且不应出现任何"风险确认"痕迹
        long order2 = pendingPlainOrderAtA();
        Api dispatched = post("/api/delivery/orders/" + order2 + "/dispatch", tokenA,
                "{\"targetStationId\":" + stationB + "}");
        assertTrue(dispatched.isSuccess(), "普通单定向外派不该要求确认: " + dispatched);
        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order2));
        assertFalse(specialNote(order2).contains("[外派风险确认]"), "普通单不该写风险确认留痕");

        // 接收站分配：普通单也不要求确认
        assertTrue(post("/api/delivery/orders/assign/" + order2, tokenB,
                "{\"deliveryStaffId\":" + driverB + "}").isSuccess(), "普通单接收站分配不该要求确认");
    }

    /* ==================================================================
     *  小工具
     * ================================================================== */

    private String specialNote(long orderId) {
        String s = jdbc.queryForObject("SELECT IFNULL(special_note,'') FROM orders WHERE id=?", String.class, orderId);
        return s == null ? "" : s;
    }

    /** 断言一行订单只带订单快照：{@code customerName} / {@code customerPhone} 为空，收件人姓名电话照常。 */
    private void assertProfileMasked(JsonNode row, String where) {
        assertNotNull(row, where + "：这一行应当存在");
        assertTrue(row.path("customerName").isNull(),
                where + "：不得下发 customerName，实际=" + row.path("customerName"));
        assertTrue(row.path("customerPhone").isNull(),
                where + "：不得下发 customerPhone，实际=" + row.path("customerPhone"));
        assertEquals(RECEIVER_NAME, row.path("receiverName").asText(), where + "：收件人姓名照常（配送必需）");
        assertEquals(RECEIVER_PHONE, row.path("receiverPhone").asText(), where + "：收件人电话照常（配送必需）");
    }

    private boolean poolContains(String token, long orderId) {
        Api res = get("/api/delivery/orders/pool", token);
        assertEquals(0, res.code(), "抢单池应可读: " + res);
        return rowById(res.data(), orderId) != null;
    }

    /** 在数组型响应里按 id 找一行；找不到返回 null（调用方用 assertNotNull/assertNull 表达期望）。 */
    private JsonNode rowById(JsonNode array, long id) {
        if (array == null || !array.isArray()) return null;
        for (JsonNode n : array) {
            if (n.path("id").asLong() == id) return n;
        }
        return null;
    }

    private void assertNullById(JsonNode array, long id, String message) {
        JsonNode row = rowById(array, id);
        assertTrue(row == null, message + "（实际=" + (row == null ? "无" : row.toString()) + "）");
    }
}
