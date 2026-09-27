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
 * 配送端列表卡的<b>逐明细摘要投影</b>（{@code itemSummary} / {@code itemKindCount}）+ 列表里的楼层/电梯。
 *
 * <p><b>为什么必须有这个用例</b>：本波次只加了 5 处 MyBatis <b>注解 SQL</b> 的投影列，而注解 SQL
 * <b>没有编译期校验</b>（不是 XML、不是实体映射）—— 拼错一个列名 / 别名撞车 / {@code case} 写错，
 * 编译与其余用例都能全绿，只有真的把这条 SQL 打到 MySQL 上才会暴露。所以本类按"最小可用
 * 混合单 → 走真实 HTTP → 断言下发内容"的形状钉住它。</p>
 *
 * <p><b>被钉住的口径</b>：卡片原先拿 {@code firstProductName × quantity} 渲染，而
 * {@code quantity} 是<b>全单总件数</b>（含瓶装水/饮水器）、{@code firstProductName} 只取第一条明细 ——
 * 3 桶纯净水 + 1 瓶矿泉水会显示成「纯净水 × 4桶」：<b>商品名错、单位也错</b>。
 * 单位判据正本是 {@code util/BarrelScope}（{@code product.category}：1 桶装水 / 2 瓶装水 / 3 饮水器），
 * 各列表 SQL 里的 {@code case} 是它的镜像。</p>
 *
 * <p>⚠️ 本类只<b>读</b>列表，不动任何状态/金额/库存 —— 投影列纯展示。</p>
 */
@DisplayName("配送列表投影 · itemSummary / itemKindCount / 楼层电梯")
class DeliveryItemProjectionIntegrationTest extends AbstractIntegrationTest {

    /** 桶装水品类（与 {@code BarrelScope.CATEGORY_BARREL} / {@code product.category} 同源）。 */
    private static final int CATEGORY_BARREL = 1;
    /** 瓶装水品类：**不进桶账**，但它必须出现在摘要里且单位是「瓶」。 */
    private static final int CATEGORY_BOTTLE = 2;

    /** 在一张**已经存在**的订单下追加一条明细，返回明细 id。 */
    private long addItem(long orderId, long productId, String name, int qty, int category) {
        return createOrderItem(orderId, productId, name, qty, "10.00",
                category == CATEGORY_BARREL ? "30.00" : "0.00", category);
    }

    /** 配送员「派给我的」（= 首页「待配送」页签的数据源）。 */
    private Api assignedToMe(String token) {
        return get("/api/delivery/orders/assigned-to-me", token);
    }

    /** 按 id 取出列表里那一行；取不到直接失败（免得后面全是 NPE 式的误报）。 */
    private JsonNode rowOf(Api api, long orderId) {
        assertEquals(0, api.code(), "列表端点应成功: " + api);
        for (JsonNode n : api.data()) {
            if (n.path("id").asLong() == orderId) {
                return n;
            }
        }
        throw new AssertionError("列表里没有订单 " + orderId + "：实际=" + api.data());
    }

    /**
     * 混合单（桶装水 + 瓶装水）在配送员「派给我的」列表里的投影。
     *
     * <p>断言三件事：① 两种商品名都在；② 各自带<b>自己的</b>单位（桶 / 瓶）与<b>自己的</b>数量
     * （2 桶、1 瓶）—— 不是"全单 3 件"；③ {@code itemKindCount} 是种类数 2，不是件数 3。</p>
     */
    @Test
    @DisplayName("混合单：itemSummary 同时含两种商品名与各自单位，itemKindCount=种类数")
    void mixedOrderSummaryCarriesEveryItemWithItsOwnUnit() {
        long station = createStation("投影混合站");
        long manager = createStaff("投影混合站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("投影混合配送员", "DELIVERY", station, 1);
        long customer = createCustomer("投影混合客户", "projection-mixed-openid");
        long barrel = createProduct("投影纯净水", CATEGORY_BARREL, "10.00", "30.00", 0, "0.00");
        long bottle = createProduct("投影矿泉水", CATEGORY_BOTTLE, "3.00", "0.00", 0, "0.00");
        long address = createAddress(customer, "投影混合地址");
        // 楼层/电梯：客户确认过 6 楼、无电梯 —— 配送员出车前要知道要不要爬楼
        jdbc.update("UPDATE address SET floor = 6, has_elevator = 0 WHERE id = ?", address);
        createInventory(station, barrel, 20);
        createInventory(station, bottle, 20);

        // 现金单（下单即待收款）：把"钱到没到"那条推送闸门与本用例要盯的投影解耦
        long order = createOrderFull(customer, address, station, barrel, 1, 1, 2,
                "23.00", "60.00", "83.00", true, 2);
        addItem(order, barrel, "投影纯净水", 2, CATEGORY_BARREL);
        addItem(order, bottle, "投影矿泉水", 1, CATEGORY_BOTTLE);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", delivery, order);
        // orders.quantity 是**全单总件数**（含瓶装水），正是旧渲染出错的那一列：这里如实写成 3
        jdbc.update("UPDATE orders SET quantity=3 WHERE id=?", order);

        String del = staffToken(delivery, "DELIVERY", station);
        Api mine = assignedToMe(del);
        JsonNode row = rowOf(mine, order);

        String summary = row.path("itemSummary").asText("");
        assertTrue(summary.contains("投影纯净水"), "摘要必须含桶装水商品名: " + mine);
        assertTrue(summary.contains("投影矿泉水"), "摘要必须含瓶装水商品名（旧渲染只剩第一条明细）: " + mine);
        assertTrue(summary.contains("2桶"), "桶装水要带自己的数量与单位「2桶」: " + summary);
        assertTrue(summary.contains("1瓶"), "瓶装水要带自己的数量与单位「1瓶」: " + summary);
        // 反向锁：旧渲染的产物是"首个商品名 × 全单件数" →「投影纯净水 3桶」，这里必须不出现
        assertFalse(summary.contains("3桶"), "不得把全单件数摊到桶装水上（旧渲染的错法）: " + summary);
        assertEquals(2, row.path("itemKindCount").asInt(),
                "itemKindCount 是种类数（2 种），不是件数: " + mine);
        assertEquals(3, row.path("quantity").asInt(), "orders.quantity 仍是全单总件数，本波次没有改它: " + mine);

        // 楼层/电梯：本端点改动前恒为 null（只有"配送中"那几个列表带）—— 现在也要有
        assertEquals(6, row.path("addressFloor").asInt(), "「派给我的」列表也要下发楼层: " + mine);
        assertEquals(0, row.path("addressHasElevator").asInt(), "无电梯要如实下发 0（三态不能塌）: " + mine);

        // 同一张单在「配送中」「今日完成」两个列表里的摘要是同一份口径
        jdbc.update("UPDATE orders SET status=2 WHERE id=?", order);
        JsonNode delivering = rowOf(get("/api/delivery/orders/delivering", del), order);
        assertEquals(summary, delivering.path("itemSummary").asText(""),
                "「配送中」列表的摘要必须与「派给我的」逐字一致（同一个投影，两处口径不许分叉）");
        assertEquals(2, delivering.path("itemKindCount").asInt());
        assertEquals(6, delivering.path("addressFloor").asInt());

        jdbc.update("UPDATE orders SET status=4 WHERE id=?", order);
        JsonNode completed = rowOf(get("/api/delivery/orders/completed-today", del), order);
        assertEquals(summary, completed.path("itemSummary").asText(""), "「今日完成」列表同口径");
        assertEquals(2, completed.path("itemKindCount").asInt());
        assertEquals(6, completed.path("addressFloor").asInt(),
                "「今日完成」补录/复盘时同样要知道客户在哪层");
    }

    /**
     * 单一商品单不许**退化**成"首个商品名 × 全单件数"。
     *
     * <p>形状取"数量与全单件数故意不等"：{@code orders.quantity} 写 3（含一件瓶装水），
     * 而桶装水明细只有 2 桶。旧渲染会给出「投影纯净水 3桶」，摘要必须是「投影纯净水 2桶」。</p>
     */
    @Test
    @DisplayName("单一商品单：数量取明细自己的数量，不拿全单件数冒充")
    void singleProductOrderSummaryUsesItemQuantityNotOrderTotal() {
        long station = createStation("投影单商品站");
        long manager = createStaff("投影单商品站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("投影单商品配送员", "DELIVERY", station, 1);
        long customer = createCustomer("投影单商品客户", "projection-single-openid");
        long barrel = createProduct("投影单商品水", CATEGORY_BARREL, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "投影单商品地址");
        createInventory(station, barrel, 20);

        long order = createOrderFull(customer, address, station, barrel, 1, 1, 2,
                "20.00", "60.00", "80.00", true, 2);
        addItem(order, barrel, "投影单商品水", 2, CATEGORY_BARREL);
        jdbc.update("UPDATE orders SET delivery_staff_id=?, quantity=3 WHERE id=?", delivery, order);

        Api mine = assignedToMe(staffToken(delivery, "DELIVERY", station));
        JsonNode row = rowOf(mine, order);

        assertEquals("投影单商品水 2桶", row.path("itemSummary").asText(""),
                "单一明细的摘要 = 商品名 + 空格 + 明细数量 + 单位（不是全单件数）: " + mine);
        assertEquals(1, row.path("itemKindCount").asInt(), "只有一条明细 → 种类数 1: " + mine);
    }

    /**
     * 站长侧两个「未分配」列表的投影：{@code /orders/pending}（本站未分配）与
     * {@code /orders/station-pending}（待分配，含他站定向外派给本站的单）。
     *
     * <p>这两张列表改动前<b>都没有</b>楼层/电梯（只有配送员的"配送中"带），站长分派时看不到
     * 哪单要爬楼。断言"未填 ≠ 0"这条三态口径也一并钉住：未填时必须是 {@code null}。</p>
     */
    @Test
    @DisplayName("站长未分配列表：摘要 + 楼层；未填楼层必须是 null 不是 0")
    void stationPendingListsCarrySummaryAndFloor() {
        long station = createStation("投影站长站");
        long manager = createStaff("投影站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("投影站长客户", "projection-manager-openid");
        long barrel = createProduct("投影站长水", CATEGORY_BARREL, "10.00", "30.00", 0, "0.00");
        long bottle = createProduct("投影站长瓶", CATEGORY_BOTTLE, "3.00", "0.00", 0, "0.00");
        long address = createAddress(customer, "投影站长地址");
        createInventory(station, barrel, 20);
        createInventory(station, bottle, 20);

        long order = createOrderFull(customer, address, station, barrel, 1, 1, 2,
                "23.00", "60.00", "83.00", true, 2);
        addItem(order, barrel, "投影站长水", 2, CATEGORY_BARREL);
        addItem(order, bottle, "投影站长瓶", 1, CATEGORY_BOTTLE);
        jdbc.update("UPDATE orders SET delivery_staff_id=NULL, quantity=3 WHERE id=?", order);
        jdbc.update("UPDATE address SET floor=3, has_elevator=1 WHERE id=?", address);

        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // ① 本站未分配（GET /api/delivery/orders/pending）
        JsonNode pending = rowOf(get("/api/delivery/orders/pending", mgr), order);
        assertTrue(pending.path("itemSummary").asText("").contains("投影站长水 2桶"),
                "待分配列表要带摘要: " + pending);
        assertTrue(pending.path("itemSummary").asText("").contains("投影站长瓶 1瓶"));
        assertEquals(2, pending.path("itemKindCount").asInt());
        assertEquals(3, pending.path("addressFloor").asInt(), "待分配列表改动前恒为 null: " + pending);
        assertEquals(1, pending.path("addressHasElevator").asInt());

        // ② 待分配（GET /api/delivery/orders/station-pending，混着他站定向外派给本站的单）
        Api stationPending = get("/api/delivery/orders/station-pending", mgr);
        JsonNode sp = rowOf(stationPending, order);
        assertEquals(pending.path("itemSummary").asText(""), sp.path("itemSummary").asText(""),
                "两张列表的摘要是同一份口径");
        assertEquals(2, sp.path("itemKindCount").asInt());
        assertEquals(3, sp.path("addressFloor").asInt());

        // ③ 三态：客户没确认过电梯 → 下发的必须是 null（塌成 0 等于替客户答"无电梯"，
        //    与后端"拿不准就不收楼层费"的口径相反）
        jdbc.update("UPDATE address SET floor=NULL, has_elevator=NULL WHERE id=?", address);
        JsonNode unknown = rowOf(get("/api/delivery/orders/pending", mgr), order);
        assertTrue(unknown.path("addressFloor").isNull(), "未填楼层应下发 null: " + unknown);
        assertTrue(unknown.path("addressHasElevator").isNull(), "未确认电梯应下发 null: " + unknown);
        assertNotNull(unknown.path("itemSummary").asText(""),
                "楼层缺失不影响摘要（两条投影互不依赖）");
    }

    /**
     * 认不出品类的明细回落「件」，且**商品已不在 product 表**时摘要不能整行变 null。
     *
     * <p>{@code left join product} 是刻意的：明细里存的是 {@code product_name_snapshot}（下单快照），
     * 商品后来被下架/删除不该让配送员看不到"这单送的是什么"。品类读不到（{@code p2.category} 为
     * null）时 {@code case} 落 {@code else '件'}，与 {@code BarrelScope} 的
     * "拿不准就当不是桶"同一个态度。</p>
     */
    @Test
    @DisplayName("品类认不出 / 商品不在库里：摘要仍然出得来，单位回落「件」")
    void unknownCategoryFallsBackToGenericUnit() {
        long station = createStation("投影回落站");
        long manager = createStaff("投影回落站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("投影回落客户", "projection-fallback-openid");
        long barrel = createProduct("投影回落水", CATEGORY_BARREL, "10.00", "30.00", 0, "0.00");
        long gadget = createProduct("投影纸巾", 9, "5.00", "0.00", 0, "0.00");
        long address = createAddress(customer, "投影回落地址");

        long order = createOrderFull(customer, address, station, barrel, 1, 1, 2,
                "11.00", "0.00", "11.00", false, 0);
        addItem(order, barrel, "投影回落水", 1, CATEGORY_BARREL);
        // 品类是库里存在的"其它值"（9）：case 走 else → 「件」
        addItem(order, gadget, "投影纸巾", 3, 9);
        // 明细存在、但商品行已不在 product 表（left join 拿不到 p2.category，同样是 null）：
        // snapshot 是真相源，摘要必须照常出，不能因为商品被删就整行变 null
        addItem(order, 999999L, "已下架的老商品", 2, 9);
        jdbc.update("UPDATE orders SET delivery_staff_id=NULL WHERE id=?", order);

        Api pending = get("/api/delivery/orders/pending", staffToken(manager, "STATION_MANAGER", station));
        JsonNode row = rowOf(pending, order);
        String summary = row.path("itemSummary").asText("");
        assertTrue(summary.contains("投影回落水 1桶"), "同单的正常明细不受影响: " + summary);
        assertTrue(summary.contains("投影纸巾 3件"), "品类认不出（category=9）→ 单位回落「件」: " + summary);
        assertTrue(summary.contains("已下架的老商品 2件"),
                "商品不在库里（left join 拿到 null 品类）→ 用明细快照名 + 单位「件」: " + summary);
        assertEquals(3, row.path("itemKindCount").asInt(), "count(*) 只看 order_item，不依赖 product: " + pending);
    }
}
