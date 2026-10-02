package com.example.aquaflow.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Test-only F-68 guard (2026-10-02): a test-looking catalog is not consent.
 * Fail before context connections, then recheck the actual connection before any TRUNCATE.
 * Keep the supported URL/environment subset aligned with scripts/lib/test-database-target.js.
 */
public final class TestDatabaseTargetGuard {
    private TestDatabaseTargetGuard() {}
    private static final String QUERY = "useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    private static final Pattern JDBC = Pattern.compile("^jdbc:mysql://([a-zA-Z0-9.-]+)(?::([0-9]+))?/([a-z][a-z0-9_]{0,63})(?:\\?(.+))?$");
    private static final Map<String, String> OPTIONS = Map.of(
            "useUnicode", "true|false", "characterEncoding", "utf-8|UTF-8|utf8",
            "serverTimezone", "Asia/Shanghai", "allowPublicKeyRetrieval", "true|false",
            "useSSL", "true|false", "sslMode", "DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY");
    private static final Set<String> JVM_OPTIONS = Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS",
            "MYSQL_PROTOCOL", "MYSQL_SOCKET", "MYSQL_UNIX_PORT", "MYSQL_TCP_PORT");
    private static final Set<String> POOL_OPTIONS = Set.of("connectiontimeout", "validationtimeout", "idletimeout", "maxlifetime",
            "minimumidle", "maximumpoolsize", "leakdetectionthreshold", "poolname", "autocommit", "registermbeans", "keepalivetime");
    private static final List<String> STARTUP_SQL_PREFIXES = List.of("springsqlinit", "springflyway", "springliquibase",
            "springbatchjdbc", "springquartzjdbc", "springsessionjdbc", "springjpa", "springhibernate", "springr2dbc");

    public record Target(String host, String port, String name, String jdbcUrl) {
        public String endpoint() { return host + ":" + port + "/" + name; }
    }

    private static IllegalStateException refuse(String message) { return new IllegalStateException("测试目标保护：" + message); }
    private static String normalized(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    private static void assertNoStartupSql(String name, String actual) {
        if (STARTUP_SQL_PREFIXES.stream().anyMatch(name::startsWith)
                && !(name.equals("springsqlinitmode") && "never".equals(actual))) {
            throw refuse("测试库须显式准备；禁止启动 SQL/迁移配置及脚本、凭据或目标覆盖，仅允许 spring.sql.init.mode=never");
        }
    }
    private static String value(Map<String, String> env, String name, String fallback) {
        String v = env.get(name); return v == null || v.isEmpty() ? fallback : v;
    }
    private static String host(String text) {
        String h = text.toLowerCase(Locale.ROOT);
        if (!h.matches("[0-9.]+")) throw refuse("仅支持完整 IPv4；主机名、多地址 DNS、别名或 IPv6 无法证明一致");
        String[] octets = h.split("\\.", -1);
        if (octets.length != 4) throw refuse("仅支持完整 IPv4");
        for (String part : octets) if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) throw refuse("IPv4 不规范");
        return h;
    }
    private static String port(String p) {
        if (!p.matches("[1-9][0-9]{0,4}") || Integer.parseInt(p) > 65535) throw refuse("端口须为规范十进制 1–65535");
        return p;
    }

    /** No credentials, encoded separators, multi-host URLs or target-changing driver properties. */
    public static Target parseJdbc(String url) {
        if (url == null || Pattern.compile("[\\s%+#\\\\]").matcher(url).find()) throw refuse("JDBC 含不支持的字符或编码");
        var match = JDBC.matcher(url);
        if (!match.matches()) throw refuse("仅支持单主机 mysql TCP JDBC 地址及明确库名");
        String h = host(match.group(1)), p = port(match.group(2) == null ? "3306" : match.group(2)), name = match.group(3), query = match.group(4);
        Set<String> seen = new HashSet<>();
        if (query != null) for (String pair : query.split("&", -1)) {
            String[] option = pair.split("=", -1);
            if (option.length != 2 || !OPTIONS.containsKey(option[0]) || !seen.add(option[0]) || !option[1].matches(OPTIONS.get(option[0]))) {
                throw refuse("未知/重复驱动参数，不接受连接改写、代理或初始化语句");
            }
        }
        return new Target(h, p, name, "jdbc:mysql://" + h + ":" + p + "/" + name + (query == null ? "" : "?" + query));
    }

    public static Target confirmed(Map<String, String> env, Properties system) {
        Set<String> bindingKeys = Set.of("MYSQL_HOST", "MYSQL_PORT", "MYSQL_USER", "TEST_DB_NAME", "TEST_DB_URL", "DB_URL",
                "AQUAFLOW_DB", "AQUAFLOW_ALLOW_DB_RESET", "AQUAFLOW_ALLOW_TEST_DB_TARGET");
        for (var entry : env.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            if (bindingKeys.contains(entry.getKey().toUpperCase(Locale.ROOT)) && !entry.getKey().equals(entry.getKey().toUpperCase(Locale.ROOT))) {
                throw refuse("绑定变量须使用规定的大写名称，避免 Windows/JVM 读取差异");
            }
            String n = normalized(entry.getKey());
            assertNoStartupSql(n, entry.getValue());
            if (n.startsWith("springdatasource") || n.startsWith("springconfig") || n.startsWith("springprofiles")
                    || n.startsWith("springapplicationjson") || n.startsWith("springapplicationname") || n.startsWith("springmain")
                    || n.startsWith("springautoconfigureexclude") || JVM_OPTIONS.contains(entry.getKey().toUpperCase(Locale.ROOT))) throw refuse("存在其它 Spring/JVM/MySQL 覆盖入口，无法证明同一目标");
        }
        for (String key : system.stringPropertyNames()) {
            String n = normalized(key);
            assertNoStartupSql(n, system.getProperty(key));
            if (n.startsWith("springdatasource") || n.startsWith("springconfig") || n.startsWith("springprofiles")
                    || n.startsWith("springapplicationjson") || n.startsWith("springmain") || n.startsWith("springautoconfigureexclude")) {
                throw refuse("存在 JVM 属性覆盖测试数据源或配置入口");
            }
        }
        String name = value(env, "TEST_DB_NAME", "aquaflow_test"), source = value(env, "AQUAFLOW_DB", "aquaflow");
        if (!name.matches("[a-z][a-z0-9_]{0,63}") || !source.matches("[a-z][a-z0-9_]{0,63}")
                || !(name.equals("aquaflow_test") || name.matches("aquaflow_test_[a-z0-9][a-z0-9_]*"))
                || Pattern.compile("backup|archive|(^|_)(bak|prod|production|live)($|_)").matcher(name).find() || name.equals(source)) throw refuse("业务/备份/源库或未登记的临时库不可清空");
        if (!name.equals(env.get("AQUAFLOW_ALLOW_DB_RESET"))) throw refuse("缺少本次精确库名确认 AQUAFLOW_ALLOW_DB_RESET");
        if (!AbstractIntegrationTest.isTestSchema(name)) throw refuse("原集成测试名称护栏拒绝此库");
        String h = host(value(env, "MYSQL_HOST", "127.0.0.1")), p = port(value(env, "MYSQL_PORT", "3306"));
        Target expected = parseJdbc(value(env, "TEST_DB_URL", "jdbc:mysql://" + h + ":" + p + "/" + name + "?" + QUERY));
        if (!expected.host().equals(h) || !expected.port().equals(p) || !expected.name().equals(name)) throw refuse("建库与 JDBC 的服务器、端口、库名不一致");
        if (env.containsKey("DB_URL") && !env.get("DB_URL").isEmpty()) assertSame(expected, parseJdbc(env.get("DB_URL")));
        if (!expected.endpoint().equals(env.get("AQUAFLOW_ALLOW_TEST_DB_TARGET"))) throw refuse("缺少完整 TCP 目标确认 AQUAFLOW_ALLOW_TEST_DB_TARGET");
        return expected;
    }
    public static Target current() { return confirmed(System.getenv(), System.getProperties()); }
    public static void registerDatasource(DynamicPropertyRegistry registry, Target target) {
        registry.add("spring.datasource.url", target::jdbcUrl);
        registry.add("spring.datasource.hikari.jdbc-url", target::jdbcUrl);
    }
    public static void assertSame(Target expected, Target actual) {
        if (!expected.endpoint().equals(actual.endpoint())) throw refuse("实际 JDBC 地址与本次精确确认目标不一致");
    }

    /** Called before bean creation; rejects startup SQL as well as Hikari init SQL and hidden driver properties. */
    public static void assertEnvironment(ConfigurableEnvironment env, Target expected) {
        assertSame(expected, parseJdbc(env.getProperty("spring.datasource.url")));
        for (var source : env.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                // An opaque custom source can feed Hikari properties without exposing names.
                // Only framework placeholders/random and the wrapper over the inspected sources are supported.
                if (!Set.of("org.springframework.core.env.PropertySource$StubPropertySource",
                        "org.springframework.boot.env.RandomValuePropertySource",
                        "org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertySource")
                        .contains(source.getClass().getName())) throw refuse("存在无法枚举核对的配置来源，禁止创建数据源");
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                String n = normalized(key);
                assertNoStartupSql(n, env.getProperty(key));
                if (!n.startsWith("springdatasource")) continue;
                if (Set.of("springdatasourceurl", "springdatasourceusername", "springdatasourcepassword").contains(n)) continue;
                String actual = env.getProperty(key);
                if (actual == null || actual.isEmpty()) continue;
                if (n.equals("springdatasourcehikarijdbcurl")) { assertSame(expected, parseJdbc(actual)); continue; }
                if (n.equals("springdatasourcedriverclassname") && actual.equals("com.mysql.cj.jdbc.Driver")) continue;
                if (n.equals("springdatasourcetype") && actual.equals("com.zaxxer.hikari.HikariDataSource")) continue;
                if (n.startsWith("springdatasourcehikari") && POOL_OPTIONS.contains(n.substring("springdatasourcehikari".length()))) continue;
                throw refuse("有效配置包含不支持的数据源类型/驱动属性/初始化语句，禁止连接");
            }
        }
    }

    public static void assertConnection(Connection connection, Target expected) throws SQLException {
        assertSame(expected, parseJdbc(connection.getMetaData().getURL()));
        assertSchema(connection.getCatalog(), expected);
    }
    public static void assertSchema(String actual, Target expected) {
        if (!expected.name().equals(actual)) throw refuse("实际当前库不是本次确认的可清空库");
    }

    /** Test context entry also protects direct Gradle invocations before any datasource bean starts. */
    public static final class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        private final java.util.function.Supplier<Target> target;
        public Initializer() { this(TestDatabaseTargetGuard::current); }
        Initializer(java.util.function.Supplier<Target> target) { this.target = target; }
        @Override public void initialize(ConfigurableApplicationContext context) {
            assertEnvironment(context.getEnvironment(), target.get());
            // Pin only after rejecting overrides: never hide an unsupported script source.
            // This also disables default script discovery in standalone test contexts.
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "aquaflowTestSqlInitDisabled", Map.of("spring.sql.init.mode", "never")));
        }
    }
}
