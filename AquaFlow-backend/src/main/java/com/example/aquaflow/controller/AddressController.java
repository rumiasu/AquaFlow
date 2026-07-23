package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.service.AddressService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/addresses")
@Slf4j
public class AddressController {

    @Autowired
    private AddressService addressService;

    @RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})
    @PostMapping
    public Result save(@RequestBody Address address) {
        addressService.save(address);
        return Result.success();
    }

    @GetMapping
    public Result<List<Address>> list(@RequestParam(required = false) String tag,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Integer customerId) {
        // 客户只能看自己的地址，员工可以看所有或按客户筛选
        if ("customer".equals(AuthContext.getUserType())) {
            customerId = AuthContext.requireCustomerId();
        }
        return Result.success(addressService.list(customerId, tag, keyword));
    }

    @GetMapping("/{id}")
    public Result<Address> getById(@PathVariable Integer id) {
        return Result.success(addressService.getById(id));
    }

    @RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})
    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody Address address) {
        address.setId(id);
        addressService.update(address);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        Integer customerId = AuthContext.requireCustomerId();
        addressService.delete(customerId, id);
        return Result.success();
    }

    @PutMapping("/{id}/default")
    public Result setDefault(@PathVariable Integer id) {
        Integer customerId = AuthContext.requireCustomerId();
        addressService.setDefault(customerId, id);
        return Result.success();
    }

    @GetMapping("/report/tags")
    public Result<List<Map<String, Object>>> reportTags() {
        return Result.success(addressService.countByTag());
    }
}
