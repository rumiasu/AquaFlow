package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.service.AddressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/addresses")
@Slf4j
public class AddressController {

    @Autowired
    private AddressService addressService;

    @PostMapping
    public Result save(@RequestBody Address address) {
        addressService.save(address);
        return Result.success(address.getId());
    }

    @GetMapping
    public Result<List<Address>> list(@RequestParam(required = false) String tag,
                                      @RequestParam(required = false) String keyword) {
        return Result.success(addressService.list(tag, keyword));
    }

    @GetMapping("/{id}")
    public Result<Address> getById(@PathVariable Integer id) {
        return Result.success(addressService.getById(id));
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody Address address) {
        address.setId(id);
        addressService.update(address);
        return Result.success();
    }
}
