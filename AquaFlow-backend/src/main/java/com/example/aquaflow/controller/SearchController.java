package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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

    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<Map<String, Object>> search(@RequestParam String keyword) {
        Map<String, Object> result = new HashMap<>();
        Long stationId = AuthContext.requireStationId();
        result.put("customers", customerMapper.searchByStation(stationId, keyword));
        result.put("addresses", addressMapper.listByStation(stationId, keyword));
        result.put("orders", orderMapper.searchByKeywordAndStation(stationId, keyword));
        return Result.success(result);
    }
}
