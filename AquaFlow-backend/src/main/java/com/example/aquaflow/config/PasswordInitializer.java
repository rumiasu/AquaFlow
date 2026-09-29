package com.example.aquaflow.config;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时给**还没有密码**的员工补初始密码 —— <b>仅限非生产环境</b>。
 *
 * <h3>为什么 prod 必须排除（2026-09-29 关雷）</h3>
 * <ul>
 *   <li>默认口令 {@code admin123} / {@code 123456} 是**公开知识**（写在本注释与 sql/README 里）。
 *       {@code /api/auth/login} 的放行判据是「hash 非空且匹配」（LoginController）——
 *       所以在 prod 跑一次本 Runner，等于给所有没密码的员工各发一把人人皆知的钥匙。</li>
 *   <li>更隐蔽的一点：微信绑定建号（wx-login-staff → {@code staffMapper.insert}）**本来就不设密码**，
 *       {@code password_hash IS NULL} 是**正常状态**、不是待修复数据。旧实现把"正常"当"缺失"
 *       批量补上了已知口令。</li>
 *   <li>prod 下空密码账号登录自然失败（hash==null 直接不放行，LoginController 已有判空），
 *       这才是安全默认。</li>
 * </ul>
 *
 * <p><b>唯一闸</b>：{@code @Profile("!prod")}（与 {@code DevLoginController} 同一手法，
 * prod 下本 Bean 根本不存在）。原 {@code app.password-initializer.enabled} 开关已于 2026-09-29
 * 随拍板 A 一并删除（见下），非 prod 是否补密码不再可配 —— 本地/测试库需要默认口令的既有用例与
 * seed 脚本依赖它一直开着，没有关的需求。</p>
 *
 * <p>写库走 {@link StaffMapper#updatePasswordIfEmpty}（CAS 只填空），不再用整行覆盖的
 * {@code update()}：补密码这件小事不该握着改 role / station_id 的能力。</p>
 *
 * <p><b>2026-09-29 拍板 A（已落地）</b>：生产<b>放弃账号密码登录</b>，员工一律微信登录；
 * 首个账号口令如需下发，走 seed SQL 预置 {@code password_hash}，不走本类。
 * 正本 = {@code docs/design/16 §9.3}，架构说明见 {@code docs/architecture/01-系统架构.md}。</p>
 */
@Component
@Order(1)
@Profile("!prod")
public class PasswordInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordInitializer.class);

    @Autowired
    private StaffMapper staffMapper;

    @Override
    public void run(String... args) {
        List<Staff> allStaff = staffMapper.listAll();
        int count = 0;

        for (Staff staff : allStaff) {
            if (staff.getPasswordHash() == null || staff.getPasswordHash().isEmpty()) {
                String defaultPassword = "admin".equals(staff.getName()) ? "admin123" : "123456";
                // CAS 只填空：并发/重复启动时后到者不会覆盖已写入或已被本人改过的密码
                int updated = staffMapper.updatePasswordIfEmpty(staff.getId(), PasswordUtil.encode(defaultPassword));
                if (updated > 0) {
                    count++;
                    log.info("为员工[{}] 初始化默认密码（仅非生产环境生效）", staff.getName());
                }
            }
        }

        if (count > 0) {
            log.info("密码初始化完成，共处理 {} 个员工账号。请尽快修改默认密码。", count);
        } else {
            log.debug("所有员工账号已有密码，无需初始化");
        }
    }
}
