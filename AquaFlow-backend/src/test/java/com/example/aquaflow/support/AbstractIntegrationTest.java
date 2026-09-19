package com.example.aquaflow.support;

import com.example.aquaflow.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 集成测试基类 —— 真实 Spring 上下文 + 真实 MySQL + 真实 HTTP。
 *
 * <p>三条不妥协的原则：</p>
 * <ol>
 *   <li><b>不用 Mock</b>：事务、CAS、唯一键、行锁这些正是被测对象，Mock 会把它们全掩盖掉。</li>
 *   <li><b>不碰真实库</b>：每个用例前断言库名含 {@code test}，不符直接抛异常中止，
 *       杜绝误 TRUNCATE 生产库 {@code aquaflow}。</li>
 *   <li><b>真 token</b>：用 {@link JwtUtil} 现签 JWT，走真实的 AuthInterceptor + RequireRoleAspect。</li>
 * </ol>
 *
 * <p>本机无 Docker，Testcontainers 不可用，故按任务书降级为「独立可重建的测试库」方案：
 * 先跑 {@code scripts/provision-test-db.sh} 从 {@code sql/schema.sql} 重建 {@code aquaflow_test}。</p>
 *
 * <p>profile 同时激活 {@code local}（提供数据源凭据与 JWT 密钥，该文件已 gitignore）
 * 与 {@code test}（把数据源指向测试库）。顺序不可颠倒：后声明的 profile 覆盖前者。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
public abstract class AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JwtUtil jwtUtil;

    @Autowired
    protected JdbcTemplate jdbc;

    protected final ObjectMapper om = new ObjectMapper();

    /** 每个用例开始前清空测试库全部业务表，保证用例自包含、可重复运行。 */
    @BeforeEach
    void resetDatabase() {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (schema == null || !schema.toLowerCase().contains("test")) {
            throw new IllegalStateException("安全护栏：集成测试只允许在 *test 库上运行，当前库=" + schema);
        }
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
                String.class);
        // ⚠️ 必须把 SET FOREIGN_KEY_CHECKS 与随后的 TRUNCATE **放在同一条连接上**。
        // 该设置是**会话级**的：原实现用 jdbc.execute(...) 逐条发（SET → 循环 TRUNCATE → SET），
        // 每条都可能从连接池拿到**另一条**连接 —— 于是 TRUNCATE 在"外键检查仍开着"的连接上执行，
        // 撞上被外键引用的表（station ← staff_station_application.fk_app_station）就报
        // ERROR 1701 Cannot truncate a table referenced in a foreign key constraint。
        // 平时靠连接复用侥幸通过，池状态一变（例如高负载下连接被换掉）就**偶发**失败：
        // 2026-09-18 隔离区全量回归里 314 例中恰有 1 例红在这里，排查成本远高于本修复。
        // ConnectionCallback 保证整段跑在同一条连接上。
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS=0");
                for (String t : tables) {
                    st.execute("TRUNCATE TABLE `" + t + "`");
                }
                st.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            return null;
        });
    }

    /* ==================== 令牌 ==================== */

    protected String customerToken(long customerId) {
        return jwtUtil.generateAccessToken(customerId, "customer", null, null);
    }

    protected String staffToken(long staffId, String role, Long stationId) {
        return jwtUtil.generateAccessToken(staffId, "staff", role, stationId);
    }

    /**
     * UNSELECTED 会话 token（员工首次进入配送端、还没有 staff 记录时）。
     * <p>openid 是**签进 token** 的，不是客户端回传的 —— 这正是
     * {@code /api/auth/select-role} 判定"这个微信是谁"的唯一依据。</p>
     */
    protected String unselectedStaffToken(long virtualUserId, String openid) {
        return jwtUtil.generateAccessToken(virtualUserId, "staff", "UNSELECTED", null, openid);
    }

    /* ==================== HTTP ==================== */

    private Api exchange(String method, String path, String token, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json; charset=UTF-8");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> res = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Api(res.statusCode(), parse(res.body()));
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " 失败: " + e.getMessage(), e);
        }
    }

    /** 空响应体一律解析成空对象，免得每个调用点都要判 null。 */
    private JsonNode parse(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return om.createObjectNode();
            }
            return om.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("响应体不是合法 JSON: " + raw, e);
        }
    }

    protected Api get(String path, String token) {
        return exchange("GET", path, token, null);
    }

    protected Api post(String path, String token, String body) {
        return exchange("POST", path, token, body);
    }

    protected Api put(String path, String token, String body) {
        return exchange("PUT", path, token, body);
    }

    protected Api delete(String path, String token) {
        return exchange("DELETE", path, token, null);
    }

    /* ==================== 造数 ==================== */

    protected long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("未取到自增主键: " + sql);
        }
        return key.longValue();
    }

    protected long createStation(String name) {
        return insert("INSERT INTO station(name, status) VALUES (?, 1)", name);
    }

    protected long createStaff(String name, String role, Long stationId, int status) {
        return insert("INSERT INTO staff(name, role, station_id, status) VALUES (?,?,?,?)",
                name, role, stationId, status);
    }

    protected long createCustomer(String name, String openid) {
        return insert("INSERT INTO customer(name, openid) VALUES (?,?)", name, openid);
    }

    protected long createProduct(String name, int category, String price, String deposit,
                                 int ticketEnabled, String ticketPrice) {
        return insert("INSERT INTO product(name, category, price, deposit, status, ticket_enabled, ticket_price) "
                        + "VALUES (?,?,?,?,1,?,?)",
                name, category, new BigDecimal(price), new BigDecimal(deposit), ticketEnabled, new BigDecimal(ticketPrice));
    }

    protected long createInventory(long stationId, long productId, int qty) {
        return insert("INSERT INTO inventory(station_id, product_id, quantity, enabled, ticket_enabled, ticket_price) "
                + "VALUES (?,?,?,1,0,0.00)", stationId, productId, qty);
    }

    protected long createInventoryFull(long stationId, long productId, int qty,
                                       int ticketEnabled, String ticketPrice) {
        return insert("INSERT INTO inventory(station_id, product_id, quantity, enabled, ticket_enabled, ticket_price) "
                        + "VALUES (?,?,?,1,?,?)",
                stationId, productId, qty, ticketEnabled, new BigDecimal(ticketPrice));
    }

    /**
     * 带**本站定价覆盖**的库存行（商品与库存重构，见 docs/design/12-商品与库存重构.md）。
     * <p>{@code salePrice}/{@code depositPrice} 传 null 或 "0" 都表示不覆盖（回落 product 的参考价）。</p>
     */
    protected long createInventoryWithStationPricing(long stationId, long productId, int qty,
                                                     String salePrice, String depositPrice,
                                                     int ticketEnabled, String ticketPrice) {
        return insert("INSERT INTO inventory(station_id, product_id, quantity, enabled, sale_price, deposit_price, "
                        + "ticket_enabled, ticket_price) VALUES (?,?,?,1,?,?,?,?)",
                stationId, productId, qty,
                salePrice == null ? null : new BigDecimal(salePrice),
                depositPrice == null ? null : new BigDecimal(depositPrice),
                ticketEnabled, new BigDecimal(ticketPrice));
    }

    protected long createAddress(long customerId, String detail) {
        return insert("INSERT INTO address(customer_id, name, phone, detail, is_default) VALUES (?,?,?,?,1)",
                customerId, "测试地址", "13800000000", detail);
    }

    protected long createOrder(long customerId, long addressId, long stationId, long productId,
                               int status, int paymentStatus) {
        return insert("INSERT INTO orders(customer_id, address_id, quantity, source, status, payment_status, "
                        + "station_id, delivery_station_id, product_id, total_amount) "
                        + "VALUES (?,?,1,3,?,?,?,?,?,0.00)",
                customerId, addressId, status, paymentStatus, stationId, stationId, productId);
    }

    protected long createOrderFull(long customerId, long addressId, long stationId, long productId,
                                   int status, int paymentStatus, Integer paymentMethod,
                                   String waterAmount, String depositAmount, String totalAmount,
                                   boolean firstBarrelOrder, int deliveryBucketQty) {
        return insert("INSERT INTO orders(customer_id, address_id, quantity, source, status, payment_status, "
                        + "payment_method, station_id, delivery_station_id, product_id, water_amount, deposit_amount, "
                        + "total_amount, first_barrel_order, delivery_bucket_qty) VALUES (?,?,1,3,?,?,?,?,?,?,?,?,?,?,?)",
                customerId, addressId, status, paymentStatus, paymentMethod, stationId, stationId, productId,
                new BigDecimal(waterAmount), new BigDecimal(depositAmount), new BigDecimal(totalAmount),
                firstBarrelOrder ? 1 : 0, deliveryBucketQty);
    }

    /**
     * 跨站外派单：归属站 ownerStation，履约站 deliveryStation（两者不同）。
     * <p>双水站语义下「钱与票记归属站、实物与库存走履约站」，是本项目最容易写错的一条边界，
     * 所有涉及外派单的回归都应显式构造本形态，而不是复用单站造的 {@code createOrderFull}。</p>
     */
    protected long createOrderCrossStation(long customerId, long addressId, long ownerStation,
                                          long deliveryStation, long productId,
                                          int status, int paymentStatus, Integer paymentMethod,
                                          String waterAmount, String depositAmount, String totalAmount) {
        return insert("INSERT INTO orders(customer_id, address_id, quantity, source, status, payment_status, "
                        + "payment_method, station_id, delivery_station_id, product_id, water_amount, deposit_amount, "
                        + "total_amount) VALUES (?,?,1,3,?,?,?,?,?,?,?,?,?)",
                customerId, addressId, status, paymentStatus, paymentMethod, ownerStation, deliveryStation, productId,
                new BigDecimal(waterAmount), new BigDecimal(depositAmount), new BigDecimal(totalAmount));
    }

    /** 库存流水。[AQ-029] 起库存变动必须留流水，此处用于对齐「库存回补到哪一站」。 */
    protected long createInventoryRecord(long stationId, long productId, int delta, String type, long refId) {
        return insert("INSERT INTO inventory_record(station_id, product_id, delta, type, ref_id, note) "
                + "VALUES (?,?,?,?,?,?)", stationId, productId, delta, type, refId, "测试造数");
    }

    protected long createOrderItem(long orderId, long productId, String productName, int quantity,
                                   String price, String deposit, int category) {
        return createOrderItemFull(orderId, productId, productName, quantity, 0, price, deposit);
    }

    /**
     * 订单明细（可控 deducted_qty）。
     * <p>{@code deductedQty} 是"下单时实际扣减的库存量"：库存不足时它 &lt; quantity，
     * 取消退款按它回补而不是按 quantity。凡是要验证库存回补的用例都必须显式设定它，
     * 否则默认 0 会让用例看起来"没有回补"，实为正确行为（一桶都没扣过）。</p>
     */
    protected long createOrderItemFull(long orderId, long productId, String productName, int quantity,
                                       int deductedQty, String price, String deposit) {
        BigDecimal p = new BigDecimal(price);
        BigDecimal d = new BigDecimal(deposit);
        BigDecimal subtotal = p.add(d).multiply(BigDecimal.valueOf(quantity));
        return insert("INSERT INTO order_item(order_id, product_id, product_name_snapshot, price, quantity, "
                        + "deposit, subtotal, deducted_qty) VALUES (?,?,?,?,?,?,?,?)",
                orderId, productId, productName, p, quantity, d, subtotal, deductedQty);
    }

    /**
     * 造一个水票账户余额。
     *
     * <p>⚠️ <b>v36 起必须同时建批次</b>：水票余额的真相源是 {@code ticket_lot}，
     * {@code ticket_account} 只是它的派生汇总（数量 + 金额价值）。只插账户不建批次会导致两件事：
     * ① 用票支付时 {@code TicketLotService.consumeFifo} 找不到批次，直接报「水票批次余额不足」；
     * ② 对账 E8 报不平。实测：只插账户的写法一次性打红了 10 个现有用例。</p>
     *
     * <p>单价记 0 并标记为推断值 —— 测试夹具不关心票值，但 E8 的两条等式（数量与金额）
     * 必须<b>同时</b>成立，所以单价 0 时 {@code right_amount} 也要是 0（DB 默认值即可）。</p>
     *
     * <p>⚠️ <b>参数顺序是 {@code (customerId, stationId, productId)}</b>。2026-09-19 实测发现
     * 有 4 处调用写成了 {@code (customer, product, station)} —— 它们一直"没出事"，
     * 只因为那些用例里本站与商品都是各自表的第一行（id 都是 1），写反了也插在同一格。
     * 一旦有人给那些用例加第二个水站或第二个商品，票就会被插进一个错误的账户，
     * 而现象是"用票支付报余额不足"，极难定位。传参前对一眼这个顺序。</p>
     */
    protected long createTicketAccount(long customerId, long stationId, long productId, int remainQuantity) {
        long id = insert("INSERT INTO ticket_account(customer_id, product_id, station_id, remain_quantity) "
                + "VALUES (?,?,?,?)", customerId, productId, stationId, remainQuantity);
        if (remainQuantity > 0) {
            insert("INSERT INTO ticket_lot(lot_no, customer_id, station_id, product_id, unit_price, qty, remain_qty, "
                            + "source_type, price_source, is_migrated, status) "
                            + "VALUES (?,?,?,?,0.00,?,?,3,3,1,1)",
                    "FIXTURE-" + id, customerId, stationId, productId, remainQuantity, remainQuantity);
        }
        return id;
    }

    protected long createCustomerStationConfig(long customerId, long stationId, int offlinePaymentEnabled) {
        return insert("INSERT INTO customer_station_config(customer_id, station_id, offline_payment_enabled) "
                + "VALUES (?,?,?)", customerId, stationId, offlinePaymentEnabled);
    }

    protected long createBarrelLot(String lotNo, long customerId, long stationId, long productId,
                                   String unitPrice, int qty, int remainQty) {
        return insert("INSERT INTO customer_barrel_lot(lot_no, customer_id, station_id, product_id, unit_price, "
                        + "qty, remain_qty, source_type, price_source, status, is_migrated) VALUES (?,?,?,?,?,?,?,1,1,1,0)",
                lotNo, customerId, stationId, productId, new BigDecimal(unitPrice), qty, remainQty);
    }

    protected long createBarrelAsset(long customerId, long stationId, long productId,
                                     int quantity, String rightAmount) {
        return insert("INSERT INTO customer_barrel_asset(customer_id, quantity, right_amount, product_id, station_id) "
                + "VALUES (?,?,?,?,?)", customerId, quantity, new BigDecimal(rightAmount), productId, stationId);
    }

    protected long createBarrelInTransit(long customerId, long stationId, long productId, int qty,
                                         String unitPrice, Long relatedOrderId, String status) {
        return insert("INSERT INTO customer_barrel_in_transit(customer_id, station_id, product_id, qty, unit_price, "
                        + "related_order_id, status) VALUES (?,?,?,?,?,?,?)",
                customerId, stationId, productId, qty, new BigDecimal(unitPrice), relatedOrderId, status);
    }

    protected long createDepositBalance(long customerId, long stationId, String balance) {
        return insert("INSERT INTO customer_deposit_account(customer_id, station_id, balance) VALUES (?,?,?)",
                customerId, stationId, new BigDecimal(balance));
    }

    protected long createBarrelOver(long customerId, long stationId, long productId, int overQty) {
        return insert("INSERT INTO customer_barrel_over(customer_id, station_id, product_id, over_qty) "
                + "VALUES (?,?,?,?)", customerId, stationId, productId, overQty);
    }

    protected long createPaymentRecord(Long orderId, long customerId, Long stationId,
                                       String amount, int method, int status) {
        return insert("INSERT INTO payment_record(order_id, customer_id, station_id, amount, payment_method, status) "
                + "VALUES (?,?,?,?,?,?)", orderId, customerId, stationId, new BigDecimal(amount), method, status);
    }

    /* ==================== 断言取值 ==================== */

    protected int intOf(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }

    protected long longOf(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    protected BigDecimal decimalOf(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    /* ==================== 响应封装 ==================== */

    /** 业务错误时 HTTP 仍是 200，因此断言一律看 body 的 code，不看 HTTP 状态（唯一例外是未认证 401）。 */
    public record Api(int status, JsonNode body) {

        public int code() {
            return body.path("code").asInt(-999);
        }

        public boolean isSuccess() {
            return code() == 0;
        }

        public String message() {
            return body.path("message").asText("");
        }

        public JsonNode data() {
            return body.path("data");
        }

        @Override
        public String toString() {
            return "HTTP " + status + " " + String.valueOf(body);
        }
    }
}
