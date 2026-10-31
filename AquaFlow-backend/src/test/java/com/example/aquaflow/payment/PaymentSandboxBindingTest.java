package com.example.aquaflow.payment;

import com.example.aquaflow.config.PaymentSandboxDatasourceConfiguration.BoundDataSource;
import com.example.aquaflow.support.PaymentSandboxFixtures;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Execute the deployed policy with JDBC doubles; all identifiers are synthetic and never resolved. */
class PaymentSandboxBindingTest {
    @Test void explicitRemoteBindingDoesNotInferPurposeFromNames() {
        var binding=PaymentSandboxBinding.from(PaymentSandboxFixtures.environment());
        assertEquals("prod-labelled-synthetic",binding.environmentId());
        assertEquals("synthetic_trial",binding.database());
    }
    @Test void missingRequiredSettingsAndRuntimeIdentityRefuseBeforeConnection() {
        for(String key:List.of("spring.datasource.url","spring.datasource.username","spring.datasource.password","jwt.secret",
                "wechat.miniapp.appid","wechat.miniapp.secret","wechat.miniapp.staff-appid","wechat.miniapp.staff-secret",
                "app.cors-allowed-origins","app.payment.sandbox.environment-id","app.payment.sandbox.service-id",
                "app.payment.sandbox.binding-id","app.payment.sandbox.runtime-environment-id","app.payment.sandbox.runtime-service-id",
                "app.payment.sandbox.db-host","app.payment.sandbox.db-port","app.payment.sandbox.db-name","app.payment.sandbox.db-server-uuid")) {
            var e=PaymentSandboxFixtures.environment().withProperty(key,"");
            var source=mock(DataSource.class);
            assertThrows(IllegalStateException.class,()->new MockPaymentPolicy(e,source),key);verifyNoInteractions(source);
        }
    }
    @Test void testLocalProdAndUnknownProfileMixesCannotActivateCloudSandbox() {
        for(String other:List.of("test","local","prod","production","staging")) {
            var e=PaymentSandboxFixtures.environment();e.setActiveProfiles("payment-sandbox",other);
            assertThrows(IllegalStateException.class,()->PaymentSandboxBinding.from(e));
        }
    }
    @Test void unknownTargetPlaceholdersPlaintextAndDriverOverridesRefuse() {
        for(String value:List.of(PaymentSandboxFixtures.URL.replace("synthetic-db.internal","unapproved.internal"),
                PaymentSandboxFixtures.URL.replace(":3306/",":3307/"),PaymentSandboxFixtures.URL.replace("synthetic_trial","another_trial"),
                PaymentSandboxFixtures.URL.replace("REQUIRED","DISABLED"),PaymentSandboxFixtures.URL+"&sessionVariables=x",
                PaymentSandboxFixtures.URL+"&sslMode=REQUIRED",PaymentSandboxFixtures.URL.replace("sslMode=REQUIRED","useSSL=false"))) {
            assertThrows(IllegalStateException.class,()->PaymentSandboxBinding.from(PaymentSandboxFixtures.environment().withProperty("spring.datasource.url",value)),value);
        }
        for(String key:List.of("spring.datasource.hikari.connection-init-sql","spring.datasource.hikari.catalog",
                "spring.datasource.hikari.data-source-properties.sessionVariables","spring.sql.init.schema-locations","spring.flyway.enabled","spring.config.location")) {
            assertThrows(IllegalStateException.class,()->PaymentSandboxBinding.from(PaymentSandboxFixtures.environment().withProperty(key,"synthetic-override")),key);
        }
        for(String key:List.of("app.payment.sandbox.runtime-environment-id","app.payment.sandbox.runtime-service-id"))
            assertThrows(IllegalStateException.class,()->PaymentSandboxBinding.from(PaymentSandboxFixtures.environment().withProperty(key,"wrong-identity")));
        assertThrows(IllegalStateException.class,()->PaymentSandboxBinding.from(PaymentSandboxFixtures.environment().withProperty("app.dev-login-enabled","true")));
        assertThrows(IllegalStateException.class,()->PaymentSandboxBinding.from(PaymentSandboxFixtures.environment().withProperty("aquaflow.barrel.independent-rights-enabled","false")));
        assertThrows(IllegalStateException.class,()->PaymentSandboxBinding.from(PaymentSandboxFixtures.environment().withProperty("app.payment.sandbox.binding-id","__REQUIRED__")));
    }

    private Connection connection() throws Exception {
        var c=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(c.getMetaData()).thenReturn(metadata);when(metadata.getURL()).thenReturn(PaymentSandboxFixtures.URL);
        when(c.getCatalog()).thenReturn("synthetic_trial");
        var identity=mock(Statement.class);var marker=mock(Statement.class);when(c.createStatement()).thenReturn(identity,marker);
        var id=mock(ResultSet.class);when(identity.executeQuery(anyString())).thenReturn(id);when(id.next()).thenReturn(true);
        when(id.getString(1)).thenReturn("synthetic_trial");when(id.getString(2)).thenReturn(PaymentSandboxFixtures.UUID);
        var row=mock(ResultSet.class);when(marker.executeQuery(anyString())).thenReturn(row);when(row.next()).thenReturn(true,false);
        when(row.getInt(1)).thenReturn(1);when(row.getString(2)).thenReturn("PAYMENT_SANDBOX");
        when(row.getString(3)).thenReturn("prod-labelled-synthetic");when(row.getString(4)).thenReturn("synthetic-payment-service");
        when(row.getString(5)).thenReturn("synthetic-binding-001");when(row.getString(6)).thenReturn(PaymentSandboxFixtures.UUID);
        return c;
    }
    @Test void verifiedConnectionAndCustomerCapabilityStillRequireWhitelist() throws Exception {
        var source=mock(DataSource.class);var connection=connection();when(source.getConnection()).thenReturn(connection);
        var policy=new MockPaymentPolicy(PaymentSandboxFixtures.environment(),source);
        assertTrue(policy.allows(7L));assertFalse(policy.allows(8L));
        var auth=(java.util.Map<?,?>)policy.channel(7L).get("simulationAuthorization");
        assertEquals("PAYMENT_SANDBOX",auth.get("scope"));assertEquals(7L,auth.get("customerId"));
        assertFalse((Boolean)policy.channel(8L).get("enabled"));assertFalse(policy.channel(8L).containsKey("simulationAuthorization"));
    }
    @Test void actualUrlCatalogInstanceAndEveryMarkerFieldMustMatchBeforeConnectionEscapes() throws Exception {
        for(int field=0;field<10;field++) {
            var c=connection();
            if(field==0)when(c.getMetaData().getURL()).thenReturn(PaymentSandboxFixtures.URL+"&useUnicode=true");
            else if(field==1)when(c.getCatalog()).thenReturn("another_db");
            else {
                Statement id=c.createStatement(), marker=c.createStatement();
                when(c.createStatement()).thenReturn(id,marker);
                if(field==2)when(id.executeQuery(anyString()).getString(1)).thenReturn("another_db");
                else if(field==3)when(id.executeQuery(anyString()).getString(2)).thenReturn("wrong-instance");
                else if(field==9)when(marker.executeQuery(anyString()).next()).thenReturn(true,true);
                else when(marker.executeQuery(anyString()).getString(field-2)).thenReturn("wrong-marker");
            }
            var source=mock(DataSource.class);when(source.getConnection()).thenReturn(c);
            var bound=new BoundDataSource(source,PaymentSandboxBinding.from(PaymentSandboxFixtures.environment()));
            assertThrows(IllegalStateException.class,bound::getConnection,"field "+field);verify(c).close();
        }
    }
    @Test void missingMarkerNeverReturnsConnectionAndEveryBorrowRechecks() throws Exception {
        var good=connection();var missing=connection();
        Statement id=missing.createStatement(), marker=missing.createStatement();when(missing.createStatement()).thenReturn(id,marker);
        when(marker.executeQuery(anyString())).thenThrow(new SQLException("synthetic missing marker"));
        var source=mock(DataSource.class);when(source.getConnection()).thenReturn(good,missing);
        var bound=new BoundDataSource(source,PaymentSandboxBinding.from(PaymentSandboxFixtures.environment()));
        assertSame(good,bound.getConnection());assertThrows(IllegalStateException.class,bound::getConnection);verify(missing).close();
    }
}
