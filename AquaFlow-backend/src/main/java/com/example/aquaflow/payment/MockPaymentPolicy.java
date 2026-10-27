package com.example.aquaflow.payment;

import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.AuthContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

/** 模拟渠道只可在已核实的本机合成测试或独立云 sandbox 运行；小程序版本不构成授权。 */
@Component
public final class MockPaymentPolicy {
    public static final String PREFIX = "SIM-WX:";
    private final boolean enabled;
    private final Set<Long> customers;
    private final PaymentSandboxBinding sandbox;

    public MockPaymentPolicy(Environment environment, DataSource datasource) {
        enabled = environment.getProperty("app.payment.mock-wechat-pay", Boolean.class, false);
        sandbox = PaymentSandboxBinding.active(environment) ? PaymentSandboxBinding.from(environment) : null;
        if (!enabled) { customers = Set.of(); return; }
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        String url = environment.getProperty("spring.datasource.url", "");
        // 2026-10-26：原开关对所有非 prod 环境生效；现在须同时满足隔离声明、受限库和逐户授权。
        // 2026-10-27：云 sandbox 走显式目标/实例/标记校验；test 的本机规则不随之放宽。
        if (profiles.contains("prod") || (sandbox == null && (profiles.stream().anyMatch(p -> !Set.of("local", "test").contains(p))
                || !profiles.contains("test")))
                || !environment.getProperty("app.payment.mock-isolated-environment", Boolean.class, false)
                || (sandbox == null && !safeUrl(url))) {
            throw new IllegalStateException("模拟支付仅允许已核实的独立 payment-sandbox 或本机合成 test 环境");
        }
        String ids = environment.getProperty("app.payment.mock-customer-ids", "");
        if (!ids.matches("[1-9][0-9]*(,[1-9][0-9]*)*"))
            throw new IllegalStateException("模拟支付必须配置明确的测试客户编号白名单，禁止空值和通配符");
        try { customers = Arrays.stream(ids.split(",")).map(Long::valueOf).collect(Collectors.toUnmodifiableSet()); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("模拟支付测试客户编号无效"); }
        String schema = url.substring(url.indexOf('/', "jdbc:mysql://".length()) + 1).split("\\?", 2)[0];
        // 核实际连接而不只看配置：连接池 init SQL / catalog 覆盖不能把安全库名导向业务库。
        try (var connection = datasource.getConnection()) {
            if(sandbox!=null) sandbox.verify(connection);
            else try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT DATABASE()")) {
                if (!url.equals(connection.getMetaData().getURL()) || !schema.equals(connection.getCatalog())
                        || !result.next() || !schema.equals(result.getString(1)))
                    throw new IllegalStateException("模拟支付实际数据库连接与隔离目标不一致");
            }
        } catch (SQLException failure) { throw new IllegalStateException("无法核实模拟支付隔离数据库，拒绝启用"); }
    }

    private static boolean safeUrl(String url) {
        var match = java.util.regex.Pattern.compile("jdbc:mysql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/(aquaflow_test(?:_pay_[a-z0-9]{1,24})?)(?:\\?(.+))?").matcher(url);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 65535) return false;
        if (java.util.regex.Pattern.compile("backup|archive|restore|prod|live").matcher(match.group(2)).find()) return false;
        if (match.group(3) == null) return true;
        Map<String,String> options = Map.of("useUnicode","true", "characterEncoding","utf-8|UTF-8",
                "serverTimezone","Asia/Shanghai|UTC", "allowPublicKeyRetrieval","true|false", "useSSL","true|false");
        var seen = new java.util.HashSet<String>();
        for (String pair : match.group(3).split("&", -1)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2 || !seen.add(parts[0]) || !options.containsKey(parts[0])
                    || !parts[1].matches(options.get(parts[0]))) return false;
        }
        return true;
    }

    public boolean allows(Long customer) { return enabled && customer != null && customers.contains(customer); }

    /** Authenticated customer capability; the write path independently rechecks policy and order ownership. */
    public Map<String,Object> channel(Long customer) {
        Map<String,Object> channel=PayMethod.payChannel(PayMethod.WECHAT,allows(customer));
        if(allows(customer)) {
            Map<String,Object> authorization=new java.util.LinkedHashMap<>();
            authorization.put("version",1);authorization.put("customerId",customer);
            authorization.put("scope",sandbox==null?"LOCAL_TEST":"PAYMENT_SANDBOX");
            if(sandbox!=null) {
                authorization.put("environmentId",sandbox.environmentId());authorization.put("serviceId",sandbox.serviceId());
                authorization.put("bindingId",sandbox.bindingId());
            }
            channel.put("simulationAuthorization",authorization);
        }
        return channel;
    }

    /** 调用方仍须先核对订单/购买归属；员工不可用代录收款入口触发模拟付款。 */
    public void requireSelf(Long customer) {
        if (!allows(customer) || !customer.equals(AuthContext.requireCustomerId()))
            throw new BusinessException("当前账号未获模拟支付授权");
    }

    /** 在第一次 INSERT 前标记，网络中断、重放、重启后仍可辨认；不信任请求备注。 */
    public void prepare(PaymentRecord payment) {
        if (enabled && Integer.valueOf(PayMethod.WECHAT).equals(payment.getPaymentMethod())) {
            requireSelf(payment.getCustomerId());
            payment.setTransactionNo(PREFIX + UUID.randomUUID());
        }
    }

    public boolean canRefund(PaymentRecord original) {
        return original != null && allows(original.getCustomerId()) && original.isSimulated();
    }

    /** 模拟模式不能把历史真实或未知来源微信原款当成模拟退款；员工原有站别判权仍由业务服务执行。 */
    public void requireRefund(PaymentRecord original) {
        if (original != null && Integer.valueOf(PayMethod.WECHAT).equals(original.getPaymentMethod()) && !canRefund(original))
            throw new BusinessException("仅可模拟退还已标识的授权测试原款，真实微信退款尚未接入");
    }

    public void markRefund(PaymentRecord original, PaymentRecord refund) {
        requireRefund(original);
        if (original.isSimulated()) refund.setTransactionNo(PREFIX + UUID.randomUUID());
    }
}
