package com.example.aquaflow.service;

import com.example.aquaflow.mapper.OrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时任务**自己跑挂了**必须落一条 SYSTEM 告警（2026-09-27）。
 *
 * <p><b>为什么是单测、不是集成测试</b>：这个缺口要造的"失败"是**基础设施故障**（库重启 / 连接池耗尽 /
 * SQL 回归）。在集成测试里最直接的做法是 `DROP TABLE`，但那会**污染整个套件**：
 * `AbstractIntegrationTest.resetDatabase()` 是 **TRUNCATE 全表、不重建结构** ——
 * 丢掉的结构在同一次运行里再也回不来，后面所有用例一起红。
 * （我第一版就是那么写的，写完才发现；记在这里免得下一个人重走。）
 * 所以这里用手写桩直接让依赖抛错：**不碰任何库**，只验"异常被兜住 + 告警落对"。</p>
 *
 * <p><b>为什么不用 Mockito</b>：本仓测试从来不用它（全仓零命中），而且实测在本机受限环境下
 * byte-buddy 的 mock maker 起不来（`Could not initialize plugin: interface
 * org.mockito.plugins.MockMaker`）—— 它需要动态 attach 到 JVM。手写桩几十行，且零依赖。</p>
 *
 * <p><b>本类锁住三件事</b>：</p>
 * <ol>
 *   <li>依赖抛异常时，{@code @Scheduled} 方法**不许把异常抛出去** ——
 *       抛出去就等于丢给调度器，而运维唯一的入口是 {@code alert_log}（系统告警没有 HTTP 入口）；</li>
 *   <li>必须落的是 **SYSTEM** 告警（任务自身故障 = 系统故障 ⇒ 投系统管理员，
 *       且 {@code relatedId} 为 null，绝不会误投给某个站长）；</li>
 *   <li>告警正文要说清"结果不可用"+ 带上底层原因，让人一眼知道该做什么，而不是只写"失败"。</li>
 * </ol>
 */
@DisplayName("定时任务自身失败 · 必须落 SYSTEM 告警（不能只打日志）")
class ScheduledJobFailureAlertTest {

    /** 记录调用、不落库的告警桩。 */
    static class RecordingAlertService implements AlertService {
        int systemCalls = 0;
        int stationCalls = 0;
        String source;
        String title;
        String content;
        String relatedType;
        Long relatedId;

        @Override
        public void systemFault(String source, String title, String content, String relatedType, Long relatedId) {
            systemCalls++;
            this.source = source;
            this.title = title;
            this.content = content;
            this.relatedType = relatedType;
            this.relatedId = relatedId;
        }

        @Override
        public void stationFault(Long stationId, String level, String source, String title, String content,
                                 String relatedType, Long relatedId) {
            stationCalls++;
        }
    }

    /**
     * 让对账第一步就抛错：覆写 `queryForObject` 即足够 ——
     * `ReconciliationService` 的两条计数路径都走它（见该类里 `jdbcTemplate.queryForObject`）。
     * 继承 JdbcTemplate 只为借一个实例，父类构造要 DataSource，但被覆写的方法不会用到它。
     */
    static class ExplodingJdbcTemplate extends JdbcTemplate {
        ExplodingJdbcTemplate() {
            super(new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                    null, "jdbc:mysql://127.0.0.1:3306/never_used", "u", "p"));
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            throw new UncategorizedSQLException(sql, sql,
                    new java.sql.SQLException("模拟：数据库连接不可用"));
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            throw new UncategorizedSQLException(sql, sql,
                    new java.sql.SQLException("模拟：数据库连接不可用"));
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            throw new UncategorizedSQLException(sql, sql,
                    new java.sql.SQLException("模拟：数据库连接不可用"));
        }
    }

    /** 取超时单时抛错的 OrderMapper 桩（MyBatis 接口方法很多，用动态代理只实现被调的那个）。 */
    static OrderMapper explodingOrderMapper(String message) {
        return (OrderMapper) java.lang.reflect.Proxy.newProxyInstance(
                OrderMapper.class.getClassLoader(),
                new Class<?>[]{OrderMapper.class},
                (proxy, method, args) -> {
                    if ("listTimedOutWechatOrders".equals(method.getName())) {
                        throw new UncategorizedSQLException("listTimedOutWechatOrders", "",
                                new java.sql.SQLException(message));
                    }
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == List.class) return List.of();
                    // ⚠️ 别在这里抛 UnsupportedOperationException：那会被测的 catch 当成
                    //    "任务失败"从而**假绿**（测出来的是桩没实现，而不是被测代码的行为）
                    return null;
                });
    }

    /** 记下调用次数的 Mapper 桩，用于断言"关闭时不该查库"。 */
    static class RecordingOrderMapper implements java.lang.reflect.InvocationHandler {
        int listCalls = 0;

        OrderMapper build() {
            return (OrderMapper) java.lang.reflect.Proxy.newProxyInstance(
                    OrderMapper.class.getClassLoader(), new Class<?>[]{OrderMapper.class}, this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if ("listTimedOutWechatOrders".equals(method.getName())) {
                listCalls++;
                return List.of();
            }
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == List.class) return List.of();
            return null;
        }
    }

    private UnpaidWechatOrderSweeper sweeper(OrderMapper mapper, PaymentService payments,
                                            AlertService alerts, int timeoutMinutes) {
        UnpaidWechatOrderSweeper s = new UnpaidWechatOrderSweeper();
        // 本类是字段注入（@Autowired 字段），单测里用反射摆好
        ReflectionTestUtils.setField(s, "orderMapper", mapper);
        ReflectionTestUtils.setField(s, "paymentService", payments);
        ReflectionTestUtils.setField(s, "alertService", alerts);
        ReflectionTestUtils.setField(s, "timeoutMinutes", timeoutMinutes);
        return s;
    }

    @Test
    @DisplayName("日结对账：底层抛错 ⇒ 不抛出 + 落 SYSTEM 告警 + 正文含原因")
    void reconcileFailureIsAlerted() {
        RecordingAlertService alerts = new RecordingAlertService();
        // [F-16] ReconciliationService 现在从注入的 Clock 取业务时间；本类是纯单测、不起 Spring 上下文，
        // 所以显式给一个时钟（这里走不到 persistResults —— jdbc 桩先炸，时钟只是为了让构造器成立）。
        ReconciliationService svc = new ReconciliationService(new ExplodingJdbcTemplate(), alerts,
                new com.example.aquaflow.util.BusinessTime(java.time.Clock.systemDefaultZone()));

        // 关键：**不许抛出**（抛了就等于丢给调度器，谁都看不到）
        svc.dailyReconcile();

        assertEquals(1, alerts.systemCalls, "任务跑挂必须落一条 SYSTEM 告警");
        assertEquals(0, alerts.stationCalls, "任务自身故障与某个水站无关，不该发运营告警");
        assertEquals("DailyReconcile", alerts.source);
        assertTrue(alerts.content != null && alerts.content.contains("日结对账未完成"),
                "正文要说清'本次结果不可用'，不能只写'失败'，实际=" + alerts.content);
        assertTrue(alerts.content.contains("数据库连接不可用"),
                "正文要带上底层原因，否则运维不知道去查什么，实际=" + alerts.content);
        assertEquals(null, alerts.relatedId, "relatedId 应为空（不是某一条业务记录的问题）");
    }

    @Test
    @DisplayName("未付单扫描：取单查询抛错 ⇒ 不抛出 + 落 SYSTEM 告警")
    void sweeperFailureIsAlerted() {
        RecordingAlertService alerts = new RecordingAlertService();
        UnpaidWechatOrderSweeper s = sweeper(
                explodingOrderMapper("模拟：查询超时"), null, alerts, 30);

        s.sweep();

        assertEquals(1, alerts.systemCalls, "扫描任务跑挂必须落 SYSTEM 告警");
        assertEquals("UnpaidWechatOrderSweeper", alerts.source);
        assertTrue(alerts.content != null && alerts.content.contains("本轮扫描未完成"),
                "正文要说明'超时未付的微信单仍占着库存'这层后果，实际=" + alerts.content);
    }

    @Test
    @DisplayName("阈值 ≤ 0（关闭自动取消）：不该触发任何告警，也不该查库")
    void disabledSweeperStaysSilent() {
        RecordingAlertService alerts = new RecordingAlertService();
        RecordingOrderMapper mapper = new RecordingOrderMapper();
        UnpaidWechatOrderSweeper s = sweeper(mapper.build(), null, alerts, 0);

        s.sweep();

        assertEquals(0, mapper.listCalls, "阈值 ≤0 时应直接返回，不该查库");
        assertEquals(0, alerts.systemCalls, "关闭状态下不该有任何告警（否则天天误报）");
    }
}
