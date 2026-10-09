package com.example.aquaflow.service;

import com.example.aquaflow.constant.WeChatApp;
import com.example.aquaflow.dto.AuthRequestDTO;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.StaffStationApplication;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.entity.UserToken;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StaffStationApplicationMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.mapper.UserTokenMapper;
import com.example.aquaflow.service.impl.StationServiceImpl;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.JwtUtil;
import com.example.aquaflow.util.MaskUtil;
import com.example.aquaflow.util.PasswordUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证与会话的编排（2026-09-29 从 {@code LoginController} 下沉）。
 *
 * <p><b>本类是登录/建站流程的事务边界</b>：{@code selectRole} 与 {@code createStationAndBind}
 * 的 {@code @Transactional} 原来开在 Controller（HTTP 层），下沉后 Controller 只剩
 * 认证注解 + DTO 校验 + 调服务 + 包 {@code Result}。业务失败一律抛
 * {@link BusinessException}（→ 全局处理器 {@code code=1}，与原 {@code Result.error(msg)}
 * 的响应体一致），<b>别在服务里返回 Result</b>。</p>
 *
 * <p>⚠️ 原 {@code wx-login-staff} 对 {@code RuntimeException} 包了「微信登录失败: 」前缀再
 * 以 code=1 返回（不走 500）—— 该错误翻译由 {@link WeChatLoginService#staffCode2Session(String)}
 * 保留；认证事务直接传播失败，协议事实和会话写入一起回滚。</p>
 */
@Slf4j
@Service
public class AuthTokenService {

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

    /** [2026-09-30 F-03③] 员工绑定的凭据来源：站长签发的一次性绑定码。 */
    @Autowired
    private StaffBindCodeService staffBindCodeService;

    @Autowired private AgreementAcknowledgementService agreementAcknowledgementService;
    @Autowired private AgreementCatalogService agreementCatalogService;

    // ==================== 微信小程序登录 ====================

    /**
     * 微信登录（用户小程序）：仅查 customer 表，与 staff 表完全独立
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> wxLogin(AuthRequestDTO.WxLogin params) {
        String code = params.getCode();
        agreementAcknowledgementService.validateLogin("CUSTOMER", params.getAgreement());

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

        boolean agreementRecorded = agreementAcknowledgementService.recordLogin("customer", customer.getId(), params.getAgreement());
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
        data.put("agreementCatalog", agreementCatalogService.catalog("CUSTOMER"));
        data.put("agreementRecorded", agreementRecorded);

        return data;
    }

    /**
     * 配送端微信登录：任何微信用户可进入。
     * <ul>
     *   <li>staff 存在 → 正常登录 (返回按 station_id 派生的 bindingStatus)</li>
     *   <li>staff 不存在 → 返回 role=UNSELECTED, needSelectRole=true, 交由 /select-role 创建</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> wxLoginStaff(AuthRequestDTO.WxLoginStaff params) {
        String code = params.getCode();
        agreementAcknowledgementService.validateLogin("STAFF", params.getAgreement());

        // 只让微信服务翻译换码错误；认证事务不捕获失败，协议事实和token写入一起回滚。
        Map<String, Object> wxSession = weChatLoginService.staffCode2Session(code);
        String openid = (String) wxSession.get("openid");
        log.info("[wx-login-staff] openid={}", MaskUtil.maskOpenid(openid));

        Staff staff = staffMapper.findByOpenid(openid);
        log.info("[wx-login-staff] staff查询结果: staffId={}, role={}, status={}, stationId={}",
                staff != null ? staff.getId() : "null",
                staff != null ? staff.getRole() : "null",
                staff != null ? staff.getStatus() : "null",
                staff != null ? staff.getStationId() : "null");

        if (staff != null) {
            if (staff.getStatus() == null || !Integer.valueOf(1).equals(staff.getStatus())) {
                log.warn("[wx-login-staff] 账号已停用, staffId={}, status={}", staff.getId(), staff.getStatus());
                throw new BusinessException("该账号已停用");
            }
            log.info("[wx-login-staff] 员工已绑定, 开始生成token, staffId={}", staff.getId());
            boolean recorded = agreementAcknowledgementService.recordLogin("staff", staff.getId(), params.getAgreement());
            Map<String,Object> result = buildStaffWxLoginResult(staff);
            result.put("agreementCatalog", agreementCatalogService.catalog("STAFF"));
            result.put("agreementRecorded", recorded);
            return result;
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
            // No staff row yet: do not store an openid/virtual ID as agreement evidence or expand guide permissions.
            data.put("agreementCatalog", agreementCatalogService.catalog("STAFF"));
            data.put("agreementRecorded", false);

            log.info("[wx-login-staff] 返回UNSELECTED结果, openid={}", MaskUtil.maskOpenid(openid));
            return data;
        } catch (Exception e) {
            log.error("[wx-login-staff] UNSELECTED流程异常: openid={}, error={}", MaskUtil.maskOpenid(openid), e.getMessage(), e);
            throw new BusinessException("登录失败(UNSELECTED): " + e.getMessage());
        }
    }

    /**
     * 首次进入配送端选择角色 (站长/配送员)；**未生效时也可用于改选**。
     * <ul>
     *   <li>STATION_MANAGER: 创建 staff, station_id=null (后续调用 /create-station 创建水站再绑定)</li>
     *   <li>DELIVERY: 创建 staff, station_id=null (后续申请绑定水站; 不自动绑定默认站)</li>
     * </ul>
     *
     * <p>[2026-09-17 产品口径] <b>「选择 ≠ 生效」</b>：选角色只是登记意向。若该员工还没挂到任何水站
     * （{@code station_id} 为空），允许再次调用本接口**原地改选**另一个角色 —— 前端在
     * apply-bind / create-station 页提供「重新选择身份」入口。一旦生效（{@code station_id} 非空）
     * 就拒绝自行更改，须走管理员。</p>
     *
     * <p>本方法有多步写入（撤销悬挂的待审批申请 → 改角色 → 清旧 token），故加事务。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> selectRole(AuthRequestDTO.SelectRole params) {
        // AQ-006: 权限提权防护 — 仅配送端(staff)账号可选择角色，顾客(userType=customer)必须用 customer 身份。
        // 否则顾客可调用此接口创建 STATION_MANAGER/DELIVERY 员工记录并拿到 staff JWT，完成提权。
        if (!"staff".equals(AuthContext.getUserType())) {
            throw new BusinessException("仅配送端账号可选择角色");
        }

        Long userId = AuthContext.getUserId();
        String roleParam = params.getRole();
        String nickname = params.getNickname();
        String phone = params.getPhone();

        // ===== 情况一：该 token 已对应一条真实员工记录（userId 是真实 staffId）=====
        // 这只可能是「改选」。[2026-09-17 产品口径] **「选择 ≠ 生效」**：选角色只是登记意向，
        // 还没挂到水站上就不算生效，此时允许回上一步改选（前端在 apply-bind / create-station
        // 提供「重新选择身份」入口）；一旦生效就禁止自行更改，须走管理员。
        //
        // 判据：两种角色「生效」时都落在同一个字段上，不必分角色判断 ——
        //   配送员生效 = 站长批准了绑定（StaffStationApplicationService.approveBind → updateStationId）
        //   站长生效   = 已建好水站（本类 createStationAndBind → staff.setStationId）
        // 两者都表现为 staff.station_id 非空。
        //
        // ⚠️ **本分支不需要 pendingOpenid，也不能把它挡在前面**：改选的凭据就是 token 里的
        // userId（它本身就是那条员工记录），比"客户端自报 openid"更硬。而身份选定后签发的
        // token 走的是 generateAccessToken(userId, userType, role, stationId) **四参重载**
        // → pendingOpenid 为 null；若把 openid 校验放在本分支之前，改选会永远以
        // 「身份信息缺失」失败（这正是初版实现过的错）。
        // [2026-09-25 返工 R6] 用**行锁读**（统一锁序：员工聚合 → 申请行）。改选这一步要
        // "撤掉该员工挂着的待审批申请 + 改 role"，若按"先锁申请行、再锁员工行"来做，
        // 就与站长审批（员工 → 申请）正好相反，两者并发即可构成循环等待。
        Staff exist = (userId != null && userId > 0) ? staffMapper.getByIdForUpdate(userId) : null;
        if (exist != null) {
            if (exist.getStationId() != null) {
                throw new BusinessException("身份已生效（已绑定水站），如需更换请联系管理员处理");
            }
            // 未生效 → **原地改既有记录**，不新建（新建会撞 uk_staff_openid）。
            // 先撤掉挂着的待审批绑定申请：配送员改选站长后那条申请已无人认领，
            // 而且站长那边点「同意」会被 approveBind 的「仅配送员可审批绑定」挡回去 ——
            // 等于给站长留了一个永远批不掉的申请。
            List<StaffStationApplication> pendings = appMapper.listByStaff(exist.getId());
            if (pendings != null) {
                for (StaffStationApplication a : pendings) {
                    if (a.getStatus() != null
                            && a.getStatus() == StaffStationApplication.STATUS_PENDING) {
                        appMapper.cancel(a.getId());
                    }
                }
            }
            exist.setRole(roleParam);
            if (nickname != null && !nickname.isEmpty()) exist.setName(nickname);
            if (phone != null && !phone.isEmpty()) exist.setPhone(phone);
            exist.setUpdateTime(LocalDateTime.now());
            // 注意 staffMapper.update() 是**全量写**（含 role / station_id / password_hash）：
            // 这里传的是刚从库里**加锁**读出来的实体（getByIdForUpdate，锁持有到本事务提交，
            // 不存在读改写窗口），且 role 已被 DTO 的 @Pattern 限死为两个合法值，
            // 不会把 station_id / password_hash 写坏（不要改成接收客户端实体）。
            staffMapper.update(exist);

            userTokenMapper.deleteByUser(userId, "staff");
            return buildStaffWxLoginResult(exist);
        }

        // ===== 情况二：还没有员工记录 → 需要新建。**此时才需要 openid，且只认 JWT 里签入的那个** =====
        // [2026-09-12 修复] 旧实现读 params.getPendingOpenid()（请求体里的 _pendingOpenid），
        // 等于让调用方自报身份：任何持 UNSELECTED token 的人把该字段换成别人的 openid，
        // 就能把那个 openid 绑到自己新建的员工记录上；配合 staff.uk_staff_openid 唯一键，
        // 真实主人之后再登录会直接落到这条被抢绑的记录上。
        // 现在 openid 在 wx-login-staff 签发 token 时就已签入 claims，此处从 AuthContext 读取，
        // 客户端传什么参数都不再影响结果（字段保留仅为兼容旧客户端，不参与判定）。
        String pendingOpenid = AuthContext.getPendingOpenid();
        if (pendingOpenid == null || pendingOpenid.isEmpty()) {
            throw new BusinessException("身份信息缺失，请重新登录");
        }

        // 该微信已经绑定过员工 → 不能再建一条（否则撞 uk_staff_openid，报的是难懂的数据库错误）
        Staff bound = staffMapper.findByOpenid(pendingOpenid);
        if (bound != null) {
            throw new BusinessException("该微信已绑定员工账号，请直接登录");
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
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createStationAndBind(AuthRequestDTO.CreateStation params) {
        String authRole = AuthContext.getRole();
        if (!"STATION_MANAGER".equals(authRole) && !"manager".equals(authRole)) {
            throw new BusinessException("仅站长账号可创建水站");
        }
        Long staffId = AuthContext.getUserId();
        if (staffId == null || staffId <= 0) {
            throw new BusinessException("请先完成身份选择");
        }
        // [2026-09-29 下沉收口] 原来是普通读 getById：两个并发建站请求都能读到 station_id=NULL、
        // 各自插出一座站，第二个把 staff 绑过去 —— 第一座站成为没人管的孤儿站（数据搬不回来）。
        // 本方法有事务，改行锁读让后到者排队到提交后，重读就能看到已绑定 → 拒绝。
        Staff staff = staffMapper.getByIdForUpdate(staffId);
        if (staff == null) throw new BusinessException("员工不存在");
        if (staff.getStationId() != null) {
            throw new BusinessException("该账号已绑定水站，请勿重复创建");
        }
        if (!"STATION_MANAGER".equals(staff.getRole())) {
            throw new BusinessException("仅 STATION_MANAGER 可创建水站");
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
        // [2026-09-17 / v34] 地图选点坐标。
        // 此前这里**从没读过** params 的 latitude/longitude，而建站页一直在发它们 ——
        // 站长在地图上选的点被静默丢弃（DTO 当时也没有这两个字段，Jackson 直接忽略未知字段）。
        // 坐标是配送范围判定的前提：没有它，"超出范围"这件事根本无从判断。
        // 留空是允许的（站长可以不定位），为空时范围校验跳过而不是拒单。
        station.setLat(params.getLatitude());
        station.setLng(params.getLongitude());
        station.setStatus(1);
        station.setCreateTime(LocalDateTime.now());
        station.setUpdateTime(LocalDateTime.now());
        // [2026-09-19] 名称 + 联系电话必填（判据的唯一实现在 StationServiceImpl.requireContactFields）：
        // 这是站长**实际走的那条**建站路径，另一条是 StationController.POST /api/stations —— 两条都要校验。
        StationServiceImpl.requireContactFields(station);
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
        return data;
    }

    /**
     * 员工绑定微信 —— [2026-09-30 F-03③] 凭据已由「姓名 + 手机号」改为**站长签发的一次性绑定码**。
     *
     * <p>⚠️ {@code POST /api/auth/bind-staff} 是**免认证**端点，所以"拿什么证明你是这名员工"
     * 就是这条端点的全部安全性。原来的凭据是姓名 + 手机号 —— 两项**公开信息**，却能签发员工会话：
     * 谁拿到某在职员工的这两项，就能在该员工还没绑微信的窗口期内把账号绑到自己微信上，
     * 随后读到本站订单与客户数据。当时只能做两层缓解（失败文案统一以消除姓名枚举 + 按来源 IP 限流），
     * 而**限流只能减慢、不能阻止** —— 凭据本身是公开信息时，防线就不存在。</p>
     *
     * <p><b>现在</b>：站长在员工管理里点「生成绑定码」（{@link StaffBindCodeService#generate}：
     * 10 分钟有效、一次性、一员工同时只有一个），当面或电话交给该员工；员工输码绑定。
     * 攻击面收敛到"拿到实时的那 6 位数"。决策正本 {@code docs/design/16} §9.3；
     * 表见 {@code sql/migration_v69_staff_bind_code.sql}。</p>
     *
     * <p>⚠️ 三条别改回去：① 码的**一次性**由 {@link StaffBindCodeMapper#markUsed} 的 CAS 保证，
     * 别改成"先查再写"；② 这里**不再**按凭据做失败锁定（那个 {@code "bind:"+name+phone} 的键
     * 随凭据一起废了 —— 姓名+手机号不再参与判定），但**按 IP 限流必须留着**
     * （注册名单在 {@code WebMvcConfig}）；③ 「该账号已绑定其他微信」**不要**与"码无效"合并 ——
     * 它是"码确实有效、只是这账号已有微信"的合法回执，员工换微信号重进时要看得懂自己为什么绑不上，
     * 而且这种账号也抢不走。</p>
     */
    public Map<String, Object> bindStaff(AuthRequestDTO.BindStaff params) {
        String wxCode = params.getCode();
        String bindCode = params.getBindCode();

        Map<String, Object> wxSession = weChatLoginService.code2Session(WeChatApp.STAFF, wxCode);
        String openid = wxSession.get("openid").toString();

        // 先消费码、再动 staff：码无效 / 已过期 / 已被用过 ⇒ 直接拒，且三种情况同一条文案
        // （分开报就成了"码存在性预言机"，理由见 StaffBindCodeService.INVALID_CODE）。
        Long staffId = staffBindCodeService.consume(bindCode, openid);

        Staff staff = staffMapper.getById(staffId);
        if (staff == null) {
            // 码指向的员工在签发后被删了：极罕见，但要说清楚 —— 否则调用方只看到一句"绑定失败"
            throw new BusinessException("该绑定码对应的员工不存在，请让站长重新生成");
        }
        if (staff.getStatus() != null && !Integer.valueOf(1).equals(staff.getStatus())) {
            throw new BusinessException("该账号已停用");
        }
        if (staff.getOpenid() != null && !staff.getOpenid().equals(openid)) {
            throw new BusinessException("该账号已绑定其他微信");
        }

        staff.setOpenid(openid);
        // [2026-09-29 下沉收口] 原来是全量 update()（把整行旧快照写回去：读改写窗口内
        // 任何并发改动都会被覆盖）。CAS 版只按列写 openid，且 WHERE 带「空或同值」条件 ——
        // 上面的判空与写入之间若有人抢先绑了**别的**微信，这里拿 0 行、报同一条文案。
        if (staffMapper.bindOpenid(staff.getId(), openid) == 0) {
            throw new BusinessException("该账号已绑定其他微信");
        }

        log.info("[F-03③] 员工微信绑定成功(凭绑定码): staffId={}, name={}", staff.getId(), staff.getName());

        return buildStaffWxLoginResult(staff, true);
    }

    // ==================== 更新资料 ====================

    public void updateProfile(AuthRequestDTO.UpdateProfile params) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BusinessException("用户ID不能为空");
        }

        String nickname = params.getNickname();
        String phone = params.getPhone();

        if ("staff".equals(AuthContext.getUserType())) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null) {
                throw new BusinessException("用户不存在");
            }
            // [2026-09-29 下沉收口] 原来是 getById → 改字段 → 全量 update()：
            // 读与写之间 role / station_id / password_hash 的并发改动会被这份旧快照整行覆盖
            // （lost update）。白名单更新只碰 name / phone / status（status 原样回写）。
            staffMapper.updateBaseInfo(userId,
                    nickname != null && !nickname.isEmpty() ? nickname : staff.getName(),
                    phone != null && !phone.isEmpty() ? phone : staff.getPhone(),
                    staff.getStatus());
            return;
        }

        Customer customer = customerMapper.getById(userId);
        if (customer == null) {
            throw new BusinessException("用户不存在");
        }

        // 同上：原来是全量 customerMapper.update()（openid / customer_type / note /
        // first_order_time / last_delivery_time 一并写旧值），改白名单按列更新。
        customerMapper.updateProfileInfo(userId, nickname, phone);
    }

    // ==================== 管理后台登录 ====================

    // [AQ-040] 登录/绑定失败限流（单体应用内存计数即可）：key -> [失败次数, 窗口起始毫秒]
    private static final ConcurrentHashMap<String, long[]> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();
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

    public Map<String, Object> login(AuthRequestDTO.Login params) {
        String username = params.getUsername();
        String password = params.getPassword();

        // [AQ-040] 同一用户名 15 分钟内失败次数超限即锁定，防暴力破解
        String lockKey = "login:" + username;
        if (tooManyAttempts(lockKey)) {
            throw new BusinessException("尝试次数过多，请 15 分钟后再试");
        }

        Staff staff = staffMapper.findByName(username);
        if (staff != null) {
            if (staff.getPasswordHash() != null && PasswordUtil.matches(password, staff.getPasswordHash())) {
                clearFailures(lockKey);
                return buildStaffLoginResult(staff);
            }
        }

        recordFailure(lockKey);
        throw new BusinessException("用户名或密码错误");
    }

    // ==================== Token 刷新 ====================

    /** 轮换需经 Spring 事务代理：旧凭据锁、精确删除和新凭据插入必须一起提交/回滚。 */
    // A waiting old-token query must not hold an RR gap that blocks replacement insertion.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> refresh(AuthRequestDTO.Refresh params) {
        String refreshToken = params.getRefreshToken();

        if (!jwtUtil.validateToken(refreshToken)) {
            throw new BusinessException("refreshToken已过期，请重新登录");
        }

        io.jsonwebtoken.Claims claims = jwtUtil.parseToken(refreshToken);
        String tokenType = claims.get("tokenType", String.class);
        if (!"refresh".equals(tokenType)) {
            throw new BusinessException("无效的refreshToken");
        }

        Long userId = claims.get("userId", Number.class).longValue();
        String userType = claims.get("userType", String.class);

        if (!"staff".equals(userType) && !"customer".equals(userType)) {
            throw new BusinessException("无效的refreshToken");
        }

        UserToken storedToken = userTokenMapper.findByRefreshToken(refreshToken);
        if (storedToken == null || !userId.equals(storedToken.getUserId())
                || !userType.equals(storedToken.getUserType())
                || !refreshToken.equals(storedToken.getRefreshToken())) {
            throw new BusinessException("令牌已失效，请重新登录");
        }

        String role;
        Long stationId = null;

        if ("staff".equals(userType)) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null) throw new BusinessException("用户不存在");
            // #6: 检查员工状态，禁用员工不允许刷新token
            if (staff.getStatus() == null || !Integer.valueOf(1).equals(staff.getStatus())) {
                throw new BusinessException("该账号已停用");
            }
            role = mapStaffRole(staff);
            stationId = (staff.getStationId() != null && staff.getStationId() == 0) ? null : staff.getStationId();
        } else {
            Customer customer = customerMapper.getById(userId);
            if (customer == null) throw new BusinessException("用户不存在");
            role = "customer";
            stationId = null;
        }

        String newAccessToken = jwtUtil.generateAccessToken(userId, userType, role, stationId);

        // [2026-10-05 F-78] 原非事务的先删后插会丢旧凭据；撤销也能在间隙结束后被晚到插入复活。
        // 旧凭据 FOR UPDATE 锁一直持有至替换提交，撤销须等待并删除已提交替换；撤销先完成则刷新拒绝。
        // 同一旧凭据的并发重放须在锁后重读，不保证两次都成功；已返回的新凭据不能被失败请求全清。
        // 仍禁止调用登录用的 saveRefreshToken/deleteByUser，否则会撤销其它会话。
        String newRefreshToken = jwtUtil.generateRefreshToken(userId, userType);
        // 删除旧token记录（同值的重复行一并清掉，见 UserTokenMapper.findByRefreshToken 的说明）
        userTokenMapper.deleteByRefreshToken(refreshToken);
        // [2026-10-02 F-78] 原误调登录用的 saveRefreshToken 会全清，删掉并发请求已返回的新 token。
        // 轮换只追加本次凭据；同用户全清仍由登录入口负责。
        insertRefreshToken(userId, userType, newRefreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", newAccessToken);
        data.put("refreshToken", newRefreshToken);
        return data;
    }

    // ==================== 登出 ====================

    public void logout(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
                Long userId = claims.get("userId", Number.class).longValue();
                String userType = claims.get("userType", String.class);
                userTokenMapper.deleteByUser(userId, userType);
            }
        }
    }

    // ==================== 验证登录状态 ====================

    public Map<String, Object> me() {
        Long userId = AuthContext.getUserId();
        String userType = AuthContext.getUserType();

        if (userId == null) {
            throw new BusinessException("未登录");
        }

        Map<String, Object> data = new HashMap<>();

        if ("staff".equals(userType)) {
            Staff staff = staffMapper.getById(userId);
            if (staff == null) throw new BusinessException("用户不存在");
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
            if (customer == null) throw new BusinessException("用户不存在");
            data.put("customerId", customer.getId());
            data.put("nickname", customer.getName());
            data.put("phone", customer.getPhone());
            data.put("role", "customer");
            data.put("userType", "customer");
        }

        return data;
    }

    // ==================== 修改密码 ====================

    public void changePassword(AuthRequestDTO.ChangePassword params) {
        Long userId = AuthContext.getUserId();
        String userType = AuthContext.getUserType();

        if (!"staff".equals(userType)) {
            throw new BusinessException("仅支持员工修改密码");
        }

        Staff staff = staffMapper.getById(userId);
        if (staff == null) throw new BusinessException("用户不存在");

        String oldPassword = params.getOldPassword();
        String newPassword = params.getNewPassword();

        if (staff.getPasswordHash() != null) {
            if (!PasswordUtil.matches(oldPassword, staff.getPasswordHash())) {
                throw new BusinessException("旧密码错误");
            }
        } else {
            throw new BusinessException("账号未设置密码，请联系管理员重置");
        }

        if (newPassword.length() < 6) {
            throw new BusinessException("新密码长度不能少于6位");
        }

        // [2026-09-29 下沉收口] 原来是 getById → 改 passwordHash → 全量 update()：
        // 读与写之间 role / station_id / status 的并发改动会被旧快照覆盖（lost update）。
        // 按列 UPDATE 只碰 password_hash（旧密码已在上面验过），别改回全量写。
        staffMapper.updatePassword(staff.getId(), PasswordUtil.encode(newPassword));

        userTokenMapper.deleteByUser(staff.getId(), "staff");
    }

    // ==================== 私有辅助方法 ====================

    private Map<String, Object> buildStaffWxLoginResult(Staff staff) {
        return buildStaffWxLoginResult(staff, false);
    }

    private Map<String, Object> buildStaffWxLoginResult(Staff staff, boolean fromBindStaff) {
        // [2026-09-29 下沉] 原来这里 catch 后返回 Result.error（HTTP 层 ⇒ 事务照常提交）。
        // 下沉后本方法可能在 @Transactional（selectRole / createStationAndBind）里被调用，
        // 抛异常 ⇒ **整笔回滚**：token 没签出来时，角色选定/建站绑定也一并撤销，
        // 不再留下"库改了、客户端却拿到失败"的半截状态（原实现会留孤儿站）。
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
            return data;
        } catch (Exception e) {
            log.error("[wx-login-staff] buildStaffWxLoginResult 异常: staffId={}, error={}", staff.getId(), e.getMessage(), e);
            throw new BusinessException("登录构建失败: " + e.getMessage());
        }
    }

    private Map<String, Object> buildStaffLoginResult(Staff staff) {
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
        return data;
    }

    private void saveRefreshToken(Long userId, String userType, String refreshToken) {
        // #3: 清理该用户旧的refresh token，防止累积
        userTokenMapper.deleteByUser(userId, userType);
        insertRefreshToken(userId, userType, refreshToken);
    }

    /** 只追加凭据；刷新不得通过登录的清旧逻辑撤销其它已通过校验的并发轮换。 */
    private void insertRefreshToken(Long userId, String userType, String refreshToken) {
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

    // [AQ-047] openid 脱敏 —— [2026-09-30 修 F-35] 实现已收到 util/MaskUtil（全仓唯一实现），
    // 本类不再自建副本（原先这里有一个 private maskOpenid，与 WeChatLoginService 靠注释同步 —— F-35 就是这么分叉的）。

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
