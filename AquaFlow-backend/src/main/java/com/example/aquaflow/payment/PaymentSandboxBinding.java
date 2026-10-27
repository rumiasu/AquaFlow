package com.example.aquaflow.payment;

import org.springframework.core.env.Environment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/** Dedicated sandbox deployment binding; names and profile labels alone never authorize a database. */
public record PaymentSandboxBinding(String environmentId, String serviceId, String bindingId,
                                    String host, String port, String database, String serverUuid, String jdbcUrl) {
    public static final String PROFILE = "payment-sandbox";
    public static final String MARKER_TABLE = "aquaflow_payment_sandbox_identity";
    private static final String PREFIX = "app.payment.sandbox.";
    private static final Pattern JDBC = Pattern.compile("jdbc:mysql://([a-z0-9.-]+):([1-9][0-9]{0,4})/([A-Za-z][A-Za-z0-9_]{0,63})\\?(.+)");
    private static final Map<String,String> OPTIONS = Map.of("useUnicode","true", "characterEncoding","utf-8|UTF-8",
            "serverTimezone","Asia/Shanghai|UTC", "allowPublicKeyRetrieval","true|false",
            "sslMode","REQUIRED|VERIFY_CA|VERIFY_IDENTITY");

    public static boolean active(Environment env) { return Arrays.asList(env.getActiveProfiles()).contains(PROFILE); }
    private static IllegalStateException refuse() { return new IllegalStateException("模拟支付隔离绑定未核实，拒绝连接或使用该测试渠道；检查专用部署清单与数据库标记"); }
    private static String required(Environment env,String key) {
        String value;
        try { value=env.getProperty(key); } catch (IllegalArgumentException unresolved) { throw refuse(); }
        if(value==null || value.isBlank() || !value.equals(value.trim()) || value.contains("${")
                || value.contains("__") || value.contains("*") || value.contains("<") || value.contains("example.invalid")) throw refuse();
        return value;
    }

    /** Validate before any connection. Runtime IDs must come from the reviewed platform deployment binding. */
    public static PaymentSandboxBinding from(Environment env) {
        if(!Set.copyOf(Arrays.asList(env.getActiveProfiles())).equals(Set.of(PROFILE))) throw refuse();
        requireRuntimeConfiguration(env);
        if(!env.getProperty("app.payment.mock-isolated-environment",Boolean.class,false)
                || env.getProperty("app.dev-login-enabled",Boolean.class,false)
                || !"never".equals(env.getProperty("spring.sql.init.mode"))) throw refuse();
        String environment=required(env,PREFIX+"environment-id"), service=required(env,PREFIX+"service-id");
        String binding=required(env,PREFIX+"binding-id"), host=required(env,PREFIX+"db-host"), port=required(env,PREFIX+"db-port");
        String db=required(env,PREFIX+"db-name"), uuid=required(env,PREFIX+"db-server-uuid");
        if(!environment.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || !service.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                || !binding.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,99}")
                || !uuid.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || !environment.equals(required(env,PREFIX+"runtime-environment-id"))
                || !service.equals(required(env,PREFIX+"runtime-service-id"))) throw refuse();
        String url=required(env,"spring.datasource.url");
        var match=JDBC.matcher(url);
        if(!match.matches() || !host.equals(match.group(1)) || !port.equals(match.group(2))
                || !db.equals(match.group(3)) || Integer.parseInt(port)>65535 || !validHost(host)) throw refuse();
        Set<String> seen=new HashSet<>();
        for(String pair:match.group(4).split("&",-1)) {
            String[] entry=pair.split("=",-1);
            if(entry.length!=2 || !seen.add(entry[0]) || !OPTIONS.containsKey(entry[0]) || !entry[1].matches(OPTIONS.get(entry[0]))) throw refuse();
        }
        if(!seen.contains("sslMode")) throw refuse();
        rejectConnectionOverrides(env);
        return new PaymentSandboxBinding(environment,service,binding,host,port,db,uuid,url);
    }

    /** Sandbox has the same required login/data configuration as the release entry; no local fallbacks. */
    public static void requireRuntimeConfiguration(Environment env) {
        for(String key:List.of("spring.datasource.url","spring.datasource.username","spring.datasource.password",
                "jwt.secret","wechat.miniapp.appid","wechat.miniapp.secret","wechat.miniapp.staff-appid",
                "wechat.miniapp.staff-secret","app.cors-allowed-origins")) {
            String value;
            try { value=env.getProperty(key); } catch(IllegalArgumentException missing) { throw refuse(); }
            if(value==null || value.isBlank() || value.contains("${") || value.equals("__REQUIRED__")) throw refuse();
        }
        if(env.getProperty("jwt.secret").length()<32 || env.getProperty("app.cors-allowed-origins").contains("*")) throw refuse();
        if(!"com.mysql.cj.jdbc.Driver".equals(env.getProperty("spring.datasource.driver-class-name","com.mysql.cj.jdbc.Driver"))) throw refuse();
    }

    private static boolean validHost(String host) {
        if(host.length()>253 || host.equals("0.0.0.0") || host.endsWith(".")) return false;
        if(host.matches("[0-9.]+")) {
            String[] parts=host.split("\\.",-1);
            if(parts.length!=4) return false;
            for(String p:parts) if(!p.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(p)>255) return false;
            return true;
        }
        return Arrays.stream(host.split("\\.",-1)).allMatch(p->p.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"));
    }

    private static String normalize(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""); }
    private static void rejectConnectionOverrides(Environment env) {
        if(!(env instanceof ConfigurableEnvironment configured)) throw refuse();
        Set<String> allowed=Set.of("springdatasourceurl","springdatasourceusername","springdatasourcepassword","springdatasourcedriverclassname",
                "springdatasourcehikarimaximumpoolsize","springdatasourcehikariminimumidle","springdatasourcehikariconnectiontimeout",
                "springdatasourcehikarivalidationtimeout","springdatasourcehikariidletimeout","springdatasourcehikarimaxlifetime",
                "springdatasourcehikarileakdetectionthreshold","springdatasourcehikaripoolname");
        for(var source:configured.getPropertySources()) if(source instanceof EnumerablePropertySource<?> names) {
            for(String name:names.getPropertyNames()) {
                String n=normalize(name);
                if(n.startsWith("springdatasource") && !allowed.contains(n)) throw refuse();
                if((n.startsWith("springsqlinit") && !n.equals("springsqlinitmode")) || n.startsWith("springflyway")
                        || n.startsWith("springliquibase") || n.startsWith("springjpa") || n.startsWith("springhibernate")
                        || n.startsWith("springr2dbc") || n.startsWith("springbatchjdbc") || n.startsWith("springquartzjdbc")
                        || n.startsWith("springsessionjdbc")) throw refuse();
            }
        }
        for(String key:List.of("spring.config.import","spring.config.location","spring.config.additional-location",
                "spring.profiles.include","spring.application.json","spring.autoconfigure.exclude")) {
            if(env.getProperty(key)!=null && !env.getProperty(key).isBlank()) throw refuse();
        }
    }

    /** Verify each borrowed connection before a caller can read/write business data; marker provisioning is separate. */
    public void verify(Connection con) throws SQLException {
        if(!jdbcUrl.equals(con.getMetaData().getURL()) || !database.equals(con.getCatalog())) throw refuse();
        try(var statement=con.createStatement();var result=statement.executeQuery("select database(), @@server_uuid")) {
            if(!result.next() || !database.equals(result.getString(1)) || !serverUuid.equals(result.getString(2))) throw refuse();
        }
        try(var statement=con.createStatement();var result=statement.executeQuery(
                "select id,purpose,environment_id,service_id,binding_id,server_uuid from "+MARKER_TABLE)) {
            if(!result.next() || result.getInt(1)!=1 || !"PAYMENT_SANDBOX".equals(result.getString(2))
                    || !environmentId.equals(result.getString(3)) || !serviceId.equals(result.getString(4))
                    || !bindingId.equals(result.getString(5)) || !serverUuid.equals(result.getString(6)) || result.next()) throw refuse();
        } catch(SQLException missingOrInvalid) { throw new IllegalStateException("模拟支付隔离标记不可验证，拒绝业务连接",missingOrInvalid); }
    }
}
