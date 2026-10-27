package com.example.aquaflow.config;

import com.example.aquaflow.controller.DevLoginController;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.support.PaymentSandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring profile/BPP selection without any network or business database. */
class PaymentSandboxStartupTest {
    @Test void sandboxHasNeitherPasswordInitializationNorDevLoginAndDoesNotBorrowConnection() {
        var source=mock(DataSource.class);var staff=mock(StaffMapper.class);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(PaymentSandboxFixtures.environment());
            context.register(PaymentSandboxDatasourceConfiguration.class,PasswordInitializer.class,DevLoginController.class);
            context.registerBean(DataSource.class,()->source);context.registerBean(StaffMapper.class,()->staff);context.refresh();
            assertTrue(context.getBeansOfType(PasswordInitializer.class).isEmpty());
            assertTrue(context.getBeansOfType(DevLoginController.class).isEmpty());
            assertInstanceOf(PaymentSandboxDatasourceConfiguration.BoundDataSource.class,context.getBean(DataSource.class));
            verifyNoInteractions(source,staff);
        }
    }
    @Test void invalidBindingOrMixedTestProfileStopsContextBeforeDatasourceCreation() {
        for(boolean mixed:new boolean[]{false,true}) {
            var e=PaymentSandboxFixtures.environment();
            if(mixed)e.setActiveProfiles("payment-sandbox","test");else e.setProperty("app.payment.sandbox.binding-id","");
            var created=new java.util.concurrent.atomic.AtomicInteger();
            try(var context=new AnnotationConfigApplicationContext()) {
                context.setEnvironment(e);context.register(PaymentSandboxDatasourceConfiguration.class);
                context.registerBean(DataSource.class,()->{created.incrementAndGet();return mock(DataSource.class);});
                assertThrows(RuntimeException.class,context::refresh);assertEquals(0,created.get());
            }
        }
    }
    @Test void requiredCheckerRetainsStrictSandboxConfigurationAndRejectsDevLoginOverrides() {
        var e=PaymentSandboxFixtures.environment();var checker=new RequiredConfigChecker();
        ReflectionTestUtils.setField(checker,"environment",e);
        ReflectionTestUtils.setField(checker,"jwtSecret",e.getProperty("jwt.secret"));
        ReflectionTestUtils.setField(checker,"wxAppId",e.getProperty("wechat.miniapp.appid"));
        ReflectionTestUtils.setField(checker,"wxSecret",e.getProperty("wechat.miniapp.secret"));
        assertDoesNotThrow(checker::check);
        ReflectionTestUtils.setField(checker,"devLoginEnabled",true);assertThrows(IllegalStateException.class,checker::check);
        ReflectionTestUtils.setField(checker,"devLoginEnabled",false);e.setProperty("wechat.miniapp.staff-secret","");
        assertThrows(IllegalStateException.class,checker::check);
    }
}
