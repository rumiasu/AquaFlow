package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.UserToken;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.UserTokenMapper;
import com.example.aquaflow.service.WeChatLoginService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.JwtUtil;
import com.example.aquaflow.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class LoginController {

    @Autowired
    private WeChatLoginService weChatLoginService;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private StaffMapper staffMapper;

    @Autowired
    private UserTokenMapper userTokenMapper;

    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 微信小程序登录 ====================

    /**
     * 微信小程序登录
     * POST /api/auth/wx-login  { code: "wx.login()返回的code" }
     */
    @PostMapping("/wx-login")
    public Result<Map<String, Object>> wxLogin(@RequestBody Map<String, String> params) {
        String code = params.get("code");
        if (code == null || code.isEmpty()) {
            return Result.error("登录code不能为空");
        }

        Map<String, Object> wxSession = weChatLoginService.code2Session(code);
        String openid = wxSession.get("openid").toString();

        Customer customer = customerMapper.findByOpenid(openid);
        if (customer == null) {
            customer = new Customer();
            customer.setName("微信用户");
            customer.setPhone("");
            customer.setOpenid(openid);
            customer.setCustomerType(1);
            customer.setRole(1);
            customer.setCreateTime(LocalDateTime.now());
            customer.setUpdateTime(LocalDateTime.now());
            customerMapper.insertWithOpenid(customer);
        }

        // 生成 JWT 双 Token
        String role = mapCustomerRole(customer);
        String accessToken = jwtUtil.generateAccessToken(
                customer.getId(), "customer", role,
                customer.getStationId(), null);
        String refreshToken = jwtUtil.generateRefreshToken(customer.getId(), "customer");

        // 存储 refresh_token
        saveRefreshToken(customer.getId(), "customer", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("phone", customer.getPhone());
        data.put("role", role);
        data.put("stationId", customer.getStationId());
        data.put("isNew", customer.getCreateTime().isEqual(customer.getUpdateTime()));

        return Result.success(data);
    }

    // ==================== 更新资料 ====================

    /**
     * 更新客户微信绑定的昵称和手机号
     * POST /api/auth/update-profile
     */
    @PostMapping("/update-profile")
    public Result<Void> updateProfile(@RequestBody Map<String, Object> params) {
        Integer customerId = AuthContext.getUserId();
        if (customerId == null) {
            return Result.error("用户ID不能为空");
        }

        Customer customer = customerMapper.getById(customerId);
        if (customer == null) {
            return Result.error("用户不存在");
        }

        String nickname = (String) params.get("nickname");
        String phone = (String) params.get("phone");

        if (nickname != null && !nickname.isEmpty()) {
            customer.setName(nickname);
        }
        if (phone != null && !phone.isEmpty()) {
            customer.setPhone(phone);
        }
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.update(customer);

        return Result.success();
    }

    // ==================== 管理后台登录 ====================

    /**
     * 统一登录（管理后台）
     * POST /api/auth/login
     * { username: "admin", password: "xxx" }         → 厂长
     * { username: "张建国", password: "xxx" }         → 站长（STATION_MANAGER）
     * { username: "李师傅", password: "xxx" }         → 配送员（DELIVERY）
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params) {
        String username = params.get("username");
        String password = params.get("password");

        if (username == null || password == null) {
            return Result.error("用户名和密码不能为空");
        }

        // 1. 超级管理员 → 厂长角色（走 staff 表 admin 记录）
        if ("admin".equals(username)) {
            Staff admin = staffMapper.findByName("admin");
            if (admin != null) {
                // 兼容明文密码和 BCrypt
                if (password.equals(admin.getPassword()) || PasswordUtil.matches(password, admin.getPassword())) {
                    return buildStaffLoginResult(admin);
                }
            }
            return Result.error("用户名或密码错误");
        }

        // 2. 员工登录：姓名 + 密码
        Staff staff = staffMapper.findByName(username);
        if (staff != null) {
            // 兼容明文密码和 BCrypt
            if (password.equals(staff.getPassword()) || PasswordUtil.matches(password, staff.getPassword())) {
                return buildStaffLoginResult(staff);
            }
        }

        // 3. 兼容旧的站长登录（customer 表，迁移过渡期）
        Customer manager = customerMapper.findManagerByUsername(username);
        if (manager != null && "123456".equals(password)) {
            return buildCustomerLoginResult(manager);
        }

        return Result.error("用户名或密码错误");
    }

    // ==================== 开发模式登录 ====================

    /**
     * 开发模式登录（跳过微信验证，直接用 openid 登录）
     * POST /api/auth/dev-login
     */
    @PostMapping("/dev-login")
    public Result<Map<String, Object>> devLogin(@RequestBody Map<String, String> params) {
        String openid = params.getOrDefault("openid", "dev-openid-001");
        String nickname = params.getOrDefault("nickname", "测试用户");

        Customer customer = customerMapper.findByOpenid(openid);
        if (customer == null) {
            customer = new Customer();
            customer.setName(nickname);
            customer.setPhone("");
            customer.setOpenid(openid);
            customer.setCustomerType(1);
            customer.setRole(2);
            customer.setStationId(1);
            customer.setCreateTime(LocalDateTime.now());
            customer.setUpdateTime(LocalDateTime.now());
            customerMapper.insertWithOpenid(customer);
        }

        // 生成 JWT 双 Token
        String accessToken = jwtUtil.generateAccessToken(
                customer.getId(), "customer", "manager",
                customer.getStationId(), null);
        String refreshToken = jwtUtil.generateRefreshToken(customer.getId(), "customer");

        saveRefreshToken(customer.getId(), "customer", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("phone", customer.getPhone());
        data.put("role", "manager");
        data.put("stationId", customer.getStationId());
        return Result.success(data);
    }

    // ==================== Token 刷新 ====================

    /**
     * 刷新 access_token
     * POST /api/auth/refresh  { refreshToken: "xxx" }
     */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody Map<String, String> params) {
        String refreshToken = params.get("refreshToken");
        if (refreshToken == null || refreshToken.isEmpty()) {
            return Result.error("refreshToken不能为空");
        }

        // 校验 refresh_token 签名和有效期
        if (!jwtUtil.validateToken(refreshToken)) {
            return Result.error("refreshToken已过期，请重新登录");
        }

        io.jsonwebtoken.Claims claims = jwtUtil.parseToken(refreshToken);
        String tokenType = claims.get("tokenType", String.class);
        if (!"refresh".equals(tokenType)) {
            return Result.error("无效的refreshToken");
        }

        Integer userId = claims.get("userId", Integer.class);
        String userType = claims.get("userType", String.class);

        // 验证 refresh_token 在数据库中存在（未被登出）
        UserToken storedToken = userTokenMapper.findByRefreshToken(refreshToken);
        if (storedToken == null) {
            return Result.error("令牌已失效，请重新登录");
        }

        // 查询用户最新信息（角色/水站可能已变更）
        String role;
        Integer stationId = null;
        Integer factoryId = null;

        if ("staff".equals(userType)) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null) return Result.error("用户不存在");
            role = mapStaffRole(staff);
            stationId = staff.getStationId();
            factoryId = staff.getFactoryId();
        } else {
            Customer customer = customerMapper.getById(userId);
            if (customer == null) return Result.error("用户不存在");
            role = mapCustomerRole(customer);
            stationId = customer.getStationId();
        }

        // 签发新 access_token（refresh_token 不变）
        String newAccessToken = jwtUtil.generateAccessToken(userId, userType, role, stationId, factoryId);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", newAccessToken);
        data.put("refreshToken", refreshToken);
        return Result.success(data);
    }

    // ==================== 登出 ====================

    /**
     * 登出（清除 refresh_token，使续期失效）
     * POST /api/auth/logout
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                Integer userId = jwtUtil.getUserId(token);
                String userType = jwtUtil.getUserType(token);
                userTokenMapper.deleteByUser(userId, userType);
            }
        }
        return Result.success();
    }

    // ==================== 验证登录状态 ====================

    /**
     * 验证登录状态（从 JWT 解析当前用户，不再依赖前端传 customerId）
     * GET /api/auth/me
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        Integer userId = AuthContext.getUserId();
        String userType = AuthContext.getUserType();

        if (userId == null) {
            return Result.error("未登录");
        }

        Map<String, Object> data = new HashMap<>();

        if ("staff".equals(userType)) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null) return Result.error("用户不存在");
            data.put("staffId", staff.getId());
            data.put("nickname", staff.getName());
            data.put("phone", staff.getPhone());
            data.put("role", mapStaffRole(staff));
            data.put("stationId", staff.getStationId());
            data.put("userType", "staff");
        } else {
            Customer customer = customerMapper.getById(userId);
            if (customer == null) return Result.error("用户不存在");
            data.put("customerId", customer.getId());
            data.put("nickname", customer.getName());
            data.put("phone", customer.getPhone());
            data.put("role", mapCustomerRole(customer));
            data.put("stationId", customer.getStationId());
            data.put("userType", "customer");
        }

        return Result.success(data);
    }

    // ==================== 修改密码 ====================

    /**
     * 修改密码（员工/管理员）
     * POST /api/auth/change-password  { oldPassword: "xxx", newPassword: "xxx" }
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody Map<String, String> params) {
        Integer userId = AuthContext.getUserId();
        String userType = AuthContext.getUserType();

        if (!"staff".equals(userType)) {
            return Result.error("仅支持员工修改密码");
        }

        Staff staff = staffMapper.getById(userId);
        if (staff == null) return Result.error("用户不存在");

        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        if (oldPassword == null || newPassword == null) {
            return Result.error("旧密码和新密码不能为空");
        }

        // 校验旧密码（兼容迁移过渡期：password 为空时用初始密码 123456）
        if (staff.getPassword() != null) {
            if (!PasswordUtil.matches(oldPassword, staff.getPassword())) {
                return Result.error("旧密码错误");
            }
        } else {
            if (!"123456".equals(oldPassword)) {
                return Result.error("旧密码错误");
            }
        }

        if (newPassword.length() < 6) {
            return Result.error("新密码长度不能少于6位");
        }

        staff.setPassword(PasswordUtil.encode(newPassword));
        staffMapper.update(staff);
        return Result.success();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建员工登录结果（JWT 双 Token + 用户信息）
     */
    private Result<Map<String, Object>> buildStaffLoginResult(Staff staff) {
        String role = mapStaffRole(staff);
        String accessToken = jwtUtil.generateAccessToken(
                staff.getId(), "staff", role,
                staff.getStationId(), staff.getFactoryId());
        String refreshToken = jwtUtil.generateRefreshToken(staff.getId(), "staff");

        saveRefreshToken(staff.getId(), "staff", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("username", staff.getName());
        data.put("nickname", staff.getName());
        data.put("role", role);
        data.put("stationId", staff.getStationId());
        data.put("staffId", staff.getId());
        data.put("staffRole", staff.getRole());
        return Result.success(data);
    }

    /**
     * 构建客户登录结果（JWT 双 Token + 用户信息）
     */
    private Result<Map<String, Object>> buildCustomerLoginResult(Customer customer) {
        String role = mapCustomerRole(customer);
        String accessToken = jwtUtil.generateAccessToken(
                customer.getId(), "customer", role,
                customer.getStationId(), null);
        String refreshToken = jwtUtil.generateRefreshToken(customer.getId(), "customer");

        saveRefreshToken(customer.getId(), "customer", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("role", role);
        data.put("stationId", customer.getStationId());
        return Result.success(data);
    }

    /** 存储 refresh_token 到 user_token 表 */
    private void saveRefreshToken(Integer userId, String userType, String refreshToken) {
        UserToken userToken = new UserToken();
        userToken.setUserId(userId);
        userToken.setUserType(userType);
        userToken.setRefreshToken(refreshToken);
        userToken.setExpireTime(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshTokenExpiry() / 1000));
        userToken.setCreateTime(LocalDateTime.now());
        userTokenMapper.insert(userToken);
    }

    /** 员工角色 → 前端角色名 */
    private String mapStaffRole(Staff staff) {
        if ("FACTORY_ADMIN".equals(staff.getRole())) return "factory";
        if ("STATION_MANAGER".equals(staff.getRole())) return "manager";
        return "delivery";
    }

    /** 客户角色 → 前端角色名 */
    private String mapCustomerRole(Customer customer) {
        return customer.getRole() != null && customer.getRole() == 2 ? "manager" : "delivery";
    }
}
