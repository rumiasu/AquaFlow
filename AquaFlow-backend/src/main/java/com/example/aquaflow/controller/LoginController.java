package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.AuthRequestDTO;
import com.example.aquaflow.service.AuthTokenService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证与会话 —— **薄壳**（2026-09-29 下沉）。
 *
 * <p><b>本类不加 {@code @RequireRole} 是正确的</b>：登录相关端点在调用时还没有身份，
 * 加了反而会把自己拦死。覆盖：微信登录（顾客 {@code /wx-login}、员工 {@code /wx-login-staff}）、
 * 角色选择、建站、员工绑定、改资料、账号密码登录、token 刷新 / 登出、{@code /me}、改密码。</p>
 *
 * <p><b>⚠️ {@code /api/auth/refresh} 与 {@code /api/auth/me} 的路径曾被小程序硬编码引用</b>
 * （两端的 {@code utils/request.js} 与 {@code app.js} 的自动续期链路）。改这两个路径会同时打断
 * 双端的 token 续期 —— 现已统一改用 {@code API.REFRESH} / {@code API.ME} 常量引用，
 * 改路径前请先确认两端 {@code config/api.js} 常量已同步。</p>
 *
 * <p>编排、事务（{@code selectRole} / {@code createStationAndBind}）、限流计数与
 * {@code Result} 构建全部在 {@link AuthTokenService}；业务失败由服务抛
 * {@code BusinessException} → 全局处理器转 {@code code=1}（与原 {@code Result.error} 一致）。
 * {@code LayeringArchitectureTest} 对本类的 Mapper 注入 / 写调用 / 事务是**基线外零容忍**。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class LoginController {

    @Autowired
    private AuthTokenService authTokenService;

    // ==================== 微信小程序登录 ====================

    /**
     * 微信登录（用户小程序）：仅查 customer 表，与 staff 表完全独立
     */
    @PostMapping("/wx-login")
    public Result<Map<String, Object>> wxLogin(@RequestBody @Valid AuthRequestDTO.WxLogin params) {
        return Result.success(authTokenService.wxLogin(params));
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
        return Result.success(authTokenService.wxLoginStaff(params));
    }

    /**
     * 首次进入配送端选择角色 (站长/配送员)；**未生效时也可用于改选**（详见服务方法 javadoc）。
     */
    @PostMapping("/select-role")
    public Result<Map<String, Object>> selectRole(@RequestBody @Valid AuthRequestDTO.SelectRole params) {
        return Result.success(authTokenService.selectRole(params));
    }

    /**
     * 站长 (STATION_MANAGER) 创建水站并将自己绑定为该站站长（详见服务方法 javadoc）。
     */
    @PostMapping("/create-station")
    public Result<Map<String, Object>> createStationAndBind(@RequestBody @Valid AuthRequestDTO.CreateStation params) {
        return Result.success(authTokenService.createStationAndBind(params));
    }

    /**
     * 员工绑定微信：输入姓名+手机号，匹配 staff 记录并绑定当前微信 openid
     */
    @PostMapping("/bind-staff")
    public Result<Map<String, Object>> bindStaff(@RequestBody @Valid AuthRequestDTO.BindStaff params) {
        return Result.success(authTokenService.bindStaff(params));
    }

    // ==================== 更新资料 ====================

    @PostMapping("/update-profile")
    public Result<Void> updateProfile(@RequestBody @Valid AuthRequestDTO.UpdateProfile params) {
        authTokenService.updateProfile(params);
        return Result.success();
    }

    // ==================== 管理后台登录 ====================

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody @Valid AuthRequestDTO.Login params) {
        return Result.success(authTokenService.login(params));
    }

    // ==================== Token 刷新 ====================

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody @Valid AuthRequestDTO.Refresh params) {
        return Result.success(authTokenService.refresh(params));
    }

    // ==================== 登出 ====================

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        authTokenService.logout(authHeader);
        return Result.success();
    }

    // ==================== 验证登录状态 ====================

    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        return Result.success(authTokenService.me());
    }

    // ==================== 修改密码 ====================

    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody @Valid AuthRequestDTO.ChangePassword params) {
        authTokenService.changePassword(params);
        return Result.success();
    }
}
