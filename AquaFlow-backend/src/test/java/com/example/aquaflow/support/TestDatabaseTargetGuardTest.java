package com.example.aquaflow.support;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.sql.autoconfigure.init.SqlInitializationProperties;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure protection and fake-context/JDBC behavior only; no AquaFlow application or real connection starts. */
class TestDatabaseTargetGuardTest {
    private static final String NAME = "aquaflow_test_guard_s1";
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/" + NAME;
    private static Map<String, String> confirmedEnv() {
        return new HashMap<>(Map.of("TEST_DB_NAME", NAME, "TEST_DB_URL", URL,
                "AQUAFLOW_ALLOW_DB_RESET", NAME, "AQUAFLOW_ALLOW_TEST_DB_TARGET", "127.0.0.1:3306/" + NAME));
    }
    private static TestDatabaseTargetGuard.Target target() { return TestDatabaseTargetGuard.confirmed(confirmedEnv(), new Properties()); }
    @Test void exactTargetAndMatchingCiDbUrlAreAccepted() {
        var env = confirmedEnv(); env.put("DB_URL", URL + "?useSSL=false");
        assertEquals("127.0.0.1:3306/" + NAME, TestDatabaseTargetGuard.confirmed(env, new Properties()).endpoint());
    }
    @Test void explicitlyConfirmedRemoteIpv4AndNonDefaultPortAreAccepted() {
        var env = confirmedEnv(); String url = "jdbc:mysql://192.0.2.10:3307/" + NAME;
        env.put("MYSQL_HOST", "192.0.2.10"); env.put("MYSQL_PORT", "3307"); env.put("TEST_DB_URL", url);
        env.put("AQUAFLOW_ALLOW_TEST_DB_TARGET", "192.0.2.10:3307/" + NAME);
        var expected = TestDatabaseTargetGuard.confirmed(env, new Properties());
        assertEquals("192.0.2.10:3307/" + NAME, expected.endpoint());
        assertDoesNotThrow(() -> TestDatabaseTargetGuard.assertConnection(new FakeConnection(url, NAME, NAME).connection(), expected));
    }
    @Test void directInvocationWithoutEitherConfirmationIsRejected() {
        for (String key : List.of("AQUAFLOW_ALLOW_DB_RESET", "AQUAFLOW_ALLOW_TEST_DB_TARGET")) {
            var env = confirmedEnv(); env.remove(key);
            assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(env, new Properties()));
        }
    }
    @Test void namespaceAndSourceGuardsRemainMandatory() {
        for (String name : List.of("aquaflow", "customer_business_test", "aquaflow_test_backup", "aquaflow_test_live", "aquaflow_test_", "aquaflow_test_old", "aquaflow_test_restore", "aquaflow_test_pre")) {
            var env = confirmedEnv(); env.put("TEST_DB_NAME", name); env.put("AQUAFLOW_ALLOW_DB_RESET", name);
            assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(env, new Properties()));
        }
        var env = confirmedEnv(); env.put("AQUAFLOW_DB", NAME);
        assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(env, new Properties()));
    }
    @Test void hostPortCatalogAndDbUrlMismatchAreRejected() {
        for (var change : List.of(Map.entry("TEST_DB_URL", "jdbc:mysql://192.0.2.11:3306/" + NAME),
                Map.entry("MYSQL_PORT", "3307"), Map.entry("TEST_DB_URL", "jdbc:mysql://127.0.0.1:3306/aquaflow_test_other"),
                Map.entry("DB_URL", "jdbc:mysql://127.0.0.1:3306/aquaflow"))) {
            var env = confirmedEnv(); env.put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(env, new Properties()));
        }
    }
    @Test void encodedMultiHostAndPropertyOverridesFailClosedWithoutLeakingInput() {
        for (String url : List.of(URL + "?host=other.invalid", URL + "?socketFactory=Factory", URL + "?propertiesTransform=Transform",
                URL + "?sessionVariables=USE_aquaflow", URL + "?useConfigs=profile", URL + "?useSSL=false&useSSL=true",
                URL + "?user=user&password=secret-never-echo", URL + "%2fextra", URL + "?serverTimezone=Asia%2FShanghai",
                "jdbc:mysql://127.0.0.1,other.invalid/" + NAME, "jdbc:mysql://address=(host=other.invalid)/" + NAME,
                "jdbc:mysql:loadbalance://127.0.0.1/" + NAME, "jdbc:mysql://127.00.0.1/" + NAME)) {
            var error = assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.parseJdbc(url));
            assertFalse(error.getMessage().contains("secret-never-echo"));
        }
    }
    @Test void environmentAndSystemOverridesAreRejectedBeforeContextCreation() {
        for (String key : List.of("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_HIKARI_JDBC_URL", "SPRING_APPLICATION_JSON",
                "SPRING_CONFIG_IMPORT", "SPRING_PROFILES_ACTIVE", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS", "java_tool_options", "mysql_protocol", "test_db_url")) {
            var env = confirmedEnv(); env.put(key, "untrusted");
            assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(env, new Properties()));
        }
        var props = new Properties(); props.setProperty("spring.datasource.url", URL);
        assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(confirmedEnv(), props));
    }
    @Test void startupSqlEnvironmentAndJvmOverridesAreRejected() {
        for (String key : List.of("SPRING_SQL_INIT_MODE", "SPRING_SQL_INIT_SCHEMA_LOCATIONS", "SPRING_SQL_INIT_DATA_LOCATIONS_0",
                "spring_sql_init_mode", "spring.sql.init.schema-locations[0]", "SPRING_SQL_INIT_USERNAME", "SPRING_SQL_INIT_PASSWORD",
                "SPRING_SQL_INIT_ENABLED", "SPRING_FLYWAY_URL", "SPRING_LIQUIBASE_CHANGE_LOG", "SPRING_BATCH_JDBC_INITIALIZE_SCHEMA",
                "SPRING_QUARTZ_JDBC_SCHEMA", "SPRING_SESSION_JDBC_INITIALIZE_SCHEMA", "SPRING_JPA_HIBERNATE_DDL_AUTO",
                "SPRING_HIBERNATE_HBM2DDL_AUTO", "SPRING_R2DBC_URL", "SPRING_DATASOURCE_SCHEMA", "SPRING_DATASOURCE_DATA")) {
            var env = confirmedEnv(); env.put(key, "never-echo-init-password");
            var error = assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(env, new Properties()));
            assertFalse(error.getMessage().contains("never-echo-init-password"));
            var props = new Properties(); props.setProperty(key, "never-echo-init-password");
            assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(confirmedEnv(), props));
        }
        var safeEnv = confirmedEnv(); safeEnv.put("SPRING_SQL_INIT_MODE", "never");
        var safeProps = new Properties(); safeProps.setProperty("spring.sql.init.mode", "never");
        assertDoesNotThrow(() -> TestDatabaseTargetGuard.confirmed(safeEnv, safeProps));
        safeEnv.put("SPRING_SQL_INIT_SCHEMA_LOCATIONS", "file:/fake-startup.sql");
        assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.confirmed(safeEnv, safeProps));
    }
    @Test void startupSqlAndRelatedEffectivePropertiesAreRejected() {
        for (var setting : List.of(Map.entry("spring.sql.init.mode", "always"), Map.entry("spring.sql.init.mode", "embedded"),
                Map.entry("spring.sql.init.schema-locations[0]", "file:/fake-startup.sql"),
                Map.entry("SPRING_SQL_INIT_DATA_LOCATIONS", "file:/fake-startup.sql"),
                Map.entry("spring.sql.init.schema-password", "never-echo-init-password"), Map.entry("spring.sql.init.continue-on-error", "true"),
                Map.entry("spring.flyway.url", "jdbc:mysql://192.0.2.11/unconfirmed"), Map.entry("spring.liquibase.change-log", "file:/fake-startup.sql"),
                Map.entry("spring.batch.jdbc.initialize-schema", "always"), Map.entry("spring.quartz.jdbc.schema", "file:/fake-startup.sql"),
                Map.entry("spring.session.jdbc.initialize-schema", "always"), Map.entry("spring.jpa.hibernate.ddl-auto", "create"),
                Map.entry("spring.hibernate.hbm2ddl.auto", "create"), Map.entry("spring.r2dbc.url", "r2dbc:mysql://192.0.2.11/unconfirmed"),
                Map.entry("spring.datasource.schema", "file:/fake-startup.sql"), Map.entry("spring.datasource.data", "file:/fake-startup.sql"))) {
            var env = new MockEnvironment().withProperty("spring.datasource.url", URL)
                    .withProperty("spring.sql.init.mode", "never").withProperty(setting.getKey(), setting.getValue());
            var error = assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.assertEnvironment(env, target()));
            assertFalse(error.getMessage().contains("never-echo-init-password"));
        }
        assertDoesNotThrow(() -> TestDatabaseTargetGuard.assertEnvironment(new MockEnvironment().withProperty("spring.datasource.url", URL)
                .withProperty("spring.sql.init.mode", "never"), target()));
    }
    @Test void effectiveHikariUrlMustMatchAndUnknownDriverOrInitSqlCannotConnect() {
        for (var change : List.of(Map.entry("spring.datasource.hikari.jdbc-url", "jdbc:mysql://192.0.2.11:3306/" + NAME),
                Map.entry("spring.datasource.hikari.data-source-properties.host", "other.invalid"),
                Map.entry("spring.datasource.hikari.connection-init-sql", "USE aquaflow"),
                Map.entry("spring.datasource.hikari.connection-test-query", "DELETE FROM station"),
                Map.entry("spring.datasource.jndi-name", "java:comp/env/BusinessDb"),
                Map.entry("spring.datasource.driver-class-name", "custom.Driver"), Map.entry("spring.datasource.type", "custom.DataSource"))) {
            var env = new MockEnvironment().withProperty("spring.datasource.url", URL).withProperty(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.assertEnvironment(env, target()));
        }
        assertDoesNotThrow(() -> TestDatabaseTargetGuard.assertEnvironment(new MockEnvironment().withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.hikari.jdbc-url", URL).withProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10"), target()));
    }
    @Test void actualInitializerStopsBeforeDatasourceBeanSupplierCanStart() {
        int[] starts = {0};
        new ApplicationContextRunner().withInitializer(new TestDatabaseTargetGuard.Initializer(TestDatabaseTargetGuardTest::target))
                .withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:3306/customer_business_test")
                .withBean("fakeDatasource", Object.class, () -> { starts[0]++; return new Object(); })
                .run(context -> { assertNotNull(context.getStartupFailure()); assertEquals(0, starts[0]); });
    }
    @Test void actualInitializerRejectsMissingConsentBeforeReadingDatasourceProperties() {
        try (var context = new GenericApplicationContext()) {
            assertThrows(IllegalStateException.class, () -> new TestDatabaseTargetGuard.Initializer(
                    () -> TestDatabaseTargetGuard.confirmed(Map.of(), new Properties())).initialize(context));
            assertFalse(context.isActive());
        }
    }
    @Test void actualDynamicRegistrationPinsBothGenericAndHikariUrls() {
        Map<String, java.util.function.Supplier<Object>> values = new HashMap<>();
        TestDatabaseTargetGuard.registerDatasource(values::put, target());
        assertEquals(URL, values.get("spring.datasource.url").get());
        assertEquals(URL, values.get("spring.datasource.hikari.jdbc-url").get());
    }
    @Test void bootActuallyDiscoversTestOnlyFactoryIncludingForStandaloneContextTests() {
        // Constructing SpringApplication loads initializer factories only; run() is forbidden here.
        var application = new SpringApplication(Object.class);
        assertEquals(1L, application.getInitializers().stream().filter(i -> i instanceof TestDatabaseTargetGuard.Initializer).count());
    }
    @Test void normalGuardedFakeContextCanCreateItsNonDatabaseBean() {
        int[] starts = {0};
        new ApplicationContextRunner().withInitializer(new TestDatabaseTargetGuard.Initializer(TestDatabaseTargetGuardTest::target))
                .withPropertyValues("spring.datasource.url=" + URL)
                .withBean("fakeDatasource", Object.class, () -> { starts[0]++; return new Object(); })
                .run(context -> { assertNull(context.getStartupFailure()); assertEquals(1, starts[0]); });
    }
    // TestConfiguration avoids adding this fixture to AquaFlow's normal component scan.
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SqlInitializationProperties.class)
    static class StartupSqlProperties {}

    private ApplicationContextRunner sqlContext(StartupSqlProbe probe) {
        return new ApplicationContextRunner().withUserConfiguration(StartupSqlProperties.class)
                .withConfiguration(AutoConfigurations.of(DataSourceInitializationAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + URL).withBean(DataSource.class, probe::datasource);
    }
    @Test void actualSqlInitializerControlRecordsUseOnFakeConnection() throws Exception {
        Path script = Files.createTempFile("aquaflow-fake-startup-sql-", ".sql");
        try {
            Files.writeString(script, "USE unconfirmed_business;\n");
            var probe = new StartupSqlProbe();
            sqlContext(probe).withPropertyValues("spring.sql.init.mode=always", "spring.sql.init.schema-locations=" + script.toUri())
                    .run(context -> {
                        assertNull(context.getStartupFailure()); assertEquals(List.of("USE unconfirmed_business"), probe.sql);
                        assertEquals(1, probe.beanStarts); assertTrue(probe.connections > 0);
                    });
        } finally { Files.deleteIfExists(script); }
    }
    @Test void actualInitializerBlocksStartupSqlBeforeDataSourceCreation() throws Exception {
        Path script = Files.createTempFile("aquaflow-fake-startup-sql-", ".sql");
        try {
            Files.writeString(script, "USE unconfirmed_business;\n");
            for (String[] settings : List.of(
                    new String[]{"spring.sql.init.mode=always", "spring.sql.init.schema-locations=" + script.toUri()},
                    new String[]{"spring.sql.init.mode=always", "spring.sql.init.data-locations=" + script.toUri()},
                    new String[]{"spring.sql.init.mode=never", "spring.sql.init.schema-locations=" + script.toUri()},
                    new String[]{"spring.sql.init.schema-locations[0]=" + script.toUri()})) {
                var probe = new StartupSqlProbe();
                sqlContext(probe).withInitializer(new TestDatabaseTargetGuard.Initializer(TestDatabaseTargetGuardTest::target))
                        .withPropertyValues(settings).run(context -> {
                            assertNotNull(context.getStartupFailure()); assertEquals(0, probe.beanStarts);
                            assertEquals(0, probe.connections); assertTrue(probe.sql.isEmpty());
                        });
            }
        } finally { Files.deleteIfExists(script); }
    }
    @Test void preparedSchemaContextPinsNeverAndDoesNotRunSqlInitializer() {
        for (String[] settings : List.of(new String[]{}, new String[]{"spring.sql.init.mode=never"})) {
            var probe = new StartupSqlProbe();
            sqlContext(probe).withInitializer(new TestDatabaseTargetGuard.Initializer(TestDatabaseTargetGuardTest::target))
                    .withPropertyValues(settings).run(context -> {
                        assertNull(context.getStartupFailure()); assertEquals(1, probe.beanStarts);
                        assertEquals("never", context.getEnvironment().getProperty("spring.sql.init.mode"));
                        assertEquals("NEVER", context.getBean(SqlInitializationProperties.class).getMode().name());
                        assertEquals(0, probe.connections); assertTrue(probe.sql.isEmpty());
                    });
        }
    }
    @Test void opaqueConfigurationSourceCannotHideDatasourceOverrides() {
        var env = new MockEnvironment().withProperty("spring.datasource.url", URL);
        env.getPropertySources().addFirst(new org.springframework.core.env.PropertySource<String>("opaque", "fake") {
            @Override public Object getProperty(String key) {
                return key.equals("spring.datasource.hikari.connection-init-sql") ? "USE aquaflow" : null;
            }
        });
        assertThrows(IllegalStateException.class, () -> TestDatabaseTargetGuard.assertEnvironment(env, target()));
    }
    @Test void baseClassActuallyClearsOnlyAfterSameConnectionValidation() throws Exception {
        var fake = new FakeConnection(URL, NAME, NAME);
        AbstractIntegrationTest.resetConfirmedDatabase(fake.connection(), target());
        assertEquals(List.of("SELECT DATABASE()", "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
                "SET FOREIGN_KEY_CHECKS=0", "TRUNCATE TABLE `station`", "SET FOREIGN_KEY_CHECKS=1"), fake.sql);
        assertEquals(1, fake.statements);
    }
    @Test void actualJdbcMetadataMismatchStopsBeforeEvenSelectDatabase() {
        for (String url : List.of("jdbc:mysql://192.0.2.11:3306/" + NAME, "jdbc:mysql://127.0.0.1:3307/" + NAME,
                "jdbc:mysql://127.0.0.1:3306/aquaflow_test_other", URL + "?propertiesTransform=Transform")) {
            var fake = new FakeConnection(url, NAME, NAME);
            assertThrows(IllegalStateException.class, () -> AbstractIntegrationTest.resetConfirmedDatabase(fake.connection(), target()));
            assertTrue(fake.sql.isEmpty()); assertEquals(0, fake.statements);
        }
    }
    @Test void actualCatalogMismatchStopsBeforeSqlAndNoConnectionIsSwapped() {
        var fake = new FakeConnection(URL, "aquaflow_test_other", NAME);
        assertThrows(IllegalStateException.class, () -> AbstractIntegrationTest.resetConfirmedDatabase(fake.connection(), target()));
        assertTrue(fake.sql.isEmpty()); assertEquals(0, fake.statements);
    }
    @Test void serverCurrentDatabaseMismatchStopsBeforeTableDiscoveryOrTruncate() {
        var fake = new FakeConnection(URL, NAME, "customer_business_test");
        assertThrows(IllegalStateException.class, () -> AbstractIntegrationTest.resetConfirmedDatabase(fake.connection(), target()));
        assertEquals(List.of("SELECT DATABASE()"), fake.sql);
    }
    @Test void failedClearingDoesNotPretendSuccessOrPerformCleanup() {
        var fake = new FakeConnection(URL, NAME, NAME); fake.failTruncate = true;
        assertThrows(SQLException.class, () -> AbstractIntegrationTest.resetConfirmedDatabase(fake.connection(), target()));
        assertEquals("TRUNCATE TABLE `station`", fake.sql.get(fake.sql.size() - 1));
        assertTrue(fake.sql.stream().noneMatch(q -> q.startsWith("DROP")));
    }

    /** SQL is recorded in memory only, including the unsafe control; this never wraps a driver. */
    private static final class StartupSqlProbe {
        final List<String> sql = new ArrayList<>(); int beanStarts, connections;
        DataSource datasource() {
            beanStarts++;
            return FakeConnection.proxy(DataSource.class, (object, method, args) -> switch (method.getName()) {
                case "getConnection" -> { connections++; yield connection(); }
                case "toString" -> "StartupSqlFakeDataSource";
                case "hashCode" -> System.identityHashCode(object);
                case "equals" -> object == args[0];
                default -> throw new AssertionError("Unexpected fake DataSource call: " + method.getName());
            });
        }
        Connection connection() {
            return FakeConnection.proxy(Connection.class, (object, method, args) -> switch (method.getName()) {
                case "createStatement" -> FakeConnection.proxy(Statement.class, (o, m, a) -> switch (m.getName()) {
                    case "execute" -> { sql.add((String) a[0]); yield false; }
                    case "getWarnings", "getResultSet", "close" -> null;
                    case "getMoreResults" -> false;
                    case "getUpdateCount" -> -1;
                    case "toString" -> "StartupSqlFakeStatement";
                    default -> throw new AssertionError("Unexpected fake Statement call: " + m.getName());
                });
                case "getAutoCommit" -> true;
                case "isClosed", "isReadOnly" -> false;
                case "close" -> null;
                case "toString" -> "StartupSqlFakeConnection";
                default -> throw new AssertionError("Unexpected fake Connection call: " + method.getName());
            });
        }
    }
    private static final class FakeConnection {
        final String url, catalog, selected; final List<String> sql = new ArrayList<>(); int statements; boolean failTruncate;
        FakeConnection(String url, String catalog, String selected) { this.url = url; this.catalog = catalog; this.selected = selected; }
        Connection connection() {
            return proxy(Connection.class, (object, method, args) -> switch (method.getName()) {
                case "getCatalog" -> catalog;
                case "getMetaData" -> proxy(DatabaseMetaData.class, (o, m, a) -> { if (m.getName().equals("getURL")) return url; throw new AssertionError(m.getName()); });
                case "createStatement" -> { statements++; yield statement(); }
                default -> throw new AssertionError(method.getName());
            });
        }
        Statement statement() {
            return proxy(Statement.class, (object, method, args) -> {
                if (method.getName().equals("close")) return null;
                String query = (String) args[0]; sql.add(query);
                if (method.getName().equals("executeQuery")) return result(query.equals("SELECT DATABASE()") ? selected : "station");
                if (failTruncate && query.startsWith("TRUNCATE")) throw new SQLException("fake failure");
                if (method.getName().equals("execute")) return false;
                throw new AssertionError(method.getName());
            });
        }
        ResultSet result(String text) {
            int[] row = {0};
            return proxy(ResultSet.class, (object, method, args) -> switch (method.getName()) {
                case "next" -> ++row[0] == 1;
                case "getString" -> text;
                case "close" -> null;
                default -> throw new AssertionError(method.getName());
            });
        }
        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
