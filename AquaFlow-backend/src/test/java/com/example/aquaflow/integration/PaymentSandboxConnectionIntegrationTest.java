package com.example.aquaflow.integration;

import com.example.aquaflow.config.PaymentSandboxDatasourceConfiguration.BoundDataSource;
import com.example.aquaflow.payment.PaymentSandboxBinding;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.support.PaymentSandboxFixtures;
import com.example.aquaflow.support.TestDatabaseTargetGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL boundary probes, exclusively inside the already confirmed/discardable CI test endpoint. */
class PaymentSandboxConnectionIntegrationTest extends AbstractIntegrationTest {
    @Autowired Environment environment;
    private BoundDataSource boundary() {
        var target=TestDatabaseTargetGuard.current();
        String uuid=jdbc.queryForObject("select @@server_uuid",String.class);
        String secure="jdbc:mysql://"+target.host()+":"+target.port()+"/"+target.name()+"?sslMode=REQUIRED";
        jdbc.execute("create table if not exists aquaflow_payment_sandbox_identity(id int primary key,purpose varchar(32),environment_id varchar(100),service_id varchar(100),binding_id varchar(100),server_uuid varchar(36))");
        jdbc.update("insert into aquaflow_payment_sandbox_identity values(1,'PAYMENT_SANDBOX','prod-labelled-synthetic','synthetic-payment-service','synthetic-binding-001',?)",uuid);
        var e=PaymentSandboxFixtures.environment().withProperty("spring.datasource.url",secure)
                .withProperty("app.payment.sandbox.db-host",target.host()).withProperty("app.payment.sandbox.db-port",target.port())
                .withProperty("app.payment.sandbox.db-name",target.name()).withProperty("app.payment.sandbox.db-server-uuid",uuid);
        // Same confirmed endpoint/catalog; only upgrade this probe's connection to encrypted MySQL.
        var source=new DriverManagerDataSource(secure,environment.getProperty("spring.datasource.username"),environment.getProperty("spring.datasource.password"));
        return new BoundDataSource(source,PaymentSandboxBinding.from(e));
    }
    @Test void approvedBindingReturnsEncryptedConnectionWithoutChangingBusinessData() throws Exception {
        try(var c=boundary().getConnection();var statement=c.createStatement();var result=statement.executeQuery("select count(*) from orders")) {
            assertTrue(result.next());assertEquals(0,result.getInt(1));
            try(var cipher=statement.executeQuery("show session status like 'Ssl_cipher'")) {
                assertTrue(cipher.next());assertFalse(cipher.getString(2).isBlank());
            }
        }
        assertEquals(0,intOf("select count(*) from payment_record"));
    }
    @Test void markerTamperingPreventsTheNextBusinessConnection() throws Exception {
        var source=boundary();try(var c=source.getConnection()){assertFalse(c.isClosed());}
        jdbc.update("update aquaflow_payment_sandbox_identity set binding_id='wrong-binding' where id=1");
        assertThrows(IllegalStateException.class,source::getConnection);
        assertEquals(0,intOf("select count(*) from orders"));assertEquals(0,intOf("select count(*) from payment_record"));
    }
    @Test void missingMarkerPreventsTheNextBusinessConnection() {
        var source=boundary();jdbc.update("delete from aquaflow_payment_sandbox_identity where id=1");
        assertThrows(IllegalStateException.class,source::getConnection);
        assertEquals(0,intOf("select count(*) from deposit_record"));
    }
}
