package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.WeChatApp;
import com.example.aquaflow.dto.AuthRequestDTO;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.StaffStationApplication;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.entity.UserToken;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StaffStationApplicationMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.mapper.UserTokenMapper;
import com.example.aquaflow.service.WeChatLoginService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.JwtUtil;
import com.example.aquaflow.util.PasswordUtil;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
/**
 * 认证与会话。
 *
 * <p><b>本类不加 {@code @RequireRole} 是正确的</b>：登录相关端点在调用时还没有身份，
 * 加了反而会把自己拦死。覆盖：微信登录（顾客 {@code /wx-login}、员工 {@code /wx-login-staff}）、
 * 角色选择、建站、员工绑定、改资料、账号密码登录、token 刷新 / 登出、{@code /me}、改密码。</p>
 *
 * <p><b>⚠️ {@code /api/auth/refresh} 与 {@code /api/auth/me} 的路径曾被小程序硬编码引用</b>
 * （两端的 {@code utils/request.js} 与 {@code app.js} 的自动续期链路）。改这两个路径会同时打断
 * 双端的 token 续期 —— 现已统一改用 {@code API.REFRESH} / {@code API.ME} 常量引用，
 * 改路径前请先确认两端 {@code config/api.js} 常量已同步。</p>
 */
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
    private StationMapper stationMapper;

    @Autowired
    private StaffStationApplicationMapper appMapper;

    @Autowired
    private UserTokenMapper userTokenMapper;

    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 微信小程序登录 ====================

    /**
     * 微信登录（用户小程序）：仅查 customer 表，与 staff 表完全独立
     */
    @PostMapping("/wx-login")
    public Result<Map<String, Object>> wxLogin(@RequestBody @Valid AuthRequestDTO.WxLogin params) {
        String code = params.getCode();

        Map<String, Object> wxSession = weChatLoginService.code2Session(WeChatApp.CUSTOMER, code);
        String openid = wxSession.get("openid").toString();

        // 仅查 customer，没有则自动注册
        Customer customer = customerMapper.findByOpenid(openid);
        if (customer == null) {
            customer = new Customer();
            customer.setName("微信用户");
            customer.setPhone("");
            customer.setOpenid(openid);
            customer.setCustomerType(1);
            customer.setCreateTime(LocalDateTime.now());
            customer.setUpdateTime(LocalDateTime.now());
            customerMapper.insert(customer);
        }

        String accessToken = jwtUtil.generateAccessToken(
                customer.getId(), "customer", "customer",
                null);
        String refreshToken = jwtUtil.generateRefreshToken(customer.getId(), "customer");

        saveRefreshToken(customer.getId(), "customer", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("phone", customer.getPhone());
        data.put("role", "customer");
        data.put("userType", "customer");
        data.put("isNew", customer.getCreateTime().isEqual(customer.getUpdateTime()));

        return Result.success(data);
    }

    /**
     * 配送端微信登录：任何微信用户可进入。
     * <ul>
     *   <li>staff 存在 → 正常登录 (返回按 station_id 派生的 bindingStatus)</li>
     *   <li>staff 不存在 → 返回 role=UNSELECTED, needSelectRole=true, 交由 /select-role 创建</li>
     * </ul>
     */
    @PostMapping("/wx-login-staff")
    public Result<Map<String, Object>> wxLoginStaff(@RequestBody @Valid AuthRequestDTO.WxLoginStaff params) {
        String code = params.getCode();

        Map<String, Object> wxSession;
        try {
            wxSession = weChatLoginService.code2Session(WeChatApp.STAFF, code);
        } catch (RuntimeException e) {
            return Result.error("微信登录失败: " + e.getMessage());
        }
        String openid = (String) wxSession.get("openid");
        log.info("[wx-login-staff] openid={}", maskOpenid(openid));

        Staff staff = staffMapper.findByOpenid(openid);
        log.info("[wx-login-staff] staff查询结果: staffId={}, role={}, status={}, stationId={}",
                staff != null ? staff.getId() : "null",
                staff != null ? staff.getRole() : "null",
                staff != null ? staff.getStatus() : "null",
                staff != null ? staff.getStationId() : "null");

        if (staff != null) {
            if (staff.getStatus() == null || !Integer.valueOf(1).equals(staff.getStatus())) {
                log.warn("[wx-login-staff] 账号已停用, staffId={}, status={}", staff.getId(), staff.getStatus());
                return Result.error("该账号已停用");
            }
            log.info("[wx-login-staff] 员工已绑定, 开始生成token, staffId={}", staff.getId());
            return buildStaffWxLoginResult(staff);
        }

        // 未绑定任何 staff：虚拟 UNSELECTED 会话
        log.info("[wx-login-staff] 未找到staff, 生成UNSELECTED会话");
        try {
            long virtualUserId = -1 * Math.abs((openid + ":staff:unselected").hashCode());
            String role = "UNSELECTED";
            // 把 openid 签进 token，而不是只放在响应体里让客户端下次再回传 ——
            // select-role 只认 token 里的身份，杜绝"自报 openid 抢绑他人微信"。
            String accessToken = jwtUtil.generateAccessToken(virtualUserId, "staff", role, null, openid);
            String refreshToken = jwtUtil.generateRefreshToken(virtualUserId, "staff");
            saveRefreshToken(virtualUserId, "staff", refreshToken);

            Map<String, Object> data = new HashMap<>();
            data.put("accessToken", accessToken);
            data.put("refreshToken", refreshToken);
            data.put("staffId", null);
            data.put("nickname", "");
            data.put("phone", "");
            data.put("role", role);
            data.put("staffRole", role);
            data.put("stationId", null);
            data.put("bindingStatus", "UNBOUND");
            data.put("userType", "staff");
            data.put("needSelectRole", true);
            data.put("_pendingOpenid", openid);

            log.info("[wx-login-staff] 返回UNSELECTED结果, openid={}", maskOpenid(openid));
            return Result.success(data);
        } catch (Exception e) {
            log.error("[wx-login-staff] UNSELECTED流程异常: openid={}, error={}", maskOpenid(openid), e.getMessage(), e);
            return Result.error("登录失败(UNSELECTED): " + e.getMessage());
        }
    }

    /**
     * 首次进入配送端选择角色 (站长/配送员).
     * <ul>
     *   <li>STATION_MANAGER: 创建 staff, station_id=null (后续调用 /create-station 创建水站再绑定)</li>
     *   <li>DELIVERY: 创建 staff, station_id=null (后续申请绑定水站; 不自动绑定默认站)</li>
     * </ul>
     */
    @PostMapping("/select-role")
    public Result<Map<String, Object>> selectRole(@RequestBody @Valid AuthRequestDTO.SelectRole params) {
        // AQ-006: 权限提权防护 — 仅配送端(staff)账号可选择角色，顾客(userType=customer)必须用 customer 身份。
        // 否则顾客可调用此接口创建 STATION_MANAGER/DELIVERY 员工记录并拿到 staff JWT，完成提权。
        if (!"staff".equals(AuthContext.getUserType())) {
            return Result.error("仅配送端账号可选择角色");
        }

        Long userId = AuthContext.getUserId();
        String roleParam = params.getRole();
        String nickname = params.getNickname();
        String phone = params.getPhone();

        // ===== 身份来源：只认 JWT 里的 pendingOpenid =====
        // [2026-09-12 修复] 旧实现在这里读 params.getPendingOpenid()（客户端请求体里的 _pendingOpenid），
        // 原注释还写着"从JWT中获取openid，而非信任客户端传入"——但它实际做的是后者，
        // 且自认了局限（"userId 是 hash 后的值，这里仅做非空校验"）。
        // 后果：任何持 UNSELECTED token 的人，只要把 _pendingOpenid 换成别人的 openid，
        // 就能把该 openid 绑到自己新建的员工记录上；配合 staff.uk_staff_openid 唯一键，
        // 真实主人之后再登录会直接落到这条被抢绑的记录上。
        // 现在 openid 在 wx-login-staff 签发 token 时就已签入 claims，此处从 AuthContext 读取，
        // 客户端传什么参数都不再影响结果（字段保留仅为兼容旧客户端，不参与判定）。
        String pendingOpenid = AuthContext.getPendingOpenid();
        if (pendingOpenid == null || pendingOpenid.isEmpty()) {
            return Result.error("身份信息缺失，请重新登录");
        }

        if (userId != null && userId > 0) {
            Staff exist = staffMapper.getById(userId);
            if (exist != null) {
                return Result.error("该账号已选择身份，如需切换请联系管理员处理");
            }
        }

        // 该微信已经绑定过员工 → 不能再建一条（否则撞 uk_staff_openid，报的是难懂的数据库错误）
        Staff bound = staffMapper.findByOpenid(pendingOpenid);
        if (bound != null) {
            return Result.error("该微信已绑定员工账号，请直接登录");
        }

        Staff staff = new Staff();
        staff.setName(nickname != null && !nickname.isEmpty() ? nickname : ("STATION_MANAGER".equals(roleParam) ? "站长" : "配送员"));
        staff.setPhone(phone != null ? phone : "");
        staff.setOpenid(pendingOpenid.isEmpty() ? null : pendingOpenid);
        staff.setStationId(null);                       // V1: 初始都 NULL
        staff.setRole(roleParam);
        staff.setStatus(1);
        staff.setCreateTime(LocalDateTime.now());
        staff.setUpdateTime(LocalDateTime.now());
        staffMapper.insert(staff);

        if (userId != null) {
            userTokenMapper.deleteByUser(userId, "staff");
        }

        return buildStaffWxLoginResult(staff);
    }

    /**
     * 站长 (STATION_MANAGER) 创建水站并将自己绑定为该站站长.
     * <p>V1 规则:</p>
     * <ul>
     *   <li>不经过申请表, 不写 staff_station_application</li>
     *   <li>事务: 先 station 插入 → 再 staff.station_id = newStation.id</li>
     *   <li>一个站长账号当前只绑定一个水站 (若已绑定 station_id 则拒绝)</li>
     * </ul>
     */
    @PostMapping("/create-station")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createStationAndBind(@RequestBody @Valid AuthRequestDTO.CreateStation params) {
        String authRole = AuthContext.getRole();
        if (!"STATION_MANAGER".equals(authRole) && !"manager".equals(authRole)) {
            return Result.error("仅站长账号可创建水站");
        }
        Long staffId = AuthContext.getUserId();
        if (staffId == null || staffId <= 0) {
            return Result.error("请先完成身份选择");
        }
        Staff staff = staffMapper.getById(staffId);
        if (staff == null) return Result.error("员工不存在");
        if (staff.getStationId() != null) {
            return Result.error("该账号已绑定水站，请勿重复创建");
        }
        if (!"STATION_MANAGER".equals(staff.getRole())) {
            return Result.error("仅 STATION_MANAGER 可创建水站");
        }

        String name = params.getName();
        String phone = params.getPhone();
        String province = params.getProvince();
        String city = params.getCity();
        String district = params.getDistrict();
        String address = params.getAddress();

        String phoneTrim = phone == null ? "" : phone.trim();

        Station station = new Station();
        station.setName(name.trim());
        station.setPhone(phoneTrim);
        String fullAddress = address == null ? "" : address.trim();
        if (province != null && !province.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(province);
            if (city != null && !city.isEmpty()) sb.append(city);
            if (district != null && !district.isEmpty()) sb.append(district);
            if (!fullAddress.isEmpty()) sb.append(fullAddress);
            fullAddress = sb.toString();
        }
        station.setAddress(fullAddress.isEmpty() ? null : fullAddress);
        station.setStatus(1);
        station.setCreateTime(LocalDateTime.now());
        station.setUpdateTime(LocalDateTime.now());
        stationMapper.insert(station);

        // 绑定当前 staff 为该站站长 → 不写 staff_station_application, 直接更新 station_id
        staff.setStationId(station.getId());
        if ((staff.getPhone() == null || staff.getPhone().isEmpty()) && !phoneTrim.isEmpty()) {
            staff.setPhone(phoneTrim);
        }
        if (staff.getName() == null || staff.getName().isEmpty()) {
            staff.setName("站长");
        }
        staff.setUpdateTime(LocalDateTime.now());
        staffMapper.update(staff);

        // 刷新 token
        String accessToken = jwtUtil.generateAccessToken(staff.getId(), "staff", "STATION_MANAGER", station.getId());
        String refreshToken = jwtUtil.generateRefreshToken(staff.getId(), "staff");
        saveRefreshToken(staff.getId(), "staff", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("id", station.getId());
        data.put("name", station.getName());
        data.put("stationId", station.getId());
        data.put("staffId", staff.getId());
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("nickname", staff.getName());
        data.put("phone", staff.getPhone());
        data.put("role", "STATION_MANAGER");
        data.put("staffRole", staff.getRole());
        data.put("bindingStatus", deriveBindingStatus(staff));
        data.put("userType", "staff");
        return Result.success(data);
    }

    /**
     * 员工绑定微信：输入姓名+手机号，匹配 staff 记录并绑定当前微信 openid
     */
    @PostMapping("/bind-staff")
    public Result<Map<String, Object>> bindStaff(@RequestBody @Valid AuthRequestDTO.BindStaff params) {
        String code = params.getCode();
        String name = params.getName();
        String phone = params.getPhone();

        Map<String, Object> wxSession = weChatLoginService.code2Session(WeChatApp.STAFF, code);
        String openid = wxSession.get("openid").toString();

        // [AQ-040] 绑定失败限流：同一 姓名+手机 组合 15 分钟内失败超限即锁定，
        // 防止攻击者用"姓名+手机"暴力抢绑从未绑定过微信的员工账号。
        String bindKey = "bind:" + name + ":" + phone;
        if (tooManyAttempts(bindKey)) {
            return Result.error("尝试次数过多，请 15 分钟后再试");
        }

        Staff staff = staffMapper.findByName(name);
        if (staff == null) {
            recordFailure(bindKey);
            return Result.error("未找到该员工账号");
        }
        if (staff.getStatus() != null && !Integer.valueOf(1).equals(staff.getStatus())) {
            recordFailure(bindKey);
            return Result.error("该账号已停用");
        }
        if (staff.getPhone() == null || !staff.getPhone().equals(phone)) {
            recordFailure(bindKey);
            return Result.error("手机号不匹配");
        }
        if (staff.getOpenid() != null && !staff.getOpenid().equals(openid)) {
            recordFailure(bindKey);
            return Result.error("该账号已绑定其他微信");
        }
        clearFailures(bindKey);
        log.info("[AQ-040] 员工微信绑定成功: staffId={}, name={}", staff.getId(), staff.getName());

        staff.setOpenid(openid);
        staffMapper.update(staff);

        return buildStaffWxLoginResult(staff, true);
    }

    // ==================== buildStaffWxLoginResult (bindingStatus 派生, 不读 DB 废弃字段) ====================

    private Result<Map<String, Object>> buildStaffWxLoginResult(Staff staff) {
        return buildStaffWxLoginResult(staff, false);
    }

    private Result<Map<String, Object>> buildStaffWxLoginResult(Staff staff, boolean fromBindStaff) {
        try {
            String role = mapStaffRole(staff);
            Long stationId = (staff.getStationId() != null && staff.getStationId() == 0) ? null : staff.getStationId();
            String accessToken = jwtUtil.generateAccessToken(
                    staff.getId(), "staff", role,
                    stationId);
            String refreshToken = jwtUtil.generateRefreshToken(staff.getId(), "staff");

            saveRefreshToken(staff.getId(), "staff", refreshToken);

            Map<String, Object> data = new HashMap<>();
            data.put("accessToken", accessToken);
            data.put("refreshToken", refreshToken);
            data.put("staffId", staff.getId());
            data.put("nickname", staff.getName());
            data.put("phone", staff.getPhone());
            data.put("role", role);
            data.put("staffRole", staff.getRole());
            data.put("stationId", stationId);
            data.put("userType", "staff");
            data.put("bindingStatus", deriveBindingStatus(staff));
            data.put("fromBindStaff", fromBindStaff);
            return Result.success(data);
        } catch (Exception e) {
            log.error("[wx-login-staff] buildStaffWxLoginResult 异常: staffId={}, error={}", staff.getId(), e.getMessage(), e);
            return Result.error("登录构建失败: " + e.getMessage());
        }
    }

    // ==================== 更新资料 ====================

    @PostMapping("/update-profile")
    public Result<Void> updateProfile(@RequestBody @Valid AuthRequestDTO.UpdateProfile params) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.error("用户ID不能为空");
        }

        String nickname = params.getNickname();
        String phone = params.getPhone();

        if ("staff".equals(AuthContext.getUserType())) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null) {
                return Result.error("用户不存在");
            }
            if (nickname != null && !nickname.isEmpty()) {
                staff.setName(nickname);
            }
            if (phone != null && !phone.isEmpty()) {
                staff.setPhone(phone);
            }
            staff.setUpdateTime(LocalDateTime.now());
            staffMapper.update(staff);
            return Result.success();
        }

        Customer customer = customerMapper.getById(userId);
        if (customer == null) {
            return Result.error("用户不存在");
        }

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

    // [AQ-040] 登录/绑定失败限流（单体应用内存计数即可）：key -> [失败次数, 窗口起始毫秒]
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> LOGIN_ATTEMPTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_LOGIN_ATTEMPTS = 10;
    private static final long LOGIN_ATTEMPT_WINDOW_MS = 15 * 60 * 1000L;

    private boolean tooManyAttempts(String key) {
        long[] rec = LOGIN_ATTEMPTS.get(key);
        if (rec == null) return false;
        long now = System.currentTimeMillis();
        if (now - rec[1] > LOGIN_ATTEMPT_WINDOW_MS) {
            LOGIN_ATTEMPTS.remove(key);
            return false;
        }
        return rec[0] >= MAX_LOGIN_ATTEMPTS;
    }

    private void recordFailure(String key) {
        long now = System.currentTimeMillis();
        LOGIN_ATTEMPTS.compute(key, (k, v) -> {
            if (v == null || now - v[1] > LOGIN_ATTEMPT_WINDOW_MS) return new long[]{1, now};
            v[0]++;
            return v;
        });
    }

    private void clearFailures(String key) {
        LOGIN_ATTEMPTS.remove(key);
    }

    /** [AQ-047] openid 脱敏，避免日志明文泄露微信用户标识 */
    private static String maskOpenid(String openid) {
        if (openid == null) return "null";
        if (openid.length() <= 4) return "***";
        return openid.substring(0, 4) + "****";
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody @Valid AuthRequestDTO.Login params) {
        String username = params.getUsername();
        String password = params.getPassword();

        // [AQ-040] 同一用户名 15 分钟内失败次数超限即锁定，防暴力破解
        String lockKey = "login:" + username;
        if (tooManyAttempts(lockKey)) {
            return Result.error("尝试次数过多，请 15 分钟后再试");
        }

        Staff staff = staffMapper.findByName(username);
        if (staff != null) {
            if (staff.getPasswordHash() != null && PasswordUtil.matches(password, staff.getPasswordHash())) {
                clearFailures(lockKey);
                return buildStaffLoginResult(staff);
            }
        }

        recordFailure(lockKey);
        return Result.error("用户名或密码错误");
    }

    // ==================== Token 刷新 ====================

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody @Valid AuthRequestDTO.Refresh params) {
        String refreshToken = params.getRefreshToken();

        if (!jwtUtil.validateToken(refreshToken)) {
            return Result.error("refreshToken已过期，请重新登录");
        }

        io.jsonwebtoken.Claims claims = jwtUtil.parseToken(refreshToken);
        String tokenType = claims.get("tokenType", String.class);
        if (!"refresh".equals(tokenType)) {
            return Result.error("无效的refreshToken");
        }

        Long userId = claims.get("userId", Number.class).longValue();
        String userType = claims.get("userType", String.class);

        UserToken storedToken = userTokenMapper.findByRefreshToken(refreshToken);
        if (storedToken == null) {
            return Result.error("令牌已失效，请重新登录");
        }

        String role;
        Long stationId = null;

        if ("staff".equals(userType)) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null) return Result.error("用户不存在");
            // #6: 检查员工状态，禁用员工不允许刷新token
            if (staff.getStatus() == null || !Integer.valueOf(1).equals(staff.getStatus())) {
                return Result.error("该账号已停用");
            }
            role = mapStaffRole(staff);
            stationId = (staff.getStationId() != null && staff.getStationId() == 0) ? null : staff.getStationId();
        } else {
            Customer customer = customerMapper.getById(userId);
            if (customer == null) return Result.error("用户不存在");
            role = "customer";
            stationId = null;
        }

        String newAccessToken = jwtUtil.generateAccessToken(userId, userType, role, stationId);

        // #2: Refresh Token轮换 — 生成新的refresh token，废弃旧的
        String newRefreshToken = jwtUtil.generateRefreshToken(userId, userType);
        // 删除旧token记录
        userTokenMapper.deleteByRefreshToken(refreshToken);
        // 保存新token
        saveRefreshToken(userId, userType, newRefreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", newAccessToken);
        data.put("refreshToken", newRefreshToken);
        return Result.success(data);
    }

    // ==================== 登出 ====================

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
                Long userId = claims.get("userId", Number.class).longValue();
                String userType = claims.get("userType", String.class);
                userTokenMapper.deleteByUser(userId, userType);
            }
        }
        return Result.success();
    }

    // ==================== 验证登录状态 ====================

    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        Long userId = AuthContext.getUserId();
        String userType = AuthContext.getUserType();

        if (userId == null) {
            return Result.error("未登录");
        }

        Map<String, Object> data = new HashMap<>();

        if ("staff".equals(userType)) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null) return Result.error("用户不存在");
            Long stationId = (staff.getStationId() != null && staff.getStationId() == 0) ? null : staff.getStationId();
            data.put("staffId", staff.getId());
            data.put("nickname", staff.getName());
            data.put("phone", staff.getPhone());
            data.put("role", mapStaffRole(staff));
            data.put("stationId", stationId);
            data.put("bindingStatus", deriveBindingStatus(staff));
            data.put("userType", "staff");
        } else {
            Customer customer = customerMapper.getById(userId);
            if (customer == null) return Result.error("用户不存在");
            data.put("customerId", customer.getId());
            data.put("nickname", customer.getName());
            data.put("phone", customer.getPhone());
            data.put("role", "customer");
            data.put("userType", "customer");
        }

        return Result.success(data);
    }

    // ==================== 修改密码 ====================

    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody @Valid AuthRequestDTO.ChangePassword params) {
        Long userId = AuthContext.getUserId();
        String userType = AuthContext.getUserType();

        if (!"staff".equals(userType)) {
            return Result.error("仅支持员工修改密码");
        }

        Staff staff = staffMapper.getById(userId);
        if (staff == null) return Result.error("用户不存在");

        String oldPassword = params.getOldPassword();
        String newPassword = params.getNewPassword();

        if (staff.getPasswordHash() != null) {
            if (!PasswordUtil.matches(oldPassword, staff.getPasswordHash())) {
                return Result.error("旧密码错误");
            }
        } else {
            return Result.error("账号未设置密码，请联系管理员重置");
        }

        if (newPassword.length() < 6) {
            return Result.error("新密码长度不能少于6位");
        }

        staff.setPasswordHash(PasswordUtil.encode(newPassword));
        staffMapper.update(staff);

        userTokenMapper.deleteByUser(staff.getId(), "staff");

        return Result.success();
    }

    // ==================== 私有辅助方法 ====================

    private Result<Map<String, Object>> buildStaffLoginResult(Staff staff) {
        String role = mapStaffRole(staff);
        Long stationId = (staff.getStationId() != null && staff.getStationId() == 0) ? null : staff.getStationId();
        String accessToken = jwtUtil.generateAccessToken(
                staff.getId(), "staff", role,
                stationId);
        String refreshToken = jwtUtil.generateRefreshToken(staff.getId(), "staff");

        saveRefreshToken(staff.getId(), "staff", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("username", staff.getName());
        data.put("nickname", staff.getName());
        data.put("role", role);
        data.put("stationId", stationId);
        data.put("staffId", staff.getId());
        data.put("staffRole", staff.getRole());
        data.put("bindingStatus", deriveBindingStatus(staff));
        return Result.success(data);
    }

    private Result<Map<String, Object>> buildCustomerLoginResult(Customer customer) {
        String accessToken = jwtUtil.generateAccessToken(
                customer.getId(), "customer", "customer",
                null);
        String refreshToken = jwtUtil.generateRefreshToken(customer.getId(), "customer");

        saveRefreshToken(customer.getId(), "customer", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("role", "customer");
        return Result.success(data);
    }

    private void saveRefreshToken(Long userId, String userType, String refreshToken) {
        // #3: 清理该用户旧的refresh token，防止累积
        userTokenMapper.deleteByUser(userId, userType);
        UserToken userToken = new UserToken();
        userToken.setUserId(userId);
        userToken.setUserType(userType);
        userToken.setRefreshToken(refreshToken);
        userToken.setExpireTime(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshTokenExpiry() / 1000));
        userToken.setCreateTime(LocalDateTime.now());
        userTokenMapper.insert(userToken);
    }

    private String mapStaffRole(Staff staff) {
        if ("STATION_MANAGER".equals(staff.getRole())) return "manager";
        return "delivery";
    }

    /**
     * V1: bindingStatus 不再是 DB 列，而是按 "station_id + 申请表待审批项" 派生.
     * <ul>
     *   <li>STATION_MANAGER:  station_id != null → BOUND, 否则 UNBOUND (意味着还没创建水站)</li>
     *   <li>DELIVERY:
     *     <ul>
     *       <li>station_id != null → 若还有 pending 解绑申请 → PENDING_UNBIND; 否则 BOUND</li>
     *       <li>station_id == null → 若有 pending 绑定申请 → PENDING; 否则 UNBOUND</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    private String deriveBindingStatus(Staff staff) {
        if (staff == null) return "UNBOUND";

        if ("STATION_MANAGER".equals(staff.getRole())) {
            return staff.getStationId() != null ? "BOUND" : "UNBOUND";
        }

        // DELIVERY (含其他)
        List<StaffStationApplication> list = null;
        if (staff.getId() != null) {
            list = appMapper.listByStaff(staff.getId());
        }

        if (staff.getStationId() != null) {
            if (list != null) {
                for (StaffStationApplication a : list) {
                    if (a.getType() == StaffStationApplication.TYPE_UNBIND
                            && a.getStatus() == StaffStationApplication.STATUS_PENDING) {
                        return "PENDING_UNBIND";
                    }
                }
            }
            return "BOUND";
        } else {
            if (list != null) {
                for (StaffStationApplication a : list) {
                    if (a.getType() == StaffStationApplication.TYPE_BIND
                            && a.getStatus() == StaffStationApplication.STATUS_PENDING) {
                        return "PENDING";
                    }
                }
            }
            return "UNBOUND";
        }
    }
}
