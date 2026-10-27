package com.example.aquaflow.payment;

import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.sql.*;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 无网络/数据库连接；数据源替身用于执行真实配置与实际连接匹配门禁。 */
class MockPaymentPolicyTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/aquaflow_test_pay_unit";
    private MockEnvironment environment() {
        var e = new MockEnvironment().withProperty("app.payment.mock-wechat-pay","true")
                .withProperty("app.payment.mock-isolated-environment","true")
                .withProperty("app.payment.mock-customer-ids","7")
                .withProperty("spring.datasource.url",URL);
        // Local JDBC doubles exercise the original test boundary; cloud sandbox has its own explicit binding tests.
        e.setActiveProfiles("test"); return e;
    }
    private DataSource datasource(String url, String catalog, String actual) throws Exception {
        var ds=mock(DataSource.class); var c=mock(Connection.class); var m=mock(DatabaseMetaData.class);
        var s=mock(Statement.class); var r=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c); when(c.getMetaData()).thenReturn(m); when(m.getURL()).thenReturn(url);
        when(c.getCatalog()).thenReturn(catalog); when(c.createStatement()).thenReturn(s);
        when(s.executeQuery("SELECT DATABASE()")).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn(actual);
        return ds;
    }
    private MockPaymentPolicy policy() throws Exception { return new MockPaymentPolicy(environment(),datasource(URL,"aquaflow_test_pay_unit","aquaflow_test_pay_unit")); }
    private void customer(long id) { AuthContext.set(new AuthContext.AuthUser(id,"customer",null,null)); }
    private PaymentRecord payment(MockPaymentPolicy p) {
        customer(7); var r=new PaymentRecord(); r.setCustomerId(7L); r.setPaymentMethod(1); r.setStatus(1);
        r.setAmount(new BigDecimal("40.00")); p.prepare(r); return r;
    }
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void defaultOffNeverConnectsOrSimulates() throws Exception {
        var ds=mock(DataSource.class); var p=new MockPaymentPolicy(new MockEnvironment(),ds);
        assertFalse(p.allows(7L)); verifyNoInteractions(ds);
        assertThrows(BusinessException.class,()->p.requireSelf(7L));
    }
    @Test void flagAloneAndMissingOrWildcardAccountsRefuse() throws Exception {
        var ds=mock(DataSource.class);
        var bare=new MockEnvironment().withProperty("app.payment.mock-wechat-pay","true");
        assertThrows(IllegalStateException.class,()->new MockPaymentPolicy(bare,ds));
        for(String ids:new String[]{"","*","7,","0","-1","7, 8","99999999999999999999999"}) {
            var e=environment().withProperty("app.payment.mock-customer-ids",ids);
            assertThrows(IllegalStateException.class,()->new MockPaymentPolicy(e,ds),ids);
        }
        verifyNoInteractions(ds);
    }
    @Test void serverTestModeAloneSupportsExistingProdEntryWithoutDatabaseOrCustomerPreparation() {
        var e = new MockEnvironment().withProperty("app.payment.test-mode", "true")
                .withProperty("app.payment.mock-wechat-pay", "false")
                .withProperty("spring.datasource.url", "jdbc:mysql://synthetic-managed-db.invalid:3306/aquaflow_trial");
        e.setActiveProfiles("prod");var ds=mock(DataSource.class);var p=new MockPaymentPolicy(e,ds);
        for(long id:new long[]{7,1001}) {
            assertTrue(p.allows(id));customer(id);p.requireSelf(id);
            var proof=(java.util.Map<?,?>)p.channel(id).get("simulationAuthorization");
            assertEquals("SERVER_TEST",proof.get("scope"));assertEquals(id,proof.get("customerId"));
            assertEquals("测试付款（不扣真实款）",p.channel(id).get("label"));
        }
        assertFalse(p.allows(null));assertFalse(p.allows(0L));verifyNoInteractions(ds);
        customer(7);assertThrows(BusinessException.class,()->p.requireSelf(1001L));
        AuthContext.set(new AuthContext.AuthUser(7L,"staff","STATION_MANAGER",1L));
        assertThrows(BusinessException.class,()->p.requireSelf(7L));
    }
    @Test void prodMixedAndUnknownProfilesRefuse() {
        for(String profile:new String[]{"prod","production","staging"}) {
            var e=environment(); e.setActiveProfiles("test",profile);
            assertThrows(IllegalStateException.class,()->new MockPaymentPolicy(e,mock(DataSource.class)));
        }
    }
    @Test void businessRemoteBackupAndDriverRewritesRefuseBeforeConnection() {
        for(String url:new String[]{"jdbc:mysql://127.0.0.1:3306/aquaflow","jdbc:mysql://10.0.0.1:3306/aquaflow_test",
                URL+"_backup",URL+"?sessionVariables=sql_mode=ANSI",URL+"?useSSL=false&useSSL=true",URL+"?socketFactory=x",
                "jdbc:mysql://127.0.0.1:99999/aquaflow_test"}) {
            var ds=mock(DataSource.class);
            assertThrows(IllegalStateException.class,()->new MockPaymentPolicy(environment().withProperty("spring.datasource.url",url),ds),url);
            verifyNoInteractions(ds);
        }
    }
    @Test void actualUrlCatalogAndSelectedDatabaseMustAllMatch() throws Exception {
        for(String[] mismatch:new String[][]{{URL,"aquaflow","aquaflow_test_pay_unit"},{URL,"aquaflow_test_pay_unit","aquaflow"},
                {"jdbc:mysql://127.0.0.1:3307/aquaflow_test_pay_unit","aquaflow_test_pay_unit","aquaflow_test_pay_unit"}}) {
            var ds=datasource(mismatch[0],mismatch[1],mismatch[2]);
            assertThrows(IllegalStateException.class,()->new MockPaymentPolicy(environment(),ds));
        }
    }
    @Test void allowedOptionsIncludingTimezoneCannotChangeSchemaParsing() throws Exception {
        String url=URL+"?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false";
        var p=new MockPaymentPolicy(environment().withProperty("spring.datasource.url",url),datasource(url,"aquaflow_test_pay_unit","aquaflow_test_pay_unit"));
        assertTrue(p.allows(7L));assertFalse(p.allows(8L));
    }
    @Test void onlyExplicitCustomerSelfMayPrepareAndRefundNeedsDurableOriginal() throws Exception {
        var p=policy(); var r=payment(p); assertTrue(r.isSimulated()); assertTrue(r.getMethodText().contains("模拟"));
        customer(8); assertThrows(BusinessException.class,()->p.requireSelf(7L)); assertThrows(BusinessException.class,()->p.requireSelf(8L));
        AuthContext.set(new AuthContext.AuthUser(7L,"staff","STATION_MANAGER",1L));
        assertThrows(BusinessException.class,()->p.requireSelf(7L));
        var unknown=new PaymentRecord(); unknown.setPaymentMethod(1); unknown.setCustomerId(7L); unknown.setNote("模拟");
        assertThrows(BusinessException.class,()->p.requireRefund(unknown));
        var refund=new PaymentRecord(); refund.setPaymentMethod(1); p.markRefund(r,refund); assertTrue(refund.isSimulated());
        var off=new MockPaymentPolicy(new MockEnvironment(),mock(DataSource.class)); assertFalse(off.canRefund(r));
    }
    @Test void internalFailureCancelDelayAndRetryDoNotInventSuccess() throws Exception {
        var p=policy(); var a=new MockWechatPaymentAdapter(p); var r=payment(p); var count=new AtomicInteger();
        Runnable confirm=()->{count.incrementAndGet();r.setStatus(2);};
        for(var outcome:new MockWechatPaymentAdapter.Outcome[]{MockWechatPaymentAdapter.Outcome.CANCEL,MockWechatPaymentAdapter.Outcome.FAIL,MockWechatPaymentAdapter.Outcome.DELAY}) {
            a.complete(r,outcome,confirm); assertEquals(1,r.getStatus()); assertEquals(0,count.get());
        }
        for(int i=0;i<20;i++)a.complete(r,MockWechatPaymentAdapter.Outcome.SUCCESS,confirm);
        assertEquals(1,count.get());
    }
    @Test void closedRefundedMissingProofAndInvalidAmountsNeverConfirm() throws Exception {
        var p=policy();var a=new MockWechatPaymentAdapter(p);
        for(int status:new int[]{0,3,4}) {var r=payment(p);r.setStatus(status);assertThrows(BusinessException.class,()->a.complete(r,MockWechatPaymentAdapter.Outcome.SUCCESS,()->fail("confirmed terminal")));}
        for(BigDecimal amount:new BigDecimal[]{null,BigDecimal.ZERO,new BigDecimal("-1"),new BigDecimal("0.001")}) {
            var r=payment(p);r.setAmount(amount);assertThrows(BusinessException.class,()->a.complete(r,MockWechatPaymentAdapter.Outcome.SUCCESS,()->fail("confirmed invalid money")));
        }
        var r=payment(p);r.setTransactionNo(null);assertThrows(BusinessException.class,()->a.complete(r,MockWechatPaymentAdapter.Outcome.SUCCESS,()->fail("confirmed unknown source")));
    }
}
