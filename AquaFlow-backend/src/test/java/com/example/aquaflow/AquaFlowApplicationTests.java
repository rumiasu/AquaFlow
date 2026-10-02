package com.example.aquaflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文冒烟测试：只验证 Spring 容器能完整装配起来（Bean 循环依赖、配置缺失会在此暴露）。
 *
 * <p><b>为什么必须显式声明 {@code @ActiveProfiles({"local", "test"})}（2026-09-14 修复）：</b>
 * 此前这个类只有裸 {@code @SpringBootTest}，不指定 profile 时会落到 {@code spring.profiles.default=local}
 * ——也就是连真实库 {@code aquaflow}。顺序与集成测试基类一致：后声明的 profile 覆盖前者。</p>
 *
 * <p><b>当前目标保护（2026-10-02 F-68）：</b>本类虽不继承集成测试基类，
 * 仍由测试资源 {@code META-INF/spring.factories} 注册的
 * {@link com.example.aquaflow.support.TestDatabaseTargetGuard.Initializer} 在数据源 Bean 建立前核对目标。
 * 缺少精确库名确认 {@code AQUAFLOW_ALLOW_DB_RESET} 或完整 TCP 目标确认
 * {@code AQUAFLOW_ALLOW_TEST_DB_TARGET=IPv4:端口/库名} 时禁止启动；库名像测试库不足以放行。</p>
 *
 * <p>运行前须确认目标可清空，并用 {@code scripts/provision-test-db.sh} 准备同一测试库，
 * 默认是 {@code aquaflow_test}。自定义目标时须显式设置一致的 {@code MYSQL_HOST}、
 * {@code MYSQL_PORT}、{@code TEST_DB_NAME} 和 {@code TEST_DB_URL}；本类不经过基类的动态 URL 绑定。</p>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AquaFlowApplicationTests {

    @Test
    void contextLoads() {
    }

}
