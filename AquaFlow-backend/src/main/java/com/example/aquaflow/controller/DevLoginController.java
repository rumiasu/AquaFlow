package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
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
import com.example.aquaflow.util.JwtUtil;
import com.example.aquaflow.util.PasswordUtil;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发模式登录（免微信授权直接换 JWT）。
 * <p>
 * 安全说明：这是一个"凭 role 参数直接换 token"的后门，任何拿到它的人都可以把自己变成站长。
 * 此前它常驻在 LoginController 中，仅靠一个布尔开关保护——开关一旦被误配为 true（或被环境变量覆盖），
 * 生产环境等同于完全不设防。
 * </p>
 * 现在的做法：整个类从生产包中物理移除，而不是靠运行时开关。
 * <ul>
 *   <li>{@code @Profile("!prod")}：prod 环境该 Bean 根本不会被创建，接口 404</li>
 *   <li>{@code @ConditionalOnProperty}：非 prod 环境也需显式配置 DEV_LOGIN_ENABLED=true 才生效</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@Profile("!prod")
@ConditionalOnProperty(name = "app.dev-login-enabled", havingValue = "true")
public class DevLoginController {

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

    @PostMapping("/dev-login")
    public Result<Map<String, Object>> devLogin(@RequestBody @Valid AuthRequestDTO.DevLogin params) {
        log.warn("[DEV-LOGIN] 开发模式登录被调用，该接口仅限非生产环境使用");
        String role = params.getRole() != null ? params.getRole() : "customer";

        if ("DELIVERY".equals(role)) {
            return devLoginDelivery();
        }
        if ("STATION_MANAGER".equals(role)) {
            return devLoginStationManager();
        }

        String openid = params.getOpenid() != null ? params.getOpenid() : "dev-openid-001";
        String nickname = params.getNickname() != null ? params.getNickname() : "测试用户";

        Customer customer = customerMapper.findByOpenid(openid);
        if (customer == null) {
            customer = new Customer();
            customer.setName(nickname);
            customer.setPhone("");
            customer.setOpenid(openid);
            customer.setCustomerType(1);
            customer.setCreateTime(LocalDateTime.now());
            customer.setUpdateTime(LocalDateTime.now());
            customerMapper.insert(customer);
        }

        String accessToken = jwtUtil.generateAccessToken(customer.getId(), "customer", "customer", null);
        String refreshToken = jwtUtil.generateRefreshToken(customer.getId(), "customer");
        saveRefreshToken(customer.getId(), "customer", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("phone", customer.getPhone());
        data.put("role", "customer");
        return Result.success(data);
    }

    private Result<Map<String, Object>> devLoginDelivery() {
        Staff delivery = staffMapper.findByRole("DELIVERY");
        if (delivery == null) {
            delivery = new Staff();
            delivery.setName("配送员");
            delivery.setPhone("13800000000");
            delivery.setPasswordHash(PasswordUtil.encode("123456"));
            delivery.setRole("DELIVERY");
            delivery.setStatus(1);
            delivery.setStationId(null);
            delivery.setCreateTime(LocalDateTime.now());
            delivery.setUpdateTime(LocalDateTime.now());
            staffMapper.insert(delivery);
        }

        Long stationId = (delivery.getStationId() != null && delivery.getStationId() == 0) ? null : delivery.getStationId();
        if (stationId == null) {
            for (Station s : stationMapper.listAll()) {
                stationId = s.getId();
                break;
            }
        }
        if (stationId != null) {
            delivery.setStationId(stationId);
        }
        String bindingStatus = deriveBindingStatus(delivery);

        String accessToken = jwtUtil.generateAccessToken(delivery.getId(), "staff", "delivery", stationId);
        String refreshToken = jwtUtil.generateRefreshToken(delivery.getId(), "staff");
        saveRefreshToken(delivery.getId(), "staff", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("staffId", delivery.getId());
        data.put("nickname", delivery.getName());
        data.put("phone", delivery.getPhone());
        data.put("role", "delivery");
        data.put("staffRole", "DELIVERY");
        data.put("stationId", stationId);
        data.put("bindingStatus", bindingStatus);
        data.put("userType", "staff");
        return Result.success(data);
    }

    private Result<Map<String, Object>> devLoginStationManager() {
        Staff manager = staffMapper.findByRole("STATION_MANAGER");
        if (manager == null) {
            manager = new Staff();
            manager.setName("站长");
            manager.setPhone("13900000000");
            manager.setPasswordHash(PasswordUtil.encode("123456"));
            manager.setRole("STATION_MANAGER");
            manager.setStatus(1);
            manager.setStationId(null);
            manager.setCreateTime(LocalDateTime.now());
            manager.setUpdateTime(LocalDateTime.now());
            staffMapper.insert(manager);
        }

        Long stationId = (manager.getStationId() != null && manager.getStationId() == 0) ? null : manager.getStationId();
        String bindingStatus = deriveBindingStatus(manager);

        String accessToken = jwtUtil.generateAccessToken(manager.getId(), "staff", "manager", stationId);
        String refreshToken = jwtUtil.generateRefreshToken(manager.getId(), "staff");
        saveRefreshToken(manager.getId(), "staff", refreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", accessToken);
        data.put("refreshToken", refreshToken);
        data.put("staffId", manager.getId());
        data.put("nickname", manager.getName());
        data.put("phone", manager.getPhone());
        data.put("role", "manager");
        data.put("staffRole", "STATION_MANAGER");
        data.put("stationId", stationId);
        data.put("bindingStatus", bindingStatus);
        data.put("userType", "staff");
        return Result.success(data);
    }

    private void saveRefreshToken(Long userId, String userType, String refreshToken) {
        userTokenMapper.deleteByUser(userId, userType);
        UserToken userToken = new UserToken();
        userToken.setUserId(userId);
        userToken.setUserType(userType);
        userToken.setRefreshToken(refreshToken);
        userToken.setExpireTime(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshTokenExpiry() / 1000));
        userToken.setCreateTime(LocalDateTime.now());
        userTokenMapper.insert(userToken);
    }

    private String deriveBindingStatus(Staff staff) {
        if (staff == null) return "UNBOUND";
        if ("STATION_MANAGER".equals(staff.getRole())) {
            return staff.getStationId() != null ? "BOUND" : "UNBOUND";
        }
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
