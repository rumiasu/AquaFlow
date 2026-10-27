package com.example.aquaflow.support;

import org.springframework.mock.env.MockEnvironment;

/** Non-routable configuration for boundary tests; never authorizes a deployed target. */
public final class PaymentSandboxFixtures {
    public static final String UUID="00000000-0000-0000-0000-000000000001";
    public static final String URL="jdbc:mysql://synthetic-db.internal:3306/synthetic_trial?sslMode=REQUIRED";
    private PaymentSandboxFixtures() {}
    public static MockEnvironment environment() {
        var e=new MockEnvironment();e.setActiveProfiles("payment-sandbox");
        return e.withProperty("app.payment.mock-wechat-pay","true")
                .withProperty("app.payment.mock-isolated-environment","true").withProperty("app.payment.mock-customer-ids","7")
                .withProperty("app.dev-login-enabled","false").withProperty("spring.sql.init.mode","never")
                .withProperty("spring.datasource.url",URL).withProperty("spring.datasource.username","synthetic")
                .withProperty("spring.datasource.password","discard-synthetic-only")
                .withProperty("jwt.secret","synthetic-sandbox-placeholder-minimum-32-characters")
                .withProperty("wechat.miniapp.appid","wx0000000000000001").withProperty("wechat.miniapp.secret","synthetic")
                .withProperty("wechat.miniapp.staff-appid","wx0000000000000002").withProperty("wechat.miniapp.staff-secret","synthetic")
                .withProperty("app.cors-allowed-origins","https://sandbox.synthetic.test")
                .withProperty("app.payment.sandbox.environment-id","prod-labelled-synthetic")
                .withProperty("app.payment.sandbox.service-id","synthetic-payment-service")
                .withProperty("app.payment.sandbox.runtime-environment-id","prod-labelled-synthetic")
                .withProperty("app.payment.sandbox.runtime-service-id","synthetic-payment-service")
                .withProperty("app.payment.sandbox.binding-id","synthetic-binding-001")
                .withProperty("app.payment.sandbox.db-host","synthetic-db.internal")
                .withProperty("app.payment.sandbox.db-port","3306")
                .withProperty("app.payment.sandbox.db-name","synthetic_trial")
                .withProperty("app.payment.sandbox.db-server-uuid",UUID);
    }
}
