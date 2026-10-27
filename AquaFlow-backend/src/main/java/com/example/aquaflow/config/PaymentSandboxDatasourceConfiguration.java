package com.example.aquaflow.config;

import com.example.aquaflow.payment.PaymentSandboxBinding;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** The sandbox boundary belongs to the datasource, so non-payment services cannot bypass it. */
@Configuration(proxyBeanMethods=false)
@Profile("payment-sandbox")
public class PaymentSandboxDatasourceConfiguration {
    @Bean
    static BeanPostProcessor sandboxDatasourceBoundary(Environment environment) {
        var binding=PaymentSandboxBinding.from(environment);
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                return bean instanceof DataSource source ? new BoundDataSource(source,binding) : bean;
            }
        };
    }

    /** No connection is handed to schema guards, schedulers or business code before identity verification. */
    public static final class BoundDataSource extends DelegatingDataSource {
        private final PaymentSandboxBinding binding;
        public BoundDataSource(DataSource target,PaymentSandboxBinding binding) { super(target);this.binding=binding; }
        private Connection verified(Connection connection) throws SQLException {
            try { binding.verify(connection);return connection; }
            catch(SQLException | RuntimeException failure) { connection.close();throw failure; }
        }
        @Override public Connection getConnection() throws SQLException { return verified(super.getConnection()); }
        @Override public Connection getConnection(String username,String password) throws SQLException { return verified(super.getConnection(username,password)); }
    }
}
