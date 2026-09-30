package com.example.aquaflow.service;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffBindCodeMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BusinessTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 员工微信绑定码（v69）—— 站长签发、10 分钟有效、一次性。
 *
 * <p><b>为什么要有它</b>（F-03③，正本 {@code docs/design/16} §9.3）：`POST /api/auth/bind-staff`
 * 是免认证端点，原来靠「姓名 + 手机号」认人 —— 而这两项是**公开信息**，却能签发员工会话。
 * 限流只能减慢暴力尝试，不能构成防线；把凭据换成**只有站长能签发、且只能用一次**的码，
 * 才真正把"谁能绑"收回到水站自己手里。</p>
 *
 * <p>⚠️ 三条不许改的口径：<br>
 * ① **站别只认登录态**（{@link AuthContext#requireStationId()}），绝不接受请求里传来的 stationId ——
 *    否则 A 站站长能给 B 站员工签发绑定码，等于跨站夺取账号。<br>
 * ② **码是一次性的**：消费走 {@link StaffBindCodeMapper#markUsed} 的 CAS，看受影响行数；
 *    不要改回"先查能用、再写已用"。<br>
 * ③ **已经绑过微信的员工不再签发**：真要换微信得先走解绑流程，别让"再发一个码"变成绕过解绑的通道。</p>
 *
 * <p>⚠️ <b>[F-44 2026-09-30] 签发与校验必须是同一个时钟</b>：{@code expires_at} 由本类的
 * {@link BusinessTime} 算出，校验侧的时效比较也由同一个 {@code now} 作为**入参**传进 Mapper
 * —— 别再让 Mapper 写 {@code expires_at > NOW()}（那读的是**库的时钟**）：两侧不同源时，
 * 冻结 Java 时钟会表现为"刚签发的码被拒"，而拨快 Java 时钟就是**过期码仍可用**。
 * 边界用例见 {@code StaffBindCodeIntegrationTest#codeValidityFollowsInjectedClock}。</p>
 */
@Slf4j
@Service
public class StaffBindCodeService {

    /** 有效期（分钟）。10 分钟够"当面告诉 / 打个电话"，又短到猜码没有胜算。 */
    private static final int VALID_MINUTES = 10;

    /** 撞码重试次数。6 位数字 = 100 万种，同时存在的有效码只有个位数，撞上基本不可能。 */
    private static final int GEN_RETRY = 20;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 码无效/过期/已被用过时的**唯一**文案。
     *
     * <p>三种失败共用一句是**有意的**：分开报（「已过期」vs「已被使用」）等于告诉调用方
     * "这个码真的存在过"，就成了一个码存在性预言机 —— 与 {@code AuthTokenService} 里
     * bind-staff 凭据文案统一的理由同源。</p>
     */
    public static final String INVALID_CODE = "绑定码无效或已过期，请让站长重新生成";

    private final StaffBindCodeMapper bindCodeMapper;
    private final StaffMapper staffMapper;

    /** 全仓唯一的"取现在"入口（F-16）；本类的签发与校验都从它取值，见类注释的 F-44 说明。 */
    private final BusinessTime businessTime;

    private final SecureRandom random = new SecureRandom();

    public StaffBindCodeService(StaffBindCodeMapper bindCodeMapper, StaffMapper staffMapper,
                               BusinessTime businessTime) {
        this.bindCodeMapper = bindCodeMapper;
        this.staffMapper = staffMapper;
        this.businessTime = businessTime;
    }

    /**
     * 站长为本站员工签发一个绑定码。
     *
     * @return {@code code} / {@code expiresAt} / {@code validMinutes} / {@code staffName}
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generate(Long staffId) {
        Long stationId = AuthContext.requireStationId();
        Staff staff = staffMapper.getById(staffId);
        if (staff == null) {
            throw new BusinessException("员工不存在");
        }
        // 口径①：只认登录态里的站别。请求里传什么 staffId 都行，但那个员工必须在**我的站**。
        if (staff.getStationId() == null || !staff.getStationId().equals(stationId)) {
            throw new BusinessException("只能为本水站员工生成绑定码");
        }
        if (staff.getStatus() != null && !Integer.valueOf(1).equals(staff.getStatus())) {
            throw new BusinessException("该员工已停用，不能生成绑定码");
        }
        // 口径③：已绑过微信的不再签发（换微信要走解绑，不能靠重复发码绕开）
        if (staff.getOpenid() != null && !staff.getOpenid().isEmpty()) {
            throw new BusinessException("该员工已绑定微信，如需更换请先在员工管理里解绑");
        }

        // 一员工同时只有一个有效码：先清旧的，否则旧码在有效期内还能用（等于多把钥匙）
        bindCodeMapper.deleteUnusedForStaff(staffId);

        // [F-44] 业务"此刻"只取一次，撞码探测与 expires_at 都用它 —— 两次取时间会跨零点分叉。
        LocalDateTime now = businessTime.now();

        String code = null;
        for (int i = 0; i < GEN_RETRY; i++) {
            // 6 位数字、允许前导 0（%06d）：不要把码当成整数处理，否则 "012345" 会变成 5 位。
            String candidate = String.format("%06d", random.nextInt(1_000_000));
            if (bindCodeMapper.findUsableStaffId(candidate, now) == null) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            throw new BusinessException("生成绑定码失败，请重试");
        }

        LocalDateTime expiresAt = now.plusMinutes(VALID_MINUTES);
        bindCodeMapper.insert(staffId, stationId, code, expiresAt, AuthContext.getUserId());

        // ⚠️ 日志只记"给谁发了"与到期时间，**不记码本身**（本仓 §6：日志不许落可用于登录的凭据）。
        log.info("[F-03③] 站长签发绑定码: staffId={}, stationId={}, 有效期至 {}", staffId, stationId, expiresAt);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("staffName", staff.getName());
        data.put("validMinutes", VALID_MINUTES);
        data.put("expiresAt", FMT.format(expiresAt));
        return data;
    }

    /**
     * 消费一个绑定码，返回它指向的员工 id（免认证端点调用，**不要**在这里读 {@code AuthContext}）。
     *
     * @throws BusinessException 码不存在 / 已过期 / 已被用过 —— 三种情况同一条文案（见 {@link #INVALID_CODE}）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long consume(String code, String openid) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(INVALID_CODE);
        }
        // [F-44] 同一个 now 同时用于"查得到吗"与"标记已用"这两步 —— 中间的零点跨越会让
        // 第一步说还有效、第二步却因过期而 CAS 命中 0 行，表现成"码明明在有效期内却被拒"。
        LocalDateTime now = businessTime.now();
        Long staffId = bindCodeMapper.findUsableStaffId(code.trim(), now);
        if (staffId == null) {
            throw new BusinessException(INVALID_CODE);
        }
        // CAS：两个请求拿同一个码同时进来时，只有先到的能拿到 1 行
        if (bindCodeMapper.markUsed(code.trim(), openid, now) == 0) {
            throw new BusinessException(INVALID_CODE);
        }
        return staffId;
    }
}
