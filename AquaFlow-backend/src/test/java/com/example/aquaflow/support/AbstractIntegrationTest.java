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
import java.util.Locale;

/**
 * 集成测试基类 —— 真实 Spring 上下文 + 真实 MySQL + 真实 HTTP。
 *
 * <p>三条不妥协的原则：</p>
 * <ol>
 *   <li><b>不用 Mock</b>：事务、CAS、唯一键、行锁这些正是被测对象，Mock 会把它们全掩盖掉。</li>
 *   <li><b>不碰真实库</b>：每个用例前断言库名<b>以 {@code aquaflow_test} 开头、或以 {@code _test} 结尾</b>
 *       （2026-09-30 由 {@code contains("test")} 收紧，判据与**残余风险**见 {@link #isTestSchema}），
 *       不符直接抛异常中止，杜绝误 TRUNCATE 生产库 {@code aquaflow} 与
 *       {@code latest} / {@code contest} / {@code attest} 这类只是"含 test"的库。</li>
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

    /**
     * 集成测试库名的**允许形态**：前缀 {@link #TEST_SCHEMA_PREFIX}，或后缀 {@link #TEST_SCHEMA_SUFFIX}。
     * <p>判据与「为什么必须是这两种、为什么不能再用 {@code contains("test")}」写在 {@link #isTestSchema}。</p>
     */
    private static final String TEST_SCHEMA_PREFIX = "aquaflow_test";

    private static final String TEST_SCHEMA_SUFFIX = "_test";

    /**
     * **永不接受**的库名关键词（子串匹配，大小写不敏感）—— 见 {@link #isTestSchema} 的 F-38 说明。
     * <p>为什么用"排除"而不是"白名单"：白名单要写死允许的形态，会把 {@code aquaflow_test_ci}
     * 这类合法并行库名一起拒掉；而这层防的是"看起来就是备份 / 旧库 / 生产"的名字，定点排除更准。
     * 加词前先想一遍：本仓真实用到、且**应该**被 TRUNCATE 的库名里有没有会撞上它的
     * （现已核对：{@code aquaflow_test}、{@code aquaflow_test_wp<N>}、{@code aquaflow_test_ci} 都不含下列词）。</p>
     */
    private static final List<String> TEST_SCHEMA_DENY =
            List.of("backup", "bak", "old", "prod", "archive", "restore", "_pre");

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
        if (!isTestSchema(schema)) {
            throw new IllegalStateException("安全护栏：集成测试只允许在测试库上运行（本方法会 TRUNCATE 该库全部表）"
                    + "—— 库名需以 " + TEST_SCHEMA_PREFIX + " 开头或以 " + TEST_SCHEMA_SUFFIX
                    + " 结尾，当前库=" + schema);
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

    /**
     * 库名判据（2026-09-30 收紧）：<b>以 {@value #TEST_SCHEMA_PREFIX} 开头，或以 {@value #TEST_SCHEMA_SUFFIX} 结尾</b>。
     *
     * <p><b>为什么从 {@code contains("test")} 收紧</b>：子串判据是"含就好"，不排他 ——
     * {@code latest}、{@code contest}、{@code attest}、{@code aquaflow_test_backup} 全都通过，
     * 而它的下游是 {@link #resetDatabase()} 对该库<b>全部 BASE TABLE 的无条件 TRUNCATE</b>。
     * 这几类库名在真实机器上都不是"可以随便清空"的库，{@code *_backup} 尤其致命：
     * 清掉的就是最后一份数据。要清库的护栏必须"只放行认识的名字"，不能"看着像就行"。</p>
     *
     * <p><b>为什么仍然必须放行 {@code aquaflow_test_*} 这一支</b>（三个真实用例，少一个都会误伤）：</p>
     * <ol>
     *   <li>CI：{@code .github/workflows/ci.yml} 的 {@code MYSQL_DATABASE}/{@code TEST_DB_URL} 就是 {@code aquaflow_test}；</li>
     *   <li>本机常规：{@code aquaflow_test}（{@code scripts/provision-test-db.sh} 重建的那个）；</li>
     *   <li><b>并行开发</b>：多会话同时跑测试时每个会话要用各自的库（{@code aquaflow_test_wp1}、
     *       {@code aquaflow_test_wp6} …，见 docs/audit/并行推进任务包.md §1.1 第 3 条 ——
     *       两个会话共用同一个库会互相 TRUNCATE，现象是"我的用例莫名红了"）。
     *       前缀判据天然覆盖 {@code aquaflow_test_wp<编号>}。</li>
     * </ol>
     *
     * <p><b>[2026-09-30 修 F-38] 再加一层"定点排除"</b>：前缀判据必须放行 {@code aquaflow_test_wp<编号>}
     * 供并行开发，于是 {@code aquaflow_test_backup} 这类"同样以 {@code aquaflow_test} 开头"的名字也顺带通过
     * —— 而它的下游是对全库 BASE TABLE 的<b>无条件 TRUNCATE</b>，清掉的可能就是最后一份数据。
     * 收紧成白名单（只放行 {@code aquaflow_test} / {@code *_test} / {@code aquaflow_test_wp\d+}）
     * 会把 {@code aquaflow_test_ci} 之类合法并行库名一起拒掉，代价更大；因此改用
     * {@link #TEST_SCHEMA_DENY} 的<b>子串排除</b>：含 backup/bak/old/prod/… 一律拒绝，
     * 不管它是否满足前缀（{@code aquaflow_test_backup_2026} 也挡得住）。
     * 判据取向是"要清库的护栏宁可多拒一个，不可少拒一个"。</p>
     *
     * <p>⚠️ 改本判据时留意另外两处**只是文字引用、不参与判据**的地方，它们现在仍写着旧的
     * 「库名含 {@code test}」：{@code application-test.yml} 顶部（已同步）与
     * {@code AquaFlowApplicationTests} 的类注释（未同步，属其他文件的范围）。</p>
     */
    private static boolean isTestSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String s = schema.toLowerCase(Locale.ROOT);
        for (String bad : TEST_SCHEMA_DENY) {
            if (s.contains(bad)) {
                return false;
            }
        }
        return s.startsWith(TEST_SCHEMA_PREFIX) || s.endsWith(TEST_SCHEMA_SUFFIX);
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

    /* ==================== 裸事务与"锁证据"（并发用例） ==================== */

    /**
     * 开一条**会话级裸事务**连接（autocommit=false），用来扮演"另一个并发事务"。
     *
     * <p>⚠️ 用完必须 {@code close()}：未提交的事务在连接关闭时由 InnoDB 回滚，
     * 否则行锁会泄漏给同一用例类里的下一个测试（实测过：下一个用例会莫名其妙地等锁超时）。</p>
     */
    protected Connection openRawTransaction() throws Exception {
        Connection c = jdbc.getDataSource().getConnection();
        c.setAutoCommit(false);
        return c;
    }

    /** 这条裸连接的连接 id（`CONNECTION_ID()` 必须在它自己的连接上查）。 */
    protected long rawConnectionId(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT CONNECTION_ID()");
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * 这条裸连接当前**活跃事务**的 InnoDB 事务号（用于把"谁挡住了谁"钉到具体事务上）。
     * <p>⚠️ 只有"已经开始且还没提交"的事务才在 {@code information_schema.innodb_trx} 里；
     * 因此在裸连接上先执行一条语句（例如取锁）再调本方法。</p>
     */
    protected long rawTrxId(Connection c) throws Exception {
        Long trxId = jdbc.queryForObject(
                "SELECT trx_id FROM information_schema.innodb_trx WHERE trx_mysql_thread_id = ?",
                Long.class, rawConnectionId(c));
        if (trxId == null) {
            throw new IllegalStateException("裸连接上还没有活跃事务（先在它上面执行一条语句再加锁判定）");
        }
        return trxId;
    }

    /** 在指定连接上执行写语句，返回受影响行数。 */
    protected int executeRaw(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    /**
     * 在指定连接上执行**加锁读**（`SELECT ... FOR UPDATE` 这类会返回结果集的语句）。
     * <p>⚠️ 别用 {@link #executeRaw} 跑 SELECT：JDBC 会报
     * "Can not issue executeUpdate() with statements that produce result sets"。</p>
     */
    protected void lockRowsRaw(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // 只需要"锁住"，不需要行内容
                }
            }
        }
    }

    /** 在指定连接上插入并返回自增主键。 */
    protected long insertRaw(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
            try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("未取到自增主键: " + sql);
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * 等"**确实有事务被 `blockingTrxId` 这把锁挡在 `expectTable` 上**"。
     *
     * <p>为什么不用"实例里有锁等待"或"某查询跑了 ≥2 秒"（二次收口契约 §3 明确否掉了这两种）：
     * 它们证明不了**哪个请求**停在**哪把锁**上。这里的判据把三件事绑在一起：
     * ① 等待方的事务出现在 {@code performance_schema.data_lock_waits} 里；
     * ② 阻塞方事务 = 我们那条裸连接的 trx_id（不是"某个事务"）；
     * ③ 等待的锁对象 = 指定的表（例如 {@code inventory}）。</p>
     *
     * <p>断言"两个请求的第一把锁都是 inventory 行"就是这么做的：如果实现改成先锁凭据，
     * 等待的锁对象会是 {@code inventory_reservation}，这里的计数就凑不满。</p>
     *
     * @return 实际观察到的等待条数（≥ expected 时返回）
     */
    protected int awaitBlockedBy(long blockingTrxId, String expectTable, int expected, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            last = waitingLocksBlockedBy(blockingTrxId, expectTable);
            if (last >= expected) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("屏障不成立：被 trx " + blockingTrxId + " 挡在 `" + expectTable
                + "` 上的等待数 = " + last + "（期望 ≥ " + expected + "）。当前锁等待快照=" + lockWaitSnapshot());
    }

    /** 当前被 `blockingTrxId` 挡在 `table` 上的等待条数。 */
    protected int waitingLocksBlockedBy(long blockingTrxId, String table) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                        + "JOIN performance_schema.data_locks l "
                        + "  ON l.ENGINE_TRANSACTION_ID = w.REQUESTING_ENGINE_TRANSACTION_ID "
                        + " AND l.LOCK_STATUS = 'WAITING' "
                        + "WHERE w.BLOCKING_ENGINE_TRANSACTION_ID = ? AND l.OBJECT_NAME = ?",
                Integer.class, blockingTrxId, table);
        return n == null ? 0 : n;
    }

    /** 锁等待快照（诊断用；只在失败信息里贴出来，不作判据）。 */
    protected String lockWaitSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append(jdbc.queryForList("SELECT w.REQUESTING_ENGINE_TRANSACTION_ID AS waiter, "
                + "w.BLOCKING_ENGINE_TRANSACTION_ID AS blocker, l.OBJECT_NAME AS obj, "
                + "l.LOCK_TYPE AS type, l.LOCK_MODE AS mode, l.LOCK_DATA AS data "
                + "FROM performance_schema.data_lock_waits w "
                + "LEFT JOIN performance_schema.data_locks l "
                + "  ON l.ENGINE_TRANSACTION_ID = w.REQUESTING_ENGINE_TRANSACTION_ID "
                + " AND l.LOCK_STATUS = 'WAITING'"));
        sb.append(" innodb_trx=").append(jdbc.queryForList(
                "SELECT trx_id, trx_state, trx_mysql_thread_id AS conn, LEFT(trx_query, 60) AS q "
                        + "FROM information_schema.innodb_trx"));
        return sb.toString();
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
        // ⚠️ `operating_status` **显式写 1（正常运营）**，不要图省事省略它去吃列默认值：
        //    2026-09-23 起列默认值是 **3「待上线」**（新注册水站的默认，见
        //    sql/migration_v61_station_pending_launch.sql），省略会让**所有用例建的站**都变成
        //    "尚未上线"，进而给每个下单响应塞一条 `warnings`，污染一大批契约断言。
        //    "新站默认待上线"由 StationOperatingStatusIntegrationTest 里那条刻意不写该列的用例验。
        return insert("INSERT INTO station(name, status, operating_status) VALUES (?, 1, 1)", name);
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

    /**
     * 库存预留凭据（`inventory_reservation`，v63 起；v65 起带 `need_qty`/`need_time` 快照列）。
     *
     * <p>⚠️ <b>SQL 造出来的在途单必须显式补一份凭据</b>，否则完成配送会被完整性检查拒绝
     * （{@code shipForOrder}：凭据必须覆盖每一条明细）。这不是"生产绕过逻辑"，而是模型的一部分：
     * 「这份货为哪张单留在哪个站」本身就是一条要落库的事实。</p>
     *
     * <p>{@code status} 用 {@code constant/ReservationStatus}（1 预留中 / 2 已出库 / 3 已释放）；
     * 同一明细只允许**一条** status=1（唯一键 {@code uk_reservation_active_item} 建在生成列上，
     * 插第二条会撞 1062 —— 这正是要测的行为，别为了绕过它去掉唯一键）。</p>
     *
     * <p>需求量/需求时间快照从**真相源**取（`order_item.quantity` / `orders.create_time`）——
     * 夹具不该另编一个值，否则 E15（快照 ≠ 真相源）会红。</p>
     */
    protected long createReservation(long orderId, long orderItemId, long productId, long stationId,
                                     int reservedQty, int status) {
        Integer need = intOf("SELECT quantity FROM order_item WHERE id=?", orderItemId);
        java.time.LocalDateTime needTime = jdbc.queryForObject(
                "SELECT create_time FROM orders WHERE id=?", java.time.LocalDateTime.class, orderId);
        return insert("INSERT INTO inventory_reservation(order_id, order_item_id, product_id, station_id, "
                        + "need_qty, need_time, reserved_qty, shipped_qty, released_qty, status) "
                        + "VALUES (?,?,?,?,?,?,?,0,0,?)",
                orderId, orderItemId, productId, stationId, need, needTime, reservedQty, status);
    }

    protected long createOrderItem(long orderId, long productId, String productName, int quantity,
                                   String price, String deposit, int category) {
        return createOrderItemFull(orderId, productId, productName, quantity, 0, price, deposit);
    }

    /**
     * 造一条**"完成配送能过完整性检查"**的订单明细：明细 + 一条活跃预留凭据（站别 = 该单当前履约站）。
     *
     * <p>为什么需要它（2026-09-25 库存预留模型）：{@code shipForOrder} 对在途单的检查是
     * 「每条明细都要有一份活跃凭据、且凭据预留量 = 需求量」——用 SQL 直接造订单**不会**产生凭据，
     * 于是"造单 → 点完成配送"的老写法现在必然被拒（契约 §5 原话：原来插订单却不创建凭据的夹具应补完整，
     * 不能为了迁就夹具在生产里开兼容分支）。</p>
     *
     * <p>⚠️ 它**不**替调用方造实物：履约站的 {@code inventory.quantity} 必须由测试自己声明
     * （库存是业务事实，夹具不该凭空编）。实物不够时完成配送会被"本站库存不足"拒绝 —— 那是对的。</p>
     */
    protected long createReservedItem(long orderId, long productId, String productName, int quantity,
                                      String price, String deposit) {
        long itemId = createOrderItemFull(orderId, productId, productName, quantity, quantity, price, deposit);
        long stationId = longOf("SELECT COALESCE(delivery_station_id, station_id) FROM orders WHERE id=?", orderId);
        createReservation(orderId, itemId, productId, stationId, quantity, 1);
        return itemId;
    }

    /**
     * 给一条**已经用 SQL 直接插进去**的明细补上活跃预留凭据（预留量 = 需求量，站别 = 该单当前履约站）。
     * <p>与 {@link #createReservedItem} 的区别只是"明细已经存在"；返回明细 id 方便链式使用。</p>
     */
    protected long reserveExistingItem(long orderItemId) {
        long orderId = longOf("SELECT order_id FROM order_item WHERE id=?", orderItemId);
        long productId = longOf("SELECT product_id FROM order_item WHERE id=?", orderItemId);
        int qty = intOf("SELECT quantity FROM order_item WHERE id=?", orderItemId);
        long stationId = longOf("SELECT COALESCE(delivery_station_id, station_id) FROM orders WHERE id=?", orderId);
        jdbc.update("UPDATE order_item SET deducted_qty=? WHERE id=?", qty, orderItemId);
        createReservation(orderId, orderItemId, productId, stationId, qty, 1);
        return orderItemId;
    }

    /**
     * 订单明细（可控 deducted_qty）。
     *
     * <p>⚠️ {@code deductedQty} 现在是 <b>{@code inventory_reservation} 里该明细那条活跃凭据
     * {@code reserved_qty} 的镜像</b>（2026-09-25 库存预留模型，返工 R5），不再是"下单时扣了多少实物"
     * —— 下单不再减实物。旧语义下"取消按它 increaseStock 回补"的逻辑已随 v63 删除。</p>
     *
     * <p>⚠️ 用 SQL 造在途单时，<b>实物既没被扣、凭据也不存在</b>：要让这张单能被完成配送，
     * 必须自己调 {@link #createReservation} 补一条凭据（并把本参数设成同一个数保持镜像一致）。
     * 只想验证"没有凭据就完不成"的用例，则刻意什么都不补。</p>
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
