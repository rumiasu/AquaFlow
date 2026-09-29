package com.example.aquaflow.service;

import com.example.aquaflow.dto.BindingActionDTO;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.StaffStationApplication;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StaffStationApplicationMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送员绑定/解绑水站申请的编排（2026-09-29 从 {@code DeliveryBindingController} 下沉）。
 *
 * <p><b>本类是这批流程的事务边界</b>：8 个写流程的 {@code @Transactional} 原来开在 Controller
 * （HTTP 层），下沉后 Controller 只剩认证 + DTO 校验 + 调服务 + 包 {@code Result}。
 * 业务失败一律抛 {@link BusinessException}（→ 全局处理器 {@code code=1}，与原
 * {@code Result.error(msg)} 的响应体逐字节一致），<b>别改回在服务里返回 Result</b>。</p>
 *
 * <p>V1 模型核心:</p>
 * <ul>
 *   <li>当前归属关系 → {@code staff.station_id} (NULL = 未绑定水站)</li>
 *   <li>申请/审批历史 → {@code staff_station_application}</li>
 *   <li>站长主动解除 → 不写申请, 直接 staff.station_id = NULL + audit_log(STAFF_BINDING / FORCE_UNBIND)</li>
 * </ul>
 */
@Service
public class StaffStationApplicationService {

    @Autowired
    private StaffMapper staffMapper;

    @Autowired
    private StaffStationApplicationMapper appMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private AuditLogService auditLogService;

    // ==================== 配送员: 申请绑定水站 (C) ====================

    @Transactional(rollbackFor = Exception.class)
    public void applyBind(BindingActionDTO.ApplyBind params) {
        Long staffId = AuthContext.getUserId();
        Long stationId = params.getStationId();
        String applyNote = params.getApplyNote() != null ? params.getApplyNote() : "";

        // [2026-09-25 返工 R6] 加锁读员工行（统一锁序：员工聚合 → 申请行）。
        // 不加锁时"读 station_id 为空 → 插一条待审批申请"与"另一站正好批准（把归属写上了）"
        // 会交错，插出一条对已绑定员工永远批不掉的申请（对方站长点同意只会得到"已被其他水站接收"）。
        Staff staff = staffMapper.getByIdForUpdate(staffId);
        if (staff == null) {
            throw new BusinessException("员工不存在");
        }
        if (!"DELIVERY".equals(staff.getRole())) {
            throw new BusinessException("仅配送员可申请绑定水站");
        }

        // V1 规则: 已经绑定水站不允许新申请绑定; 若想换站, 必须先完成解绑
        if (staff.getStationId() != null) {
            throw new BusinessException("已绑定水站, 如需更换请先申请解绑");
        }

        // 目标水站必须存在
        if (stationMapper.getById(stationId) == null) {
            throw new BusinessException("目标水站不存在");
        }

        // 防重复: 同一个配送员对同一水站不允许同时存在多个"待审批绑定申请"
        if (appMapper.countPendingDup(staffId, stationId, StaffStationApplication.TYPE_BIND) > 0) {
            throw new BusinessException("已存在对该水站的待审批申请");
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
    }

    // ==================== 配送员: 取消自己的绑定申请 ====================

    @Transactional(rollbackFor = Exception.class)
    public void cancelApply(Map<String, Object> params) {
        Long staffId = AuthContext.getUserId();

        // [2026-09-25 返工 R6] 先锁自己的员工行（统一锁序：员工聚合 → 申请行）：
        // 撤回与"站长同意"并发时，两边都按同一顺序取锁 ⇒ 不会形成环，胜者由状态 CAS 决定。
        staffMapper.getByIdForUpdate(staffId);

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
            throw new BusinessException("找不到待取消的申请");
        }
        if (!target.getStaffId().equals(staffId)) {
            throw new BusinessException("只能取消自己发起的申请");
        }
        if (target.getStatus() != StaffStationApplication.STATUS_PENDING) {
            throw new BusinessException("当前申请状态不允许取消");
        }

        // 上面是"读后判断"，这里必须再 CAS 一次并检查行数：读与写之间站长可能已经同意了这条申请
        //（本轮返工 R6）。无条件 update 会把"已同意"改写成"已取消"，而归属已经改了 —— 静默不一致。
        if (appMapper.cancelIfPending(target.getId()) == 0) {
            throw new BusinessException("该申请已被处理，请刷新后重试");
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", target.getId());
        detail.put("stationId", target.getStationId());
        auditLogService.log("STAFF_BINDING", "CANCEL_APPLY", "staff:" + staffId, detail.toString(), null);
    }

    // ==================== 配送员: 申请解绑 (F) ====================

    @Transactional(rollbackFor = Exception.class)
    public void unbindRequest(Map<String, Object> params) {
        Long staffId = AuthContext.getUserId();
        String applyNote = params != null && params.get("applyNote") != null ? params.get("applyNote").toString() : "";

        // [2026-09-25 返工 R6] 加锁读员工行（统一锁序），见 applyBind 的注释
        Staff staff = staffMapper.getByIdForUpdate(staffId);
        if (staff == null) {
            throw new BusinessException("员工不存在");
        }
        if (staff.getStationId() == null) {
            throw new BusinessException("当前未绑定任何水站");
        }

        Long stationId = staff.getStationId();

        // 避免对同一站同时存在多条待审批解绑申请
        if (appMapper.countPendingDup(staffId, stationId, StaffStationApplication.TYPE_UNBIND) > 0) {
            throw new BusinessException("已存在待审批的解绑申请, 请等待处理");
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
    }

    // ==================== 配送员: 查询自己的绑定状态 (派生) ====================

    public Map<String, Object> getBindStatus() {
        Long staffId = AuthContext.getUserId();
        Staff staff = staffMapper.getById(staffId);
        if (staff == null) {
            throw new BusinessException("员工不存在");
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
        return res;
    }

    // ==================== 站长: 同意绑定申请 (D) ====================

    @Transactional(rollbackFor = Exception.class)
    public void approveBind(BindingActionDTO.Handle params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();

        // applicationId 或 staffId 二选一 (优先取 applicationId, 兼容性更好)
        StaffStationApplication app = null;
        if (params.getApplicationId() != null) {
            app = appMapper.getById(params.getApplicationId());
        }
        if (app == null && params.getStaffId() != null) {
            Long staffId = params.getStaffId();
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
            throw new BusinessException("找不到待审批的绑定申请");
        }
        if (app.getStatus() != StaffStationApplication.STATUS_PENDING) {
            throw new BusinessException("申请已处理");
        }
        if (app.getType() != StaffStationApplication.TYPE_BIND) {
            throw new BusinessException("这不是绑定申请");
        }
        if (!app.getStationId().equals(myStationId)) {
            throw new BusinessException("该申请不属于本站");
        }

        // [2026-09-25 返工 R6] **先锁员工行**（聚合根），再动申请行。
        // 这一步是本方法唯一的取锁顺序声明：下面是"改申请 → 改归属 → 取消该员工其它申请"，
        // 三步横跨两张表；若并发方走的是相反顺序（先申请后员工），两者就会互相等待成环。
        // 统一成"员工 → 申请"后，同一员工的审批/撤回/解绑全部退化为排队；拿不到锁的一方等到
        // 前者提交，再被 CAS 判成"已被处理"→ code=1（**可读业务拒绝**，不是 500 / SYSTEM 告警）。
        // 这里必须重新读一次：加锁前读到的那份可能是旧的（role / station_id 正是下面要判的字段）。
        Staff staff = staffMapper.getByIdForUpdate(app.getStaffId());
        if (staff == null) {
            throw new BusinessException("员工不存在");
        }
        if (!"DELIVERY".equals(staff.getRole())) {
            throw new BusinessException("仅配送员可审批绑定");
        }
        if (staff.getStationId() != null) {
            throw new BusinessException("该配送员当前已绑定其他水站");
        }

        // [2026-09-25 架构评审问题 8] 两步都是 **CAS + 检查行数**，不再"先查再无条件写"。
        // 顺序固定：先落申请（它才是本次动作的对象），再改归属；任一步拿不到行数就抛异常
        // ——@Transactional 会把前一步一起回滚，绝不会留下"申请已同意但归属没变"的半截状态。
        int handled = appMapper.handleIfPending(app.getId(), StaffStationApplication.STATUS_APPROVED, myStaffId,
                params.getHandleNote() != null ? params.getHandleNote() : "");
        if (handled == 0) {
            throw new BusinessException("该申请已被处理（可能已被其他站长同意或拒绝），请刷新后重试");
        }
        int bound = staffMapper.updateStationIdIfUnbound(staff.getId(), myStationId);
        if (bound == 0) {
            // 同一员工的两个站的申请被同时同意：只有一个能真正拿到这个人。
            // 归属只许有一个，所以这里必须整笔失败（上面那条"申请已同意"随之回滚）。
            throw new BusinessException("该配送员已被其他水站接收，请刷新列表");
        }
        // 归属已定 ⇒ 该员工在别站挂着的待审批申请**不可能再生效**，同事务内一并作废，
        // 免得对方站长的待办里留一条永远批不掉的申请（与 select-role 改选时的处理同口径）。
        appMapper.cancelOtherPending(staff.getId(), app.getId());

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", staff.getId());
        detail.put("staffName", staff.getName());
        detail.put("stationId", myStationId);
        detail.put("handleStaffId", myStaffId);
        auditLogService.log("STAFF_BINDING", "APPROVE_BIND", "staff:" + staff.getId(), detail.toString(), String.valueOf(myStaffId));
    }

    // ==================== 站长: 拒绝绑定申请 (E) ====================

    @Transactional(rollbackFor = Exception.class)
    public void rejectBind(BindingActionDTO.Handle params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();
        String reason = params.getReason() != null ? params.getReason() : "";

        StaffStationApplication app = null;
        if (params.getApplicationId() != null) {
            app = appMapper.getById(params.getApplicationId());
        }
        if (app == null && params.getStaffId() != null) {
            Long staffId = params.getStaffId();
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
            throw new BusinessException("找不到待审批的绑定申请");
        }
        if (app.getStatus() != StaffStationApplication.STATUS_PENDING) {
            throw new BusinessException("申请已处理");
        }
        if (app.getType() != StaffStationApplication.TYPE_BIND) {
            throw new BusinessException("这不是绑定申请");
        }
        if (!app.getStationId().equals(myStationId)) {
            throw new BusinessException("该申请不属于本站");
        }

        // [2026-09-25 返工 R6] 统一锁序：员工聚合（行锁）在前、申请行在后。
        // 本方法只写一条申请行，看似不需要；但"同意/撤回"两步走的是这个顺序，
        // 拒绝若反着来（先申请后员工）就会给它们制造环 —— 锁序是全组的约定，不是单点的选择。
        staffMapper.getByIdForUpdate(app.getStaffId());

        // [2026-09-25 架构评审问题 8] CAS + 检查行数：与"同意"并发时只有一个能落，
        // 否则会出现"同意 8 秒后又被拒绝覆盖"而归属已经改了的矛盾状态。
        int handled = appMapper.handleIfPending(app.getId(), StaffStationApplication.STATUS_REJECTED, myStaffId, reason);
        if (handled == 0) {
            throw new BusinessException("该申请已被处理，请刷新后重试");
        }
        // 注意: 拒绝绑定不修改 staff.station_id (仍为 NULL)

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", app.getStaffId());
        detail.put("stationId", myStationId);
        detail.put("reason", reason);
        auditLogService.log("STAFF_BINDING", "REJECT_BIND", "staff:" + app.getStaffId(), detail.toString(), String.valueOf(myStaffId));
    }

    // ==================== 站长: 同意解绑申请 (G) ====================

    @Transactional(rollbackFor = Exception.class)
    public void unbindConfirm(BindingActionDTO.Handle params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();
        String handleNote = params.getHandleNote() != null ? params.getHandleNote() : "";

        StaffStationApplication app = null;
        if (params.getApplicationId() != null) {
            app = appMapper.getById(params.getApplicationId());
        }
        if (app == null && params.getStaffId() != null) {
            Long staffId = params.getStaffId();
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
            throw new BusinessException("找不到待审批的解绑申请");
        }
        if (app.getStatus() != StaffStationApplication.STATUS_PENDING) {
            throw new BusinessException("申请已处理");
        }
        if (app.getType() != StaffStationApplication.TYPE_UNBIND) {
            throw new BusinessException("这不是解绑申请");
        }
        if (!app.getStationId().equals(myStationId)) {
            throw new BusinessException("该申请不属于本站");
        }

        Staff staff = staffMapper.getByIdForUpdate(app.getStaffId());
        if (staff == null) {
            throw new BusinessException("员工不存在");
        }
        if (staff.getStationId() == null || !staff.getStationId().equals(app.getStationId())) {
            throw new BusinessException("配送员当前归属与申请不一致");
        }

        // [2026-09-25 架构评审问题 8] 同"同意绑定"：先落申请、再清归属，两步都 CAS + 检查行数。
        // 清归属必须带 expected（当前归属 = 申请里的那一站）：员工若已被调走/别站接管，
        // 这里绝不能把**新归属**抹掉（那会让正在送货的配送员突然变成无归属）。
        int handled = appMapper.handleIfPending(app.getId(), StaffStationApplication.STATUS_APPROVED, myStaffId, handleNote);
        if (handled == 0) {
            throw new BusinessException("该申请已被处理（可能已被其他站长同意或拒绝），请刷新后重试");
        }
        int cleared = staffMapper.clearStationIdIf(staff.getId(), app.getStationId());
        if (cleared == 0) {
            throw new BusinessException("该配送员当前归属已变更，解绑未执行，请刷新后重试");
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", staff.getId());
        detail.put("staffName", staff.getName());
        detail.put("stationId", myStationId);
        auditLogService.log("STAFF_BINDING", "UNBIND_CONFIRM", "staff:" + staff.getId(), detail.toString(), String.valueOf(myStaffId));
    }

    // ==================== 站长: 拒绝解绑申请 ====================

    @Transactional(rollbackFor = Exception.class)
    public void unbindReject(BindingActionDTO.Handle params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();
        String reason = params.getReason() != null ? params.getReason() : "";

        StaffStationApplication app = null;
        if (params.getApplicationId() != null) {
            app = appMapper.getById(params.getApplicationId());
        }
        if (app == null && params.getStaffId() != null) {
            Long staffId = params.getStaffId();
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
            throw new BusinessException("找不到待审批的解绑申请");
        }
        if (app.getStatus() != StaffStationApplication.STATUS_PENDING) {
            throw new BusinessException("申请已处理");
        }
        if (app.getType() != StaffStationApplication.TYPE_UNBIND) {
            throw new BusinessException("这不是解绑申请");
        }
        if (!app.getStationId().equals(myStationId)) {
            throw new BusinessException("该申请不属于本站");
        }

        // [2026-09-25 返工 R6] 统一锁序：员工聚合（行锁）在前、申请行在后（同 rejectBind 的注释）
        staffMapper.getByIdForUpdate(app.getStaffId());

        // [2026-09-25 架构评审问题 8] CAS + 检查行数（同"拒绝绑定申请"）
        int handled = appMapper.handleIfPending(app.getId(), StaffStationApplication.STATUS_REJECTED, myStaffId, reason);
        if (handled == 0) {
            throw new BusinessException("该申请已被处理，请刷新后重试");
        }
        // 拒绝解绑不修改 staff.station_id (保持已绑定状态)

        Map<String, Object> detail = new HashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("staffId", app.getStaffId());
        detail.put("stationId", myStationId);
        detail.put("reason", reason);
        auditLogService.log("STAFF_BINDING", "REJECT_UNBIND", "staff:" + app.getStaffId(), detail.toString(), String.valueOf(myStaffId));
    }

    // ==================== 站长: 单方面解除配送员 (H) — 直接改 station_id=NULL, 不走申请 ====================

    /**
     * 站长强制解除**配送员**与本站的归属关系。
     *
     * <p>⚠️ 2026-09-19 补了两条护栏，之前**一个都没有**（实测能把站长自己解除掉）：
     * 本方法原来只校验「员工存在 + 属于本站」，而列表数据源
     * {@link #getManagerStaff()} 用的是 {@code staffMapper.listByStationId}
     * （`where station_id = ? and status = 1`，**不带 role 条件**）→ 站长自己也在返回集里，
     * 前端两个页面都给站长那一行渲染了「解除」按钮，点下去真的成功。</p>
     *
     * <p><b>解除站长 = 把水站变成没人管的孤儿</b>：{@code staff.station_id} 被置 NULL 之后，
     * 该站长所有走 {@code AuthContext.requireStationId()} 的端点全部失败、被前端路由到"创建水站"，
     * 而 {@code station} 表、客户、订单、库存、{@code customer_station_config} 全都留在库里 ——
     * 客户还能照常给这个站下单，却再没有任何站长能接单。重建水站会拿到**新 id**，旧数据搬不回来。
     * 这条不是"少一个按钮"的问题，是**不可逆的脏数据制造机**。</p>
     *
     * <p>两条护栏缺一不可：① 目标必须是 {@code DELIVERY}（挡"解除别的站长"）；
     * ② 不能解除自己（挡"解除自己"，它同样会孤儿化水站）。
     * 前端两处（{@code station-mgmt/staff/}、{@code mine/}）也把站长行的按钮换成了纯文字，
     * 但**判定必须以服务端为准** —— 列表里看不到不等于 id 编不出来（见 AGENTS §1.1 的同形判据）。</p>
     *
     * <p>站长退出/交接水站属于另一个功能（水站转让，需要"接手人 + 二次确认 + 数据迁移范围"），
     * <b>当前全仓没有这个端点</b>，不要拿本端点当地址用。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void release(BindingActionDTO.Release params) {
        Long myStaffId = AuthContext.getUserId();
        Long myStationId = AuthContext.requireStationId();
        Long staffId = params.getStaffId();
        String reason = params.getReason() != null ? params.getReason() : "站长强制解除绑定";

        Staff staff = staffMapper.getByIdForUpdate(staffId);
        if (staff == null) {
            throw new BusinessException("员工不存在");
        }
        if (staff.getStationId() == null || !staff.getStationId().equals(myStationId)) {
            throw new BusinessException("该员工不属于本站");
        }
        // ① 只允许解除配送员：解除站长会让水站失去唯一管理者（见本方法 javadoc）
        if (!"DELIVERY".equals(staff.getRole())) {
            throw new BusinessException("只能解除配送员，站长不能这样解除；水站交接请走水站转让");
        }
        // ② 不能解除自己（防自伤；正常情况下站长的 role 已被①挡住，这里再兜一层）
        if (staffId.equals(myStaffId)) {
            throw new BusinessException("不能解除自己");
        }

        // [2026-09-25 架构评审问题 8] 置空归属也走 CAS + 检查行数：
        // 与"同意解绑申请"并发时，谁先到谁生效，后到者拿 0 行、直接拒绝，
        // 不再出现"两个动作都返回成功、但归属到底谁清的说不清"。
        int cleared = staffMapper.clearStationIdIf(staffId, myStationId);
        if (cleared == 0) {
            throw new BusinessException("该员工当前归属已变更，请刷新后重试");
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("staffId", staffId);
        detail.put("staffName", staff.getName());
        detail.put("stationId", myStationId);
        detail.put("reason", reason);
        auditLogService.log("STAFF_BINDING", "FORCE_UNBIND", "staff:" + staffId, detail.toString(), String.valueOf(myStaffId));
    }

    // ==================== 站长: 查询水站收到的申请列表 ====================

    public List<Map<String, Object>> getBindApplications(String status, Integer type) {
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
        return result;
    }

    // ==================== 站长: 查询水站下所有在职员工 ====================

    public List<Staff> getManagerStaff() {
        Long myStationId = AuthContext.requireStationId();
        // 复用 station_id 过滤在职员工 (站长 + 配送员)
        return staffMapper.listByStationId(myStationId);
    }

    // ==================== 配送员: 列出自己的申请历史 ====================

    public List<Map<String, Object>> getMyApplications() {
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
        return result;
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
