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
            List<com.example.aquaflow.entity.Orders> orders = orderMapper.list(myStationId, customerId, null, null, null);
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

        log.info("save address: userType={}, userId={}, address.customerId={}, address={}", userType, userId, address.getCustomerId(), address);

        // 客户端：使用 JWT 中的 customerId
        if ("customer".equals(userType)) {
            address.setCustomerId(AuthContext.requireCustomerId());
        }
        // 管理端：检查权限
        else if (AuthContext.isManager()) {
            // 显式传了 customerId：放宽校验，允许为任意客户创建地址（录入/调试场景）
            if (address.getCustomerId() != null) {
                log.info("manager debug mode: using explicit customerId={}, skip station check", address.getCustomerId());
            } else {
                Result<Void> check = checkCustomerStation(address.getCustomerId());
                if (check != null) return check;
            }
        }
        // 兼容：staff 调试时可显式传 customerId
        else if (address.getCustomerId() != null) {
            log.info("staff debug mode: using explicit customerId={}", address.getCustomerId());
        } else {
            return Result.error("权限不足，请用客户账号登录或传入 customerId");
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
        return Result.success(addressService.list(null, keyword));
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
            List<com.example.aquaflow.entity.Orders> orders = orderMapper.list(myStationId, c.getId(), null, null, null);
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
        // 管理端：检查权限
        else if (AuthContext.isManager()) {
            // 显式传了 customerId：放宽校验
            if (address.getCustomerId() != null && !address.getCustomerId().equals(existing.getCustomerId())) {
                log.info("manager debug mode: changing customerId {} -> {}, skip station check", existing.getCustomerId(), address.getCustomerId());
            } else {
                Result<Void> check = checkCustomerStation(existing.getCustomerId());
                if (check != null) return check;
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