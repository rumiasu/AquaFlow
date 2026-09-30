package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-14（台账 {@code docs/audit/项目健康度台账.md} §4 P2）零覆盖端点契约 · <b>顾客侧两条</b>。
 *
 * <p>被钉住的两条端点在补本用例之前，{@code src/test/**} 里<b>一次都没有被调用过</b>
 * （按路径常量 grep 的零覆盖证据见台账 F-14 与本次交付报告）。</p>
 *
 * <p><b>为什么零覆盖要按「失败可区分」写</b>（AGENTS §8.22）：只读统计端点的真实故障形态不是崩溃，
 * 而是<b>界面空白</b> —— mapper JOIN 写错不抛异常、只返回空值，"今天没有单"与"确实查不到"长得一模一样。
 * 所以每条端点都刻意把三种形态分开断言：</p>
 * <ol>
 *   <li><b>正例</b>：{@code code=0} + 返回值能在库里逐列对上（不是只看返回码）；</li>
 *   <li><b>失败</b>：被拒/查不到要给 {@code code=1} + 具体文案，<b>不能</b>退化成 {@code code=0} 的空数据；</li>
 *   <li><b>合法空态</b>：真的没有服务水站时是 {@code code=0 + data:null}，与"失败"必须分得开；</li>
 *   <li><b>越权</b>：身份只认登录态，请求参数里塞别人的 id 不生效。</li>
 * </ol>
 *
 * <p>断言语义一律看 body {@code code}（业务错误 HTTP 仍是 200），只有"未认证"才是真 401。</p>
 */
@DisplayName("F-14 · 顾客侧零覆盖只读端点（/api/customers/stats、/api/orders/my-station）")
class CustomerStatsAndMyStationIntegrationTest extends AbstractIntegrationTest {

    private static final String STATS = "/api/customers/stats";

    private static final String MY_STATION = "/api/orders/my-station";

    /* ==================================================================
     * 端点 1：GET /api/customers/stats（CustomerController#getStats）
     * 顾客查自己的「首次下单 / 最近配送」两个时间戳。
     * ================================================================== */

    @Test
    @DisplayName("stats 正例：下发值就是库里那两列（落库断言），且两个字段恒存在")
    void statsReturnsPersistedTimestamps() {
        long customer = createCustomer("统计客户", "f14-stats-openid");
        LocalDateTime firstOrder = LocalDateTime.of(2026, 3, 4, 9, 15, 0);
        LocalDateTime lastDelivery = LocalDateTime.of(2026, 3, 5, 18, 30, 0);
        jdbc.update("UPDATE customer SET first_order_time=?, last_delivery_time=? WHERE id=?",
                firstOrder, lastDelivery, customer);

        // 先确认"库里的事实"确实是我们要断言的那两个值 —— 否则下面的断言只是在自证夹具
        assertEquals(firstOrder, jdbc.queryForObject(
                        "SELECT first_order_time FROM customer WHERE id=?", LocalDateTime.class, customer),
                "落库结果：first_order_time 必须是夹具写进去的那个值");
        assertEquals(lastDelivery, jdbc.queryForObject(
                        "SELECT last_delivery_time FROM customer WHERE id=?", LocalDateTime.class, customer),
                "落库结果：last_delivery_time 必须是夹具写进去的那个值");

        Api res = get(STATS, customerToken(customer));

        assertEquals(0, res.code(), "顾客查自己的统计应成功：" + res);
        assertTrue(res.data().has("firstOrderTime"),
                "字段必须恒存在（AQ-044 起 null 也下发，不是键缺失，否则前端 res.data.data 变 undefined）：" + res.data());
        assertTrue(res.data().has("lastDeliveryTime"), "同上：" + res.data());
        assertEquals(firstOrder, LocalDateTime.parse(res.data().path("firstOrderTime").asText()),
                "下发值必须等于库里那一列，而不是被兜成 null/当前时间：" + res.data());
        assertEquals(lastDelivery, LocalDateTime.parse(res.data().path("lastDeliveryTime").asText()),
                "同上：" + res.data());
    }

    @Test
    @DisplayName("stats 失败可区分：员工被拒 / 客户不存在，都是 code=1+具体文案，不是 code=0 空数据")
    void statsFailureIsDistinguishableFromEmptyData() {
        long station = createStation("统计站");
        long manager = createStaff("统计站长", "STATION_MANAGER", station, 1);

        // ① 员工调顾客端点 → 明拒。若这里回 code=0，界面就是"这个客户没有数据"，排查时无从下手
        Api asStaff = get(STATS, staffToken(manager, "STATION_MANAGER", station));
        assertEquals(1, asStaff.code(), "员工不是客户，必须被拒而不是拿到空统计：" + asStaff);
        assertEquals("仅客户可访问此接口", asStaff.message(),
                "拒绝要给可读的具体文案，不能只说「系统错误」：" + asStaff);
        assertTrue(asStaff.data().isNull(), "被拒时不得下发 data：" + asStaff);

        // ② token 合法但客户行不存在 → 与"这个客户还没有统计"必须分得开
        Api ghost = get(STATS, customerToken(999_999L));
        assertEquals(1, ghost.code(), "客户不存在必须是明确失败，不能回 code=0 + 全 null（§8.22）：" + ghost);
        assertTrue(ghost.message().contains("客户不存在"), "文案要指得出是客户不存在：" + ghost);

        // ③ 未认证是本仓唯一返回真 401 的场景
        assertEquals(401, get(STATS, null).status(), "无令牌应被 AuthInterceptor 拒成真 401");
    }

    @Test
    @DisplayName("stats 越权隔离：身份只认登录态，别人的数据与参数里的 customerId 都不认")
    void statsIdentityComesFromTokenOnly() {
        long alice = createCustomer("统计甲", "f14-stats-alice");
        long bob = createCustomer("统计乙", "f14-stats-bob");
        LocalDateTime aliceFirst = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        LocalDateTime bobFirst = LocalDateTime.of(2026, 2, 2, 20, 0, 0);
        jdbc.update("UPDATE customer SET first_order_time=? WHERE id=?", aliceFirst, alice);
        jdbc.update("UPDATE customer SET first_order_time=? WHERE id=?", bobFirst, bob);

        Api asAlice = get(STATS, customerToken(alice));
        assertEquals(0, asAlice.code(), "甲应能读自己的统计：" + asAlice);
        assertEquals(aliceFirst, LocalDateTime.parse(asAlice.data().path("firstOrderTime").asText()),
                "甲拿到的必须是甲自己的值");

        Api asBob = get(STATS, customerToken(bob));
        assertEquals(bobFirst, LocalDateTime.parse(asBob.data().path("firstOrderTime").asText()),
                "乙拿到的必须是乙自己的值（不能被甲的 token 串味）");

        // 请求参数里塞别人的 id 一律无效：身份取自 AuthContext（RequireRoleAspect 类注释的强制约定）
        Api spoof = get(STATS + "?customerId=" + alice, customerToken(bob));
        assertEquals(0, spoof.code(), "带无关查询参数不该报错：" + spoof);
        assertEquals(bobFirst, LocalDateTime.parse(spoof.data().path("firstOrderTime").asText()),
                "端点不读请求参数里的 customerId —— 读了就是一条可枚举他人消费数据的越权链");
    }

    /* ==================================================================
     * 端点 2：GET /api/orders/my-station（OrderController#getMyLatestStation）
     * 「我当前的服务水站」：优先最近一笔订单的归属站，新客户回退到绑定关系。
     * ================================================================== */

    @Test
    @DisplayName("my-station 正例：返回库里最近一笔订单的归属站（含站名落库断言）")
    void myStationReturnsLatestOrderStation() {
        long oldStation = createStation("F14 旧站");
        long newStation = createStation("F14 新站");
        long customer = createCustomer("F14 老客户", "f14-mystation-openid");
        long product = createProduct("F14 水", 1, "20.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "F14 小区 1 号");

        long oldOrder = createOrder(customer, address, oldStation, product, 1, 1);
        long newOrder = createOrder(customer, address, newStation, product, 1, 1);
        // orders.create_time 默认 CURRENT_TIMESTAMP，同秒插入会让「最近一笔」失去判据 —— 显式错开两天
        jdbc.update("UPDATE orders SET create_time=? WHERE id=?", LocalDateTime.now().minusDays(2), oldOrder);
        jdbc.update("UPDATE orders SET create_time=? WHERE id=?", LocalDateTime.now().minusDays(1), newOrder);

        // 落库断言锚点：库里"这个客户最近一笔订单的归属站"
        long dbLatestStation = longOf(
                "SELECT o.station_id FROM orders o WHERE o.customer_id=? ORDER BY o.create_time DESC, o.id DESC LIMIT 1",
                customer);
        assertEquals(newStation, dbLatestStation, "落库结果：最近一笔必须是新站那单，否则下面的断言没有意义");

        Api res = get(MY_STATION, customerToken(customer));

        assertEquals(0, res.code(), "查自己的服务水站应成功：" + res);
        assertEquals(dbLatestStation, res.data().path("stationId").asLong(),
                "必须下发库里最近一笔订单的归属站，而不是更早那笔：" + res.data());
        assertEquals(jdbc.queryForObject("SELECT name FROM station WHERE id=?", String.class, newStation),
                res.data().path("stationName").asText(),
                "站名必须来自 station 表那一行（JOIN 写错时这里会是 null 而不是报错，见 §8.22）：" + res.data());
        assertNotEquals(oldStation, res.data().path("stationId").asLong(), "不能返回更早那笔的旧站");
    }

    @Test
    @DisplayName("my-station 失败可区分：绑定回退有效、「确实没有」是 data=null、员工被明拒")
    void myStationDistinguishesEmptyFromFailure() {
        long boundStation = createStation("F14 绑定站");
        long newCustomer = createCustomer("F14 新客户", "f14-mystation-new");
        // 新客户还没下过单，但水站已把它纳入管辖（绑定关系）
        createCustomerStationConfig(newCustomer, boundStation, 1);

        Api fallback = get(MY_STATION, customerToken(newCustomer));
        assertEquals(0, fallback.code(), "没下过单的新客户应走绑定回退：" + fallback);
        assertEquals(boundStation, fallback.data().path("stationId").asLong(),
                "库里明明有绑定关系却答 null，就是「界面空白」那条坑：首页会退化成「请选择服务水站」");
        assertEquals(jdbc.queryForObject("SELECT name FROM station WHERE id=?", String.class, boundStation),
                fallback.data().path("stationName").asText(),
                "回退分支的站名也要真下发：" + fallback.data());

        // 既没订单也没绑定 = 「确实没有」：合法空态，必须与"失败"可区分
        long lonely = createCustomer("F14 孤客", "f14-mystation-lonely");
        Api empty = get(MY_STATION, customerToken(lonely));
        assertEquals(0, empty.code(), "「没有服务水站」是合法状态，不是错误：" + empty);
        assertTrue(empty.data().isNull(),
                "合法空态必须是 data=null（不是空对象/空串，否则前端判不出「确实没有」）：" + empty);

        // 员工调顾客端点 → 明拒，而不是"这个员工没有服务水站"
        long manager = createStaff("F14 站长", "STATION_MANAGER", boundStation, 1);
        Api asStaff = get(MY_STATION, staffToken(manager, "STATION_MANAGER", boundStation));
        assertEquals(1, asStaff.code(), "员工不该能调顾客端点：" + asStaff);
        assertEquals("仅客户可访问此接口", asStaff.message(), "拒绝文案要具体：" + asStaff);
        assertTrue(asStaff.data().isNull(), "被拒时不得下发 data（否则与空态混淆）：" + asStaff);

        assertEquals(401, get(MY_STATION, null).status(), "无令牌应是真 401");
    }

    @Test
    @DisplayName("my-station 越权隔离：不串别站订单；跨站外派单的服务站是归属站（定价方）")
    void myStationDoesNotLeakOtherCustomersStation() {
        long stationA = createStation("F14 甲站");
        long stationB = createStation("F14 乙站");
        long product = createProduct("F14 隔离水", 1, "20.00", "30.00", 0, "0.00");

        long alice = createCustomer("F14 隔离甲", "f14-iso-alice");
        long aliceAddr = createAddress(alice, "F14 甲小区 1 号");
        long bob = createCustomer("F14 隔离乙", "f14-iso-bob");
        long bobAddr = createAddress(bob, "F14 乙小区 1 号");

        long aliceOrder = createOrder(alice, aliceAddr, stationA, product, 1, 1);
        long bobOrder = createOrder(bob, bobAddr, stationB, product, 1, 1);
        jdbc.update("UPDATE orders SET create_time=? WHERE id=?", LocalDateTime.now().minusDays(2), aliceOrder);
        // 乙那笔**更新**：如果查询漏了 customer_id 过滤，甲就会被告知"服务水站是乙的站"
        jdbc.update("UPDATE orders SET create_time=? WHERE id=?", LocalDateTime.now().minusDays(1), bobOrder);

        Api asAlice = get(MY_STATION, customerToken(alice));
        assertEquals(0, asAlice.code(), "甲应能查到自己的站：" + asAlice);
        assertEquals(stationA, asAlice.data().path("stationId").asLong(),
                "甲的单虽然更早，但只能看自己的单 —— 答成乙的站就是跨租户泄露");

        Api spoof = get(MY_STATION + "?customerId=" + bob, customerToken(alice));
        assertEquals(stationA, spoof.data().path("stationId").asLong(),
                "请求参数里的 customerId 不参与身份判定（身份一律取 AuthContext）：" + spoof.data());

        // 跨站外派单：顾客看到的「服务水站」必须是**归属站**（客户选定 = 定价方），不是履约站。
        // 判据见 AGENTS §1.1：orders.station_id = 归属站（DeliveryFeeUtil 按它算费）。
        long deliveryStation = createStation("F14 丙履约站");
        long carol = createCustomer("F14 隔离丙", "f14-iso-carol");
        long carolAddr = createAddress(carol, "F14 丙小区 1 号");
        createOrderCrossStation(carol, carolAddr, stationA, deliveryStation, product, 1, 1, 2,
                "40.00", "60.00", "100.00");

        Api asCarol = get(MY_STATION, customerToken(carol));
        assertEquals(0, asCarol.code(), "跨站外派单的客户也应查得到：" + asCarol);
        assertEquals(stationA, asCarol.data().path("stationId").asLong(),
                "归属站（station_id）才是客户的服务水站与定价方；答成履约站会让客户端选错定价站");
        assertNotEquals(deliveryStation, asCarol.data().path("stationId").asLong(),
                "履约站只在履约期间起作用，不是「我的服务水站」");
        assertFalse(asCarol.data().isNull(), "有订单就不能是空态：" + asCarol.data());
    }
}
