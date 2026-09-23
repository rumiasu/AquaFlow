package com.example.aquaflow.config;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动时自动为缺少密码的员工初始化 BCrypt 密码。
 * - admin 账号：初始密码 admin123
 * - 其他员工：初始密码 123456
 * 仅处理 password 字段为 NULL 的记录，已有密码的不会覆盖。
 */
@Component
@Order(1)
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
                staff.setPasswordHash(PasswordUtil.encode(defaultPassword));
                staffMapper.update(staff);
                count++;
                log.info("为员工 [{}] 初始化默认密码", staff.getName());
            }
        }

        if (count > 0) {
            log.info("密码初始化完成，共处理 {} 个员工账号。请及时修改默认密码！", count);
        } else {
            log.debug("所有员工账号已有密码，无需初始化");
        }
    }
}
