package com.example.aquaflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文冒烟测试：只验证 Spring 容器能完整装配起来（Bean 循环依赖、配置缺失会在此暴露）。
 *
 * <p><b>为什么必须显式声明 {@code @ActiveProfiles({"local", "test"})}（2026-09-14 修复）：</b>
 * 此前这个类只有裸 {@code @SpringBootTest}，不指定 profile 时会落到 {@code spring.profiles.default=local}
 * ——也就是连真实库 {@code aquaflow}。它是全套测试里唯一不受
 * {@code AbstractIntegrationTest} 那层“库名必须含 test”安全护栏保护的测试类。
 * 今天它只 {@code contextLoads()} 不写库所以看起来无害，但只要有人在里面加一个写操作的用例，
 * 就会直接打进与生产同源的真实库。顺序与基类一致：后声明的 profile 覆盖前者。</p>
 *
 * <p>注意：运行本测试前需先执行 {@code scripts/provision-test-db.sh} 建好 {@code aquaflow_test}，
 * 这与 {@code integration} 包下所有用例的前提相同。</p>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AquaFlowApplicationTests {

    @Test
    void contextLoads() {
    }

}
