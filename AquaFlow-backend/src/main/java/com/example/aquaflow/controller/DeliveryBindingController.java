package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.BindingActionDTO;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.service.StaffBindCodeService;
import com.example.aquaflow.service.StaffStationApplicationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 配送员绑定/解绑水站申请 API —— **薄壳**（2026-09-29 下沉）。
 *
 * <p>编排、锁序（员工聚合 → 申请行）、CAS 判定与 8 处 {@code @Transactional} 全部在
 * {@link StaffStationApplicationService}；本类只剩认证注解 + DTO 校验 + 调服务 + 包 {@code Result}。
 * 业务失败由服务抛 {@code BusinessException}，全局处理器转成与原 {@code Result.error} 一致的
 * {@code code=1} 响应。{@code LayeringArchitectureTest} 对本类的 Mapper 注入 / 写调用 /
 * 事务是**基线外零容忍**，别把它们搬回来。</p>
 */
@RestController
public class DeliveryBindingController {

    @Autowired
    private StaffStationApplicationService bindingService;

    /** [2026-09-30 F-03③] 员工绑定码的签发（站长端）。 */
    @Autowired
    private StaffBindCodeService bindCodeService;

    // ==================== 配送员: 申请绑定水站 (C) ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/api/delivery/bind/apply")
    public Result<Void> applyBind(@RequestBody @Valid BindingActionDTO.ApplyBind params) {
        bindingService.applyBind(params);
        return Result.success();
    }

    // ==================== 配送员: 取消自己的绑定申请 ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/api/delivery/bind/cancel")
    public Result<Void> cancelApply(@RequestBody(required = false) Map<String, Object> params) {
        bindingService.cancelApply(params);
        return Result.success();
    }

    // ==================== 配送员: 申请解绑 (F) ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/api/delivery/bind/unbind-request")
    public Result<Void> unbindRequest(@RequestBody(required = false) Map<String, Object> params) {
        bindingService.unbindRequest(params);
        return Result.success();
    }

    // ==================== 配送员: 查询自己的绑定状态 (派生) ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/api/delivery/bind/status")
    public Result<Map<String, Object>> getBindStatus() {
        return Result.success(bindingService.getBindStatus());
    }

    // ==================== 站长: 同意绑定申请 (D) ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/approve")
    public Result<Void> approveBind(@RequestBody @Valid BindingActionDTO.Handle params) {
        bindingService.approveBind(params);
        return Result.success();
    }

    // ==================== 站长: 拒绝绑定申请 (E) ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/reject")
    public Result<Void> rejectBind(@RequestBody @Valid BindingActionDTO.Handle params) {
        bindingService.rejectBind(params);
        return Result.success();
    }

    // ==================== 站长: 同意解绑申请 (G) ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/unbind-confirm")
    public Result<Void> unbindConfirm(@RequestBody @Valid BindingActionDTO.Handle params) {
        bindingService.unbindConfirm(params);
        return Result.success();
    }

    // ==================== 站长: 拒绝解绑申请 ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/unbind-reject")
    public Result<Void> unbindReject(@RequestBody @Valid BindingActionDTO.Handle params) {
        bindingService.unbindReject(params);
        return Result.success();
    }

    // ==================== 站长: 单方面解除配送员 (H) — 护栏见服务方法 javadoc ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/release")
    public Result<Void> release(@RequestBody @Valid BindingActionDTO.Release params) {
        bindingService.release(params);
        return Result.success();
    }

    // ==================== 站长: 查询水站收到的申请列表 ====================

    @RequireRole("STATION_MANAGER")
    @GetMapping("/api/manager/bind/applications")
    public Result<List<Map<String, Object>>> getBindApplications(@RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) Integer type) {
        return Result.success(bindingService.getBindApplications(status, type));
    }

    // ==================== 站长: 查询水站下所有在职员工 ====================

    @RequireRole("STATION_MANAGER")
    @GetMapping("/api/manager/staff")
    public Result<List<Staff>> getManagerStaff() {
        return Result.success(bindingService.getManagerStaff());
    }

    // ==================== 站长: 为本站员工签发一次性绑定码 (F-03③) ====================

    /**
     * 生成员工绑定微信要用的**一次性绑定码**（决策正本 {@code docs/design/16} §9.3）。
     *
     * <p>为什么不能沿用「姓名 + 手机号」当凭据：那是公开信息，而 {@code POST /api/auth/bind-staff}
     * 是**免认证**端点、签发的却是员工会话 —— 谁拿到这两项，就能在该员工还没绑微信时把账号
     * 绑到自己微信上。绑定码只有本站站长能签发、10 分钟有效、用一次即废。</p>
     *
     * <p>⚠️ 站别判定在 {@code StaffBindCodeService.generate} 里走 {@code AuthContext}，
     * **不接受**请求参数里的站别；本方法只做认证注解 + 转发（本类对 Mapper 注入是基线外零容忍，
     * 别把 mapper 搬进来）。</p>
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/staff/{staffId}/bind-code")
    public Result<Map<String, Object>> generateBindCode(@PathVariable Long staffId) {
        return Result.success(bindCodeService.generate(staffId));
    }

    // ==================== 配送员: 列出自己的申请历史 ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/api/delivery/bind/applications")
    public Result<List<Map<String, Object>>> getMyApplications() {
        return Result.success(bindingService.getMyApplications());
    }
}
