package com.example.aquaflow.service;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.util.PasswordUtil;
import com.example.aquaflow.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** 临时测试认证只读取环境映射；不建账号、不补员工密码、不绑定微信。 */
@Component
public final class TemporaryTestLogin {
    public static final String SOURCE = com.example.aquaflow.util.JwtUtil.TEST_AUTH_SOURCE;
    private final Environment environment;
    private final CustomerMapper customers;
    private final StaffMapper staff;
    private final DataSource datasource;

    public TemporaryTestLogin(Environment environment, CustomerMapper customers, StaffMapper staff, DataSource datasource) {
        this.environment = environment;
        this.customers = customers;
        this.staff = staff;
        this.datasource = datasource;
    }

    public record Account(String name, String hash, String userType, long userId, long stationId) {}
    public record Identity(Account account, String role, Long stationId, String nickname, String phone, String staffRole) {}

    private Map<String,Account> accounts() {
        if (!environment.getProperty("app.test-login.enabled", Boolean.class, false)
                || !environment.getProperty("app.payment.test-mode", Boolean.class, false))
            throw new BusinessException("测试账号登录未开启");
        // 当前云测试沿用 prod 配置名；明确的全测试开关和已核实库共同限制入口，不恢复旧 dev-login。
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.matches("jdbc:mysql://[^/\\s]+/aquaflow_trial(?:\\?[^\\s]*)?"))
            throw new BusinessException("测试账号登录不适用于当前数据库");
        try (var connection = datasource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT DATABASE()")) {
            if (!url.equals(connection.getMetaData().getURL()) || !"aquaflow_trial".equals(connection.getCatalog())
                    || !result.next() || !"aquaflow_trial".equals(result.getString(1)))
                throw new BusinessException("测试账号登录不适用于当前数据库");
        } catch (BusinessException denied) { throw denied; }
        catch (Exception failure) { throw new BusinessException("暂时无法核实测试登录环境"); }
        try {
            JsonNode root = new ObjectMapper().readTree(environment.getProperty("app.test-login.accounts-json", "[]"));
            if (!root.isArray() || root.isEmpty() || root.size() > 10) throw new IllegalArgumentException();
            Map<String,Account> result = new HashMap<>();
            Set<String> identities = new java.util.HashSet<>();
            for (JsonNode row : root) {
                if (!row.isObject()) throw new IllegalArgumentException();
                var fields = row.fieldNames();
                while (fields.hasNext()) if (!Set.of("account","passwordHash","userType","userId","stationId").contains(fields.next()))
                    throw new IllegalArgumentException();
                String name = row.path("account").asText("");
                String hash = row.path("passwordHash").asText("");
                String type = row.path("userType").asText("");
                if (!name.matches("[A-Za-z0-9._-]{1,48}") || !hash.matches("\\$2[aby]\\$(10|11|12|13|14)\\$[./A-Za-z0-9]{53}")
                        || !Set.of("customer","staff").contains(type) || !row.path("userId").isIntegralNumber()
                        || !row.path("stationId").isIntegralNumber() || !row.path("userId").canConvertToLong()
                        || !row.path("stationId").canConvertToLong()) throw new IllegalArgumentException();
                long id = row.path("userId").asLong(), station = row.path("stationId").asLong();
                if (id <= 0 || station <= 0 || !identities.add(type+":"+id)
                        || result.putIfAbsent(name, new Account(name,hash,type,id,station)) != null)
                    throw new IllegalArgumentException();
            }
            return result;
        } catch (Exception invalid) { throw new BusinessException("测试账号配置无效，请联系测试负责人"); }
    }

    public Identity login(String name, String password) {
        Account account = accounts().get(name);
        if (account == null || password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !PasswordUtil.matches(password, account.hash()))
            throw new BusinessException("测试账号或密码错误");
        return identity(account);
    }

    /** 角色与站别只读实际业务行；微信身份不得被测试密码入口接管。 */
    private Identity identity(Account account) {
        if ("customer".equals(account.userType())) {
            Customer customer = customers.getById(account.userId());
            if (customer == null || customer.getOpenid() != null && !customer.getOpenid().isBlank()
                    || customers.countCustomerOfStation(account.userId(), account.stationId()) == 0)
                throw new BusinessException("测试账号关联无效");
            return new Identity(account,"customer",null,customer.getName(),customer.getPhone(),null);
        }
        Staff member = staff.getById(account.userId());
        if (member == null || !Integer.valueOf(1).equals(member.getStatus())
                || member.getOpenid() != null && !member.getOpenid().isBlank()
                || !Long.valueOf(account.stationId()).equals(member.getStationId())
                || member.getRole() == null || !Set.of("STATION_MANAGER","DELIVERY").contains(member.getRole()))
            throw new BusinessException("测试账号关联无效");
        return new Identity(account, "STATION_MANAGER".equals(member.getRole()) ? "manager" : "delivery",
                member.getStationId(),member.getName(),member.getPhone(),member.getRole());
    }

    public static boolean marked(Claims claims) {
        return claims.containsKey("authSource") || claims.containsKey("testAccount") || claims.containsKey("testBinding");
    }

    /** 每次请求与刷新都重验，关闭功能或移除/改密后，已发出的测试会话也失效。 */
    public Account requireSession(Claims claims) {
        if (!marked(claims)) return null;
        if (!SOURCE.equals(claims.get("authSource"))) throw new BusinessException("测试登录已失效，请重新登录");
        Map<String,Account> configured = accounts();
        if (!(claims.get("userId") instanceof Number id))
            throw new BusinessException("测试登录已失效，请重新登录");
        boolean compactRefresh = "refresh".equals(claims.get("tokenType")) && !claims.containsKey("testAccount");
        // Configuration forbids duplicate (userType,userId) mappings. The binding also covers
        // account name, password hash and station, so changing any still revokes this session.
        Account account = compactRefresh ? configured.values().stream()
                .filter(value -> value.userId() == id.longValue() && value.userType().equals(claims.get("userType")))
                .findFirst().orElse(null) : configured.get(claims.get("testAccount",String.class));
        if (account == null) throw new BusinessException("测试登录已失效，请重新登录");
        String expectedBinding = compactRefresh ? JwtUtil.compactTestBinding(binding(account)) : binding(account);
        if (!expectedBinding.equals(claims.get("testBinding",String.class))
                || !account.userType().equals(claims.get("userType"))
                || id.longValue()!=account.userId())
            throw new BusinessException("测试登录已失效，请重新登录");
        identity(account);
        return account;
    }

    public String binding(Account account) {
        try {
            String input=account.name()+"\n"+account.userType()+"\n"+account.userId()+"\n"+account.stationId()+"\n"+account.hash();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException("无法建立测试会话"); }
    }
}
