package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private AddressMapper addressMapper;
    @Autowired
    private OrderMapper orderMapper;

    @GetMapping
    public Result<Map<String, Object>> search(@RequestParam String keyword) {
        Map<String, Object> result = new HashMap<>();
        result.put("customers", customerMapper.search(keyword));
        result.put("addresses", addressMapper.search(keyword));
        return Result.success(result);
    }
}
