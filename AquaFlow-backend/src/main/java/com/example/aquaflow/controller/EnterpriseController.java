package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.CustomerEnterpriseApply;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.service.EnterpriseIdentityService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 企业身份申请（v50）：客户提交 / 站长审核；站级提示阈值（v51）：站长在读写的同一处。**不做独立入口** ——
 * 客户侧只在订水报价出现"大额可申请"提示后由那个提示带进来；站长侧在客户列表里审核与设阈值。
 *
 * <p>⚠️ 全部端点都受 {@code app.enterprise.enabled} 开关控制（默认关闭）：关掉时一律返回
 * 业务错误「企业身份功能当前未开启」，**不是**把入口藏起来而已（藏起来但接口还能调 =
 * 甲方以为关了实际没关）。两个例外都是有意为之，别"顺手统一"：{@code GET /manager/applications}
 * 与 {@code GET /manager/config} 返回**空列表 / enabled=false**，让站长端那一块安静消失。</p>
 *
 * <p>⚠️ {@code app.enterprise.enabled} 这个**平台级**开关没有前端入口（产品 2026-09-19：
 * 「平台级的暂时不做前端可视化了」）—— 它只有环境变量一条路径。站长端能改的是**站级阈值**。</p>
 */
@RestController
@RequestMapping("/api/enterprise")
public class EnterpriseController {

    @Autowired
    private EnterpriseIdentityService enterpriseIdentityService;

    /** 提交申请（顾客自助：客户 id 与站点都取自登录态与请求体里的站，客户不能替别人申请）。 */
    @PostMapping("/applications")
    public Result<CustomerEnterpriseApply> submit(@RequestBody Map<String, Object> body) {
        Long customerId = AuthContext.requireCustomerId();
        Long stationId = body.get("stationId") == null ? null : Long.valueOf(body.get("stationId").toString());
        if (stationId == null) {
            return Result.error("请选择水站");
        }
        return Result.success(enterpriseIdentityService.submit(customerId, stationId,
                str(body.get("companyName")), str(body.get("contactPerson")),
                str(body.get("contactPhone")), str(body.get("taxNo"))));
    }

    /** 我的申请（顾客自助）：按登录客户 + 指定水站查最近 10 条。 */
    @GetMapping("/applications/my")
    public Result<List<CustomerEnterpriseApply>> myApplications(@RequestParam Long stationId) {
        return Result.success(enterpriseIdentityService.listMine(AuthContext.requireCustomerId(), stationId));
    }

    /**
     * 本站待审的企业身份申请（站长端）。
     *
     * <p>开关关着时返回**空列表而不是报错**：站长端那一行提示应当安静地消失，
     * 不该在客户列表页弹一个"功能未开启"的红字。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/manager/applications")
    public Result<List<CustomerEnterpriseApply>> pendingApplications() {
        if (!enterpriseIdentityService.isEnabled()) {
            return Result.success(java.util.Collections.emptyList());
        }
        return Result.success(enterpriseIdentityService.listPending(AuthContext.requireStationId()));
    }

    /** 站长审核：{@code approve=true} 通过（转企业身份 + 写企业资料），false 驳回。 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/manager/applications/{id}")
    public Result<Void> review(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("approve")));
        enterpriseIdentityService.review(id, AuthContext.requireStationId(), approve,
                str(body.get("note")), AuthContext.getUserId());
        return Result.success();
    }

    /**
     * 本站的「企业身份提示阈值」（站长端设置界面回填用）。
     *
     * <p>站别取自登录态（{@code AuthContext}），**不接受请求参数传站** —— 否则就是跨站改别人配置。
     * 开关关着时同样返回 200 且 {@code enabled=false}：前端据此把整块入口隐藏，
     * <b>不要</b>在这里抛错（那是"藏入口"与"报错"的混合体，客户列表页会冒红字）。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/manager/config")
    public Result<Map<String, Object>> stationConfig() {
        return Result.success(enterpriseIdentityService.stationConfig(AuthContext.requireStationId()));
    }

    /**
     * 站长改本站阈值：{@code {"barrelThreshold":30,"waterAmountThreshold":500}}。
     *
     * <p>两项都可为 null：<b>留空 = 不用这条口径</b>；<b>两项都留空 = 本站不提示</b>
     * （与"从没配过 → 用平台默认"是两回事，见 {@code StationEnterpriseConfig} 的类注释）。
     * 开关关着时本端点**必须真拒绝**（与其它三个端点同口径）。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/manager/config")
    public Result<Void> updateStationConfig(@RequestBody Map<String, Object> body) {
        Integer barrels = intOrNull(body.get("barrelThreshold"));
        java.math.BigDecimal amount = decimalOrNull(body.get("waterAmountThreshold"));
        enterpriseIdentityService.updateStationConfig(AuthContext.requireStationId(), barrels, amount,
                AuthContext.getUserId());
        return Result.success();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** 空串 / null 都当"留空"（前端清空输入框发过来的就是空串）。 */
    private static Integer intOrNull(Object v) {
        if (v == null || v.toString().trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("桶数阈值要填整数");
        }
    }

    private static java.math.BigDecimal decimalOrNull(Object v) {
        if (v == null || v.toString().trim().isEmpty()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("水费金额阈值要填数字");
        }
    }
}
