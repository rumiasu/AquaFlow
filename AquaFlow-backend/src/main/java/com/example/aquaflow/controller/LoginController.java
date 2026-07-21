package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.WeChatLoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class LoginController {

    @Autowired
    private WeChatLoginService weChatLoginService;

    @Autowired
    private CustomerMapper customerMapper;

    /**
     * 微信小程序登录
     * POST /api/auth/wx-login  { code: "wx.login()返回的code" }
     *
     * 流程：
     * 1. 用 code 调用微信 jscode2session 换取 openid
     * 2. 根据 openid 查找/创建客户记录
     * 3. 返回 token + 客户信息
     */
    @PostMapping("/wx-login")
    public Result<Map<String, Object>> wxLogin(@RequestBody Map<String, String> params) {
        String code = params.get("code");
        if (code == null || code.isEmpty()) {
            return Result.error("登录code不能为空");
        }

        // 1. 调用微信接口获取openid
        Map<String, String> wxSession = weChatLoginService.code2Session(code);
        String openid = wxSession.get("openid");

        // 2. 根据openid查找或创建客户
        Customer customer = customerMapper.findByOpenid(openid);
        if (customer == null) {
            // 首次登录，创建新客户
            customer = new Customer();
            customer.setName("微信用户");
            customer.setPhone("");
            customer.setOpenid(openid);
            customer.setCustomerType(1);
            customer.setCreateTime(LocalDateTime.now());
            customer.setUpdateTime(LocalDateTime.now());
            customerMapper.insertWithOpenid(customer);
        }

        // 3. 生成token（简单实现，用 openid + 时间戳）
        String token = "wx-" + openid + "-" + System.currentTimeMillis();

        // 4. 返回
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("phone", customer.getPhone());
        data.put("isNew", customer.getCreateTime().isEqual(customer.getUpdateTime()));

        return Result.success(data);
    }

    /**
     * 更新客户微信绑定的昵称和手机号
     * POST /api/auth/update-profile
     */
    @PostMapping("/update-profile")
    public Result<Void> updateProfile(@RequestBody Map<String, Object> params) {
        Integer customerId = (Integer) params.get("customerId");
        String nickname = (String) params.get("nickname");
        String phone = (String) params.get("phone");

        if (customerId == null) {
            return Result.error("客户ID不能为空");
        }

        Customer customer = customerMapper.getById(customerId);
        if (customer == null) {
            return Result.error("客户不存在");
        }

        if (nickname != null && !nickname.isEmpty()) {
            customer.setName(nickname);
        }
        if (phone != null && !phone.isEmpty()) {
            customer.setPhone(phone);
        }
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.update(customer);

        return Result.success();
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params) {
        String username = params.get("username");
        String password = params.get("password");
        if ("admin".equals(username) && "123456".equals(password)) {
            Map<String, Object> data = new HashMap<>();
            data.put("token", "aquaflow-token-" + System.currentTimeMillis());
            data.put("username", username);
            data.put("nickname", "管理员");
            return Result.success(data);
        }
        return Result.error("用户名或密码错误");
    }

    /**
     * 开发模式登录（跳过微信验证，直接用 openid 登录）
     * POST /api/auth/dev-login
     */
    @PostMapping("/dev-login")
    public Result<Map<String, Object>> devLogin(@RequestBody Map<String, String> params) {
        String openid = params.getOrDefault("openid", "dev-openid-001");
        String nickname = params.getOrDefault("nickname", "测试用户");

        Customer customer = customerMapper.findByOpenid(openid);
        if (customer == null) {
            customer = new Customer();
            customer.setName(nickname);
            customer.setPhone("");
            customer.setOpenid(openid);
            customer.setCustomerType(1);
            customer.setCreateTime(LocalDateTime.now());
            customer.setUpdateTime(LocalDateTime.now());
            customerMapper.insertWithOpenid(customer);
        } else {
            customer.setName(nickname);
            customer.setUpdateTime(LocalDateTime.now());
            customerMapper.update(customer);
        }

        String token = "dev-" + openid + "-" + System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("phone", customer.getPhone());
        return Result.success(data);
    }

    /**
     * 验证登录状态
     * GET /api/auth/me?customerId=xxx
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestParam Integer customerId) {
        if (customerId == null) {
            return Result.error("未登录");
        }
        Customer customer = customerMapper.getById(customerId);
        if (customer == null) {
            return Result.error("用户不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("customerId", customer.getId());
        data.put("nickname", customer.getName());
        data.put("phone", customer.getPhone());
        return Result.success(data);
    }
}
