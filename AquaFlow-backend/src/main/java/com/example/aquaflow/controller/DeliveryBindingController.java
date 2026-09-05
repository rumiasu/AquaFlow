package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.StaffStationApplication;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StaffStationApplicationMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送员绑定/解绑水站申请 API
 * <p>V1 模型核心:
 * <ul>
 *   <li>当前归属关系 → {@code staff.station_id} (NULL = 未绑定水站)</li>
 *   <li>申请/审批历史 → {@code staff_station_application}</li>
 *   <li>站长主动解除 → 不写申请, 直接 staff.station_id = NULL + audit_log(STAFF_BINDING / FORCE_UNBIND)</li>
 * </ul>
 */
@RestController
public class DeliveryBindingController {

    @Autowired
    private StaffMapper staffMapper;

    @Autowired
    private StaffStationApplicationMapper appMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private AuditLogService auditLogService;

    // ==================== 配送员: 申请绑定水站 (C) ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/api/delivery/bind/apply")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> applyBind(@RequestBody Map<String, Object> params) {
        Long staffId = AuthContext.getUserId();
        Object stationIdObj = params.get("stationId");
        if (stationIdObj == null) {
            return Result.error("stationId 不能为空");
        }
        Long stationId = ((Number) stationIdObj).longValue();
        String applyNote = params.get("applyNote") != null ? params.get("applyNote").toString() : "";

        Staff staff = staffMapper.getById(staffId);
        if (staff == null) {
            return Result.error("员工不存在");
        }
        if (!"DELIVERY".equals(staff.getRole())) {
            return Result.error("仅配送员可申请绑定水站");
        }

        // V1 规则: 已经绑定水站不允许新申请绑定; 若想换站, 必须先完成解绑
        if (staff.getStationId() != null) {
            return Result.error("已绑定水站, 如需更换请先申请解绑");
        }

        // 目标水站必须存在
        if (stationMapper.getById(stationId) == null) {
            return Result.error("目标水站不存在");
        }

        // 防重复: 同一个配送员对同一水站不允许同时存在多个"待审批绑定申请"
        if (appMapper.countPendingDup(staffId, stationId, StaffStationApplication.TYPE_BIND) > 0) {
            return Result.error("已存在对该水站的待审批申请");
        }

        StaffStationApplication app = new StaffStationApplication();
        app.setStaffId(staffId);
        app.setStationId(stationId);
        app.setType(StaffStationApplication.TYPE_BIND);
        app.setStatus(StaffStationApplication.STATUS_PENDING);
        app.setApplyNote(applyNote);
        app.setCreateTime(LocalDateTime.now());
        appMapper.insert(app);

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", staffId);
        detail.put("staffName", staff.getName());
        detail.put("stationId", stationId);
        auditLogService.log("STAFF_BINDING", "APPLY_BIND", "staff:" + staffId, detail.toString(), null);

        return Result.success();
    }

    // ==================== 配送员: 取消自己的绑定申请 ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/api/delivery/bind/cancel")
    public Result<Void> cancelApply(@RequestBody(required = false) Map<String, Object> params) {
        Long staffId = AuthContext.getUserId();

        Long applicationId = null;
        if (params != null && params.get("applicationId") != null) {
            applicationId = ((Number) params.get("applicationId")).longValue();
        }

        StaffStationApplication target = null;
        if (applicationId != null) {
            target = appMapper.getById(applicationId);
        } else {
            // 没传 applicationId, 取该用户最新一条 绑定待审批 申请
            List<StaffStationApplication> list = appMapper.listByStaff(staffId);
            for (StaffStationApplication a : list) {
                if (a.getType() == StaffStationApplication.TYPE_BIND
                        && a.getStatus() == StaffStationApplication.STATUS_PENDING) {
                    target = a;
                    break;
                }
            }
        }

        if (target == null) {
            return Result.error("找不到待取消的申请");
        }
        if (!target.getStaffId().equals(staffId)) {
            return Result.error("只能取消自己发起的申请");
        }
        if (target.getStatus() != StaffStationApplication.STATUS_PENDING) {
            return Result.error("当前申请状态不允许取消");
        }

        appMapper.cancel(target.getId());

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", target.getId());
        detail.put("stationId", target.getStationId());
        auditLogService.log("STAFF_BINDING", "CANCEL_APPLY", "staff:" + staffId, detail.toString(), null);

        return Result.success();
    }

    // ==================== 配送员: 申请解绑 (F) ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/api/delivery/bind/unbind-request")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindRequest(@RequestBody(required = false) Map<String, Object> params) {
        Long staffId = AuthContext.getUserId();
        String applyNote = params != null && params.get("applyNote") != null ? params.get("applyNote").toString() : "";

        Staff staff = staffMapper.getById(staffId);
        if (staff == null) {
            return Result.error("员工不存在");
        }
        if (staff.getStationId() == null) {
            return Result.error("当前未绑定任何水站");
        }

        Long stationId = staff.getStationId();

        // 避免对同一站同时存在多条待审批解绑申请
        if (appMapper.countPendingDup(staffId, stationId, StaffStationApplication.TYPE_UNBIND) > 0) {
            return Result.error("已存在待审批的解绑申请, 请等待处理");
        }

        StaffStationApplication app = new StaffStationApplication();
        app.setStaffId(staffId);
        app.setStationId(stationId);
        app.setType(StaffStationApplication.TYPE_UNBIND);
        app.setStatus(StaffStationApplication.STATUS_PENDING);
        app.setApplyNote(applyNote);
        app.setCreateTime(LocalDateTime.now());
        appMapper.insert(app);

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", staffId);
        detail.put("staffName", staff.getName());
        detail.put("stationId", stationId);
        auditLogService.log("STAFF_BINDING", "UNBIND_REQUEST", "staff:" + staffId, detail.toString(), null);

        return Result.success();
    }

    // ==================== 配送员: 查询自己的绑定状态 (派生) ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/api/delivery/bind/status")
    public Result<Map<String, Object>> getBindStatus() {
        Long staffId = AuthContext.getUserId();
        Staff staff = staffMapper.getById(staffId);
        if (staff == null) {
            return Result.error("员工不存在");
        }

        String bindingStatus;
        Map<String, Object> pendingApply = null;
        if (staff.getStationId() != null) {
            bindingStatus = "BOUND";
            // 若还存在一个待审批的解绑申请, 返回 PENDING_UNBIND
            List<StaffStationApplication> list = appMapper.listByStaff(staffId);
            for (StaffStationApplication a : list) {
                if (a.getType() == StaffStationApplication.TYPE_UNBIND
                        && a.getStatus() == StaffStationApplication.STATUS_PENDING) {
                    bindingStatus = "PENDING_UNBIND";
                    pendingApply = applicationToMap(a);
                    break;
                }
            }
        } else {
            bindingStatus = "UNBOUND";
            // 若存在一个待审批的绑定申请, 返回 PENDING
            List<StaffStationApplication> list = appMapper.listByStaff(staffId);
            for (StaffStationApplication a : list) {
                if (a.getType() == StaffStationApplication.TYPE_BIND
                        && a.getStatus() == StaffStationApplication.STATUS_PENDING) {
                    bindingStatus = "PENDING";
                    pendingApply = applicationToMap(a);
                    break;
                }
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("bindingStatus", bindingStatus);
        res.put("stationId", staff.getStationId());
        res.put("pendingApplication", pendingApply);
        res.put("role", staff.getRole());
        return Result.success(res);
    }

    // ==================== 站长: 同意绑定申请 (D) ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/approve")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> approveBind(@RequestBody Map<String, Object> params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();

        // applicationId 或 staffId 二选一 (优先取 applicationId, 兼容性更好)
        StaffStationApplication app = null;
        if (params.get("applicationId") != null) {
            Long appId = ((Number) params.get("applicationId")).longValue();
            app = appMapper.getById(appId);
        }
        if (app == null && params.get("staffId") != null) {
            Long staffId = ((Number) params.get("staffId")).longValue();
            List<StaffStationApplication> list = appMapper.listByStationAndStatus(
                    myStationId, StaffStationApplication.STATUS_PENDING);
            for (StaffStationApplication a : list) {
                if (a.getStaffId().equals(staffId) && a.getType() == StaffStationApplication.TYPE_BIND) {
                    app = a;
                    break;
                }
            }
        }

        if (app == null) {
            return Result.error("找不到待审批的绑定申请");
        }
        if (app.getStatus() != StaffStationApplication.STATUS_PENDING) {
            return Result.error("申请已处理");
        }
        if (app.getType() != StaffStationApplication.TYPE_BIND) {
            return Result.error("这不是绑定申请");
        }
        if (!app.getStationId().equals(myStationId)) {
            return Result.error("该申请不属于本站");
        }

        Staff staff = staffMapper.getById(app.getStaffId());
        if (staff == null) {
            return Result.error("员工不存在");
        }
        if (!"DELIVERY".equals(staff.getRole())) {
            return Result.error("仅配送员可审批绑定");
        }
        if (staff.getStationId() != null) {
            return Result.error("该配送员当前已绑定其他水站");
        }

        // 事务: 1) 更新申请为已同意  2) 写审批人/时间  3) 改 staff.station_id
        appMapper.handle(app.getId(), StaffStationApplication.STATUS_APPROVED, myStaffId,
                params.get("handleNote") != null ? params.get("handleNote").toString() : "");
        staffMapper.updateStationId(staff.getId(), myStationId);

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", staff.getId());
        detail.put("staffName", staff.getName());
        detail.put("stationId", myStationId);
        detail.put("handleStaffId", myStaffId);
        auditLogService.log("STAFF_BINDING", "APPROVE_BIND", "staff:" + staff.getId(), detail.toString(), String.valueOf(myStaffId));

        return Result.success();
    }

    // ==================== 站长: 拒绝绑定申请 (E) ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/reject")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> rejectBind(@RequestBody Map<String, Object> params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";

        StaffStationApplication app = null;
        if (params.get("applicationId") != null) {
            Long appId = ((Number) params.get("applicationId")).longValue();
            app = appMapper.getById(appId);
        }
        if (app == null && params.get("staffId") != null) {
            Long staffId = ((Number) params.get("staffId")).longValue();
            List<StaffStationApplication> list = appMapper.listByStationAndStatus(
                    myStationId, StaffStationApplication.STATUS_PENDING);
            for (StaffStationApplication a : list) {
                if (a.getStaffId().equals(staffId) && a.getType() == StaffStationApplication.TYPE_BIND) {
                    app = a;
                    break;
                }
            }
        }

        if (app == null) {
            return Result.error("找不到待审批的绑定申请");
        }
        if (app.getStatus() != StaffStationApplication.STATUS_PENDING) {
            return Result.error("申请已处理");
        }
        if (app.getType() != StaffStationApplication.TYPE_BIND) {
            return Result.error("这不是绑定申请");
        }
        if (!app.getStationId().equals(myStationId)) {
            return Result.error("该申请不属于本站");
        }

        appMapper.handle(app.getId(), StaffStationApplication.STATUS_REJECTED, myStaffId, reason);
        // 注意: 拒绝绑定不修改 staff.station_id (仍为 NULL)

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", app.getStaffId());
        detail.put("stationId", myStationId);
        detail.put("reason", reason);
        auditLogService.log("STAFF_BINDING", "REJECT_BIND", "staff:" + app.getStaffId(), detail.toString(), String.valueOf(myStaffId));

        return Result.success();
    }

    // ==================== 站长: 同意解绑申请 (G) ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/unbind-confirm")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindConfirm(@RequestBody Map<String, Object> params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();
        String handleNote = params.get("handleNote") != null ? params.get("handleNote").toString() : "";

        StaffStationApplication app = null;
        if (params.get("applicationId") != null) {
            Long appId = ((Number) params.get("applicationId")).longValue();
            app = appMapper.getById(appId);
        }
        if (app == null && params.get("staffId") != null) {
            Long staffId = ((Number) params.get("staffId")).longValue();
            List<StaffStationApplication> list = appMapper.listByStationAndStatus(
                    myStationId, StaffStationApplication.STATUS_PENDING);
            for (StaffStationApplication a : list) {
                if (a.getStaffId().equals(staffId) && a.getType() == StaffStationApplication.TYPE_UNBIND) {
                    app = a;
                    break;
                }
            }
        }

        if (app == null) {
            return Result.error("找不到待审批的解绑申请");
        }
        if (app.getStatus() != StaffStationApplication.STATUS_PENDING) {
            return Result.error("申请已处理");
        }
        if (app.getType() != StaffStationApplication.TYPE_UNBIND) {
            return Result.error("这不是解绑申请");
        }
        if (!app.getStationId().equals(myStationId)) {
            return Result.error("该申请不属于本站");
        }

        Staff staff = staffMapper.getById(app.getStaffId());
        if (staff == null) {
            return Result.error("员工不存在");
        }
        if (staff.getStationId() == null || !staff.getStationId().equals(app.getStationId())) {
            return Result.error("配送员当前归属与申请不一致");
        }

        appMapper.handle(app.getId(), StaffStationApplication.STATUS_APPROVED, myStaffId, handleNote);
        staffMapper.updateStationId(staff.getId(), null);

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", staff.getId());
        detail.put("staffName", staff.getName());
        detail.put("stationId", myStationId);
        auditLogService.log("STAFF_BINDING", "UNBIND_CONFIRM", "staff:" + staff.getId(), detail.toString(), String.valueOf(myStaffId));

        return Result.success();
    }

    // ==================== 站长: 拒绝解绑申请 ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/unbind-reject")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindReject(@RequestBody Map<String, Object> params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";

        StaffStationApplication app = null;
        if (params.get("applicationId") != null) {
            Long appId = ((Number) params.get("applicationId")).longValue();
            app = appMapper.getById(appId);
        }
        if (app == null && params.get("staffId") != null) {
            Long staffId = ((Number) params.get("staffId")).longValue();
            List<StaffStationApplication> list = appMapper.listByStationAndStatus(
                    myStationId, StaffStationApplication.STATUS_PENDING);
            for (StaffStationApplication a : list) {
                if (a.getStaffId().equals(staffId) && a.getType() == StaffStationApplication.TYPE_UNBIND) {
                    app = a;
                    break;
                }
            }
        }

        if (app == null) {
            return Result.error("找不到待审批的解绑申请");
        }
        if (app.getStatus() != StaffStationApplication.STATUS_PENDING) {
            return Result.error("申请已处理");
        }
        if (app.getType() != StaffStationApplication.TYPE_UNBIND) {
            return Result.error("这不是解绑申请");
        }
        if (!app.getStationId().equals(myStationId)) {
            return Result.error("该申请不属于本站");
        }

        appMapper.handle(app.getId(), StaffStationApplication.STATUS_REJECTED, myStaffId, reason);
        // 拒绝解绑不修改 staff.station_id (保持已绑定状态)

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", app.getStaffId());
        detail.put("stationId", myStationId);
        detail.put("reason", reason);
        auditLogService.log("STAFF_BINDING", "REJECT_UNBIND", "staff:" + app.getStaffId(), detail.toString(), String.valueOf(myStaffId));

        return Result.success();
    }

    // ==================== 站长: 单方面解除配送员 (H) — 直接改 station_id=NULL, 不走申请 ====================

    @RequireRole("STATION_MANAGER")
    @PostMapping("/api/manager/bind/release")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> release(@RequestBody Map<String, Object> params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();
        Object staffIdObj = params.get("staffId");
        if (staffIdObj == null) {
            return Result.error("staffId 不能为空");
        }
        Long staffId = ((Number) staffIdObj).longValue();
        String reason = params.get("reason") != null ? params.get("reason").toString() : "站长强制解除绑定";

        Staff staff = staffMapper.getById(staffId);
        if (staff == null) {
            return Result.error("员工不存在");
        }
        if (staff.getStationId() == null || !staff.getStationId().equals(myStationId)) {
            return Result.error("该员工不属于本站");
        }

        staffMapper.updateStationId(staffId, null);

        Map<String, Object> detail = new HashMap<>();
        detail.put("staffId", staffId);
        detail.put("staffName", staff.getName());
        detail.put("stationId", myStationId);
        detail.put("reason", reason);
        auditLogService.log("STAFF_BINDING", "FORCE_UNBIND", "staff:" + staffId, detail.toString(), String.valueOf(myStaffId));

        return Result.success();
    }

    // ==================== 站长: 查询水站收到的申请列表 ====================

    @RequireRole("STATION_MANAGER")
    @GetMapping("/api/manager/bind/applications")
    public Result<List<Map<String, Object>>> getBindApplications(@RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) Integer type) {
        Long myStationId = AuthContext.requireStationId();

        int statusVal;
        if (status == null) {
            statusVal = StaffStationApplication.STATUS_PENDING;
        } else if ("PENDING".equalsIgnoreCase(status)) {
            statusVal = StaffStationApplication.STATUS_PENDING;
        } else if ("APPROVED".equalsIgnoreCase(status)) {
            statusVal = StaffStationApplication.STATUS_APPROVED;
        } else if ("REJECTED".equalsIgnoreCase(status)) {
            statusVal = StaffStationApplication.STATUS_REJECTED;
        } else if ("CANCELLED".equalsIgnoreCase(status)) {
            statusVal = StaffStationApplication.STATUS_CANCELLED;
        } else {
            // 支持直接传数字
            try {
                statusVal = Integer.parseInt(status);
            } catch (NumberFormatException e) {
                statusVal = StaffStationApplication.STATUS_PENDING;
            }
        }

        List<StaffStationApplication> list = appMapper.listByStationAndStatus(myStationId, statusVal);
        List<Map<String, Object>> result = new ArrayList<>();
        for (StaffStationApplication a : list) {
            if (type != null && a.getType() != type.intValue()) continue;
            Map<String, Object> row = applicationToMap(a);
            Staff applicant = staffMapper.getById(a.getStaffId());
            if (applicant != null) {
                row.put("staffName", applicant.getName());
                row.put("staffPhone", applicant.getPhone());
            }
            if (a.getHandleStaffId() != null) {
                Staff handler = staffMapper.getById(a.getHandleStaffId());
                if (handler != null) {
                    row.put("handleStaffName", handler.getName());
                }
            }
            result.add(row);
        }
        return Result.success(result);
    }

    // ==================== 站长: 查询水站下所有在职员工 ====================

    @RequireRole("STATION_MANAGER")
    @GetMapping("/api/manager/staff")
    public Result<List<Staff>> getManagerStaff() {
        Long myStationId = AuthContext.requireStationId();
        // 复用 station_id 过滤在职员工 (站长 + 配送员)
        List<Staff> list = staffMapper.listByStationId(myStationId);
        return Result.success(list);
    }

    // ==================== 配送员: 列出自己的申请历史 ====================

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/api/delivery/bind/applications")
    public Result<List<Map<String, Object>>> getMyApplications() {
        Long staffId = AuthContext.getUserId();
        List<StaffStationApplication> list = appMapper.listByStaff(staffId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (StaffStationApplication a : list) {
            Map<String, Object> row = applicationToMap(a);
            if (a.getHandleStaffId() != null) {
                Staff handler = staffMapper.getById(a.getHandleStaffId());
                if (handler != null) {
                    row.put("handleStaffName", handler.getName());
                }
            }
            result.add(row);
        }
        return Result.success(result);
    }

    // ==================== 私有辅助 ====================

    private Map<String, Object> applicationToMap(StaffStationApplication a) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("staffId", a.getStaffId());
        m.put("stationId", a.getStationId());
        m.put("type", a.getType());
        m.put("typeName", Integer.valueOf(1).equals(a.getType()) ? "绑定申请" : "解绑申请");
        m.put("status", a.getStatus());
        String statusName;
        switch (a.getStatus() == null ? 0 : a.getStatus()) {
            case StaffStationApplication.STATUS_PENDING:
                statusName = "待审批";
                break;
            case StaffStationApplication.STATUS_APPROVED:
                statusName = "已同意";
                break;
            case StaffStationApplication.STATUS_REJECTED:
                statusName = "已拒绝";
                break;
            case StaffStationApplication.STATUS_CANCELLED:
                statusName = "已取消";
                break;
            default:
                statusName = "未知";
        }
        m.put("statusName", statusName);
        m.put("applyNote", a.getApplyNote());
        m.put("handleStaffId", a.getHandleStaffId());
        m.put("handleNote", a.getHandleNote());
        m.put("createTime", a.getCreateTime());
        m.put("handleTime", a.getHandleTime());
        return m;
    }
}
