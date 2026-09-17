package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.AddressService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
/**
 * 收货地址接口（<b>顾客自助</b>）。
 *
 * <p>身份一律由 {@code AuthContext.requireCustomerId()} 取得，<b>不信任请求参数里的 customerId</b>。
 * 因此本类不加 {@code @RequireRole} —— 那不是"忘了加"，而是「顾客自助端点」的标准写法
 * （见 {@code aspect/RequireRoleAspect.java} 的「新增端点强制约定」）。</p>
 */
@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    @Autowired
    private AddressService addressService;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private OrderMapper orderMapper;

    private Result<Void> checkCustomerStation(Long customerId) {
        if (customerId == null) return Result.error("缺少客户ID");
        Customer c = customerMapper.getById(customerId);
        if (c == null) return Result.error("客户不存在");
        // 检查客户是否在当前站长的站点有过订单
        Long myStationId = AuthContext.getStationId();
        if (myStationId != null) {
            List<com.example.aquaflow.entity.Orders> orders = orderMapper.list(myStationId, customerId, null, null, null, null, null);
            if (orders.isEmpty()) {
                return Result.error("无权操作他站客户");
            }
        }
        return null;
    }

    @PostMapping
    public Result save(@RequestBody Address address) {
        String userType = AuthContext.getUserType();
        Long userId = AuthContext.getUserId();

        // [2026-09-16] 原来把整个 Address 对象打进日志（收件人姓名 / 电话 / 完整门牌），
        // 属个人信息，不应落日志（OPS-001 非功能-隐私判定 NG 项）。
        // 只保留定位问题所需的非敏感标识。
        log.info("save address: userType={}, userId={}, addressId={}, address.customerId={}",
                userType, userId, address.getId(), address.getCustomerId());

        // 客户端：使用 JWT 中的 customerId
        if ("customer".equals(userType)) {
            address.setCustomerId(AuthContext.requireCustomerId());
        }
        // 管理端：客户必须归属本水站。
        // [AQ-037] 删除"显式传 customerId 即跳过校验"的调试后门 —— 该分支允许站长/任意 staff
        // 为全平台任意客户新建地址（越权写入 + 泄露他站客户资料）。
        else if (AuthContext.isManager()) {
            Result<Void> check = checkCustomerStation(address.getCustomerId());
            if (check != null) return check;
        } else {
            // 非客户、非站长（如配送员）：一律拒绝，不再保留"传 customerId 即可写"的口子
            return Result.error("权限不足，仅客户本人或本站站长可创建地址");
        }

        if (address.getCustomerId() == null) {
            return Result.error("缺少客户ID");
        }
        addressService.save(address);
        return Result.success(address.getId());
    }

    @GetMapping
    public Result<List<Address>> list(@RequestParam(required = false) String keyword) {
        String userType = AuthContext.getUserType();
        Long userId = AuthContext.getUserId();

        if ("customer".equals(userType)) {
            Long customerId = AuthContext.requireCustomerId();
            return Result.success(addressService.list(customerId, keyword));
        } else if (AuthContext.isManager()) {
            Long stationId = AuthContext.requireStationId();
            return Result.success(addressService.listByStation(stationId, keyword));
        }
        // AQ-013: 兜底分支严禁返回全平台地址簿 — 配送/其他角色无权查看全部地址，返回空列表。
        return Result.success(java.util.Collections.emptyList());
    }

    @GetMapping("/{id}")
    public Result<Address> getById(@PathVariable Long id) {
        Address address = addressService.getById(id);
        if (address == null) {
            return Result.error("地址不存在");
        }
        if ("customer".equals(AuthContext.getUserType())) {
            if (!AuthContext.requireCustomerId().equals(address.getCustomerId())) {
                return Result.error("无权查看他人地址");
            }
        } else if (AuthContext.isDelivery()) {
            return Result.error("权限不足");
        } else if (AuthContext.isManager()) {
            Customer c = customerMapper.getById(address.getCustomerId());
            if (c == null) return Result.error("客户不存在");
            Long myStationId = AuthContext.requireStationId();
            List<com.example.aquaflow.entity.Orders> orders = orderMapper.list(myStationId, c.getId(), null, null, null, null, null);
            if (orders.isEmpty()) {
                return Result.error("无权查看他站客户地址");
            }
        }
        return Result.success(address);
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody Address address) {
        Address existing = addressService.getById(id);
        if (existing == null) return Result.error("地址不存在");

        // 客户端：只能改自己的
        if ("customer".equals(AuthContext.getUserType())) {
            if (!AuthContext.requireCustomerId().equals(existing.getCustomerId())) {
                return Result.error("无权修改他人地址");
            }
        }
        // 管理端：客户必须归属本水站。
        // [AQ-037] 删除"改 customerId 即跳过校验"的调试后门 —— 原分支允许站长把地址改挂到他站客户名下。
        else if (AuthContext.isManager()) {
            Result<Void> check = checkCustomerStation(existing.getCustomerId());
            if (check != null) return check;
            // 禁止通过本接口把地址改挂到别的客户名下
            if (address.getCustomerId() != null && !address.getCustomerId().equals(existing.getCustomerId())) {
                return Result.error("不支持变更地址归属客户");
            }
        } else {
            return Result.error("权限不足");
        }

        address.setId(id);
        addressService.update(address);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        Long customerId = AuthContext.requireCustomerId();
        addressService.delete(customerId, id);
        return Result.success();
    }

    @PutMapping("/{id}/default")
    public Result setDefault(@PathVariable Long id) {
        Long customerId = AuthContext.requireCustomerId();
        addressService.setDefault(customerId, id);
        return Result.success();
    }
}